package io.github.danisec2601.idelink.core

import com.google.gson.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real processes connect to a real socket. python3 plays the bridge, this test JVM plays claude
 * (the peer's parent), so the gate's checks run against actual /proc entries, not mocks.
 */
class GateTest {
    private val dir = Path.of(System.getProperty("user.home"), "dev/tmp/idelink-test")
    private val sock = dir.resolve("s/ide.sock")
    private val audit = dir.resolve("audit.log")
    private val python = Path.of("/usr/bin/python3").toRealPath()
    private val jvm = Path.of(ProcessHandle.current().info().command().get()).toRealPath()
    private val asked = AtomicInteger()
    private var allowPairing = true
    private var pairAnswer: () -> Approval.Answer =
        { if (allowPairing) Approval.Answer.Allowed else Approval.Answer.Denied("declined", asked = true) }
    private val warned = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private lateinit var server: Server

    private val echoed = AtomicInteger()
    private val tools = listOf(
        ToolDef("ping", "answers pong", Schema.Obj(emptyMap())) { JsonPrimitive("pong") },
        ToolDef("echo", "echoes n", Schema.Obj(mapOf("n" to Schema.Integer()), required = setOf("n"))) {
            echoed.incrementAndGet(); JsonPrimitive(it.get("n").asInt)
        },
        ToolDef("nsme", "throws", Schema.Obj(emptyMap())) { throw NoSuchMethodError("Foo.bar()V") },
        ToolDef("ncdfe", "throws", Schema.Obj(emptyMap())) { throw NoClassDefFoundError("com/intellij/Gone") },
        ToolDef("linkage", "throws", Schema.Obj(emptyMap())) { throw LinkageError("loader constraint violation") },
        ToolDef("runtime", "throws", Schema.Obj(emptyMap())) { throw IllegalStateException("state went odd") },
    )

    private val me = LibC.getuid()
    private val noConfig = dir.resolve("no-such-trusted-claude")
    private val paired = java.util.concurrent.CopyOnWriteArrayList<PeerGate.Peer>()

    private fun start(
        apiMissing: List<String> = emptyList(),
        claudes: TrustedClaude = TrustedClaude(noConfig, me, jvm),
        requireRootOwned: Boolean = false,
        maxConnections: Int = Server.MAX_CONNECTIONS,
        idleMs: Long = Server.IDLE_MS,
    ) {
        server = Server(sock, PeerGate(python, claudes, requireRootOwned), tools,
            pair = { paired += it; asked.incrementAndGet(); pairAnswer() }, audit = Audit(audit),
            ideBuild = "PY-263.test",
            apiMissing = apiMissing, warn = { _, t -> warned += t }, maxConnections = maxConnections, idleMs = idleMs)
        server.start()
    }

    private val clientPy = dir.resolve("client.py")
    /** Sends each request line in turn and prints each reply line ('' once the server hangs up). */
    private fun clientSource(vararg reqs: String) = """
        import socket,sys
        try:
            s=socket.socket(socket.AF_UNIX); s.connect(sys.argv[1]); f=s.makefile()
            for r in ${reqs.joinToString(",", "[", "]") { "r'" + it + "'" }}:
                s.sendall(r.encode()+b'\n'); print(f.readline().strip())
        except OSError:
            print('')
    """.trimIndent()
    private fun call(tool: String, args: String = "{}") = """{"id":1,"tool":"$tool","args":$args}"""

    @BeforeTest
    fun up() {
        Files.createDirectories(dir)
        Files.deleteIfExists(audit)
        Files.writeString(clientPy, clientSource(call("ping")))
        // requireRootOwned=false: the test JVM (the "claude" stand-in) may be a user-owned JBR. The
        // ownership check has its own test below.
        start()
    }

    @AfterTest
    fun down() = server.close()

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "client hung: ${cmd.joinToString(" ")}")
        return p.inputStream.bufferedReader().readText().trim()
    }

    private fun auditText() = if (Files.exists(audit)) Files.readString(audit) else ""

    /** A client that pings, then (after [pauseS] s) pings again or, with [again] false, just waits for EOF. */
    private fun holder(name: String, again: Boolean = false, pauseS: Double = 0.0): Path = dir.resolve(name).also {
        Files.writeString(it, """
            import socket,sys,time
            s=socket.socket(socket.AF_UNIX); s.connect(sys.argv[1]); f=s.makefile()
            s.sendall(b'{"id":1,"tool":"ping","args":{}}\n'); print(f.readline().strip(), flush=True)
            time.sleep($pauseS)
            if ${if (again) "True" else "False"}:
                try: s.sendall(b'{"id":2,"tool":"ping","args":{}}\n')
                except OSError: pass
            print(repr(f.readline()), flush=True)
        """.trimIndent())
    }

    private fun spawn(vararg cmd: String) = ProcessBuilder(*cmd).redirectErrorStream(true).start()

    @Test
    fun closeHangsUpConnectionsBlockedInRead() {
        val p = spawn(python.toString(), holder("hold.py").toString(), sock.toString())
        val out = p.inputStream.bufferedReader()
        assertTrue(out.readLine().contains("pong"))
        server.close()
        assertTrue(p.waitFor(5, TimeUnit.SECONDS), "a connection must not outlive close()")
        assertEquals("''", out.readLine())
    }

    @Test
    fun anIdleListOnlyPeerIsHungUp() {
        server.close(); start(idleMs = 300)
        val c = dir.resolve("client-idle.py")
        Files.writeString(c, clientSource(call("list_tools")) + "\nprint(repr(f.readline()))")
        val p = spawn("/bin/sh", "-c", "\"$python\" \"$c\" \"$sock\"; true")
        assertTrue(p.waitFor(5, TimeUnit.SECONDS), "a list-only peer must not hold its connection open")
        assertEquals("''", p.inputStream.bufferedReader().readText().trim().lines().last())
    }

    @Test
    fun aPairedPeerIsNotTimedOutAfterItsFirstCall() {
        server.close(); start(idleMs = 300)
        val client = holder("hold-again.py", again = true, pauseS = 1.0)
        val out = run(python.toString(), client.toString(), sock.toString())
        assertTrue(out.lines().last().contains("pong"), out)
    }

    @Test
    fun connectionsOverTheCapAreClosed() {
        server.close(); start(maxConnections = 1)
        val first = spawn(python.toString(), holder("hold-cap.py", pauseS = 3.0).toString(), sock.toString())
        assertTrue(first.inputStream.bufferedReader().readLine().contains("pong"))
        val out = run(python.toString(), clientPy.toString(), sock.toString())
        assertEquals("", out, "a connection over the cap must be closed unserved")
        assertTrue(auditText().contains("over 1 open connections"), auditText())
        first.destroy()
    }

    @Test
    fun acceptBackoffDoublesToASecondAndResets() {
        val b = Server.Backoff()
        assertEquals(listOf(10L, 20L, 40L, 80L, 160L, 320L, 640L, 1000L, 1000L), List(9) { b.next() })
        b.reset()
        assertEquals(10L, b.next())
    }

    @Test
    fun bridgeLaunchedByClaudeIsServedAndPairedOnce() {
        val out1 = run(python.toString(), clientPy.toString(), sock.toString())
        val out2 = run(python.toString(), clientPy.toString(), sock.toString())
        assertTrue(out1.contains("\"result\":\"pong\""), out1)
        assertTrue(out2.contains("\"result\":\"pong\""), out2)
        assertEquals(1, asked.get(), "pairing must be asked once per claude process, not per connection")
        assertEquals(jvm, paired.single().claude)
    }

    @Test
    fun bridgeWithTheWrongParentIsRefusedEverythingButListTools() {
        // Same bridge binary, but launched by sh rather than by the "claude" (this JVM).
        val c = dir.resolve("client-sh.py"); Files.writeString(c, clientSource(call("ping"), call("list_tools")))
        val out = run("/bin/sh", "-c", "\"$python\" \"$c\" \"$sock\"; true").lines()
        assertTrue(out[0].contains("\"ok\":false") && out[0].contains("only list_tools"), out.toString())
        assertEquals("", out.getOrElse(1) { "" }, "the connection must close after a refused call")
        assertTrue(auditText().contains("not a trusted Claude Code binary"), auditText())
        assertEquals(0, asked.get(), "a list-only peer must never be shown a pairing prompt")
    }

    @Test
    fun bridgeWithTheWrongParentMayListTools() {
        val c = dir.resolve("client-doctor.py"); Files.writeString(c, clientSource(call("list_tools")))
        val out = run("/bin/sh", "-c", "\"$python\" \"$c\" \"$sock\"; true")
        assertTrue(out.contains("\"name\":\"ping\""), out)
        assertTrue(auditText().contains("list-only"), auditText())
        assertEquals(0, asked.get())
    }

    @Test
    fun listOnlyRefusesEvenAnUnknownToolBeforeLookingItUp() {
        val c = dir.resolve("client-sh2.py"); Files.writeString(c, clientSource(call("rm_rf")))
        val out = run("/bin/sh", "-c", "\"$python\" \"$c\" \"$sock\"; true")
        assertTrue(out.contains("only list_tools"), out)
    }

    @Test
    fun aDifferentBinaryIsRefused() {
        val perl = "use IO::Socket::UNIX; my \$s=IO::Socket::UNIX->new(Peer=>\$ARGV[0]) or die; " +
            "print \$s qq({\"id\":1,\"tool\":\"ping\",\"args\":{}}\\n); my \$l=<\$s>; print \$l // ''"
        val out = run("/usr/bin/perl", "-e", perl, sock.toString())
        assertEquals("", out)
        assertTrue(auditText().contains("is not the bridge binary"), auditText())
    }

    @Test
    fun declinedPairingIsRefused() {
        allowPairing = false
        val c = dir.resolve("client-two.py"); Files.writeString(c, clientSource(call("ping"), call("ping")))
        val out = run(python.toString(), c.toString(), sock.toString()).lines()
        assertTrue(out[0].contains("not paired") && out[0].contains("declined"), out.toString())
        assertEquals("", out.getOrElse(1) { "" }, "an unpaired connection must close")
        assertTrue(auditText().contains("unpaired"), auditText())
    }

    @Test
    fun aDenialNoOneSawIsNotRemembered() {
        pairAnswer = { Approval.Answer.Denied("no IDE client is connected", asked = false) }
        val out1 = run(python.toString(), clientPy.toString(), sock.toString())
        assertTrue(out1.contains("no IDE client is connected"), out1)
        pairAnswer = { Approval.Answer.Allowed }
        val out2 = run(python.toString(), clientPy.toString(), sock.toString())
        assertTrue(out2.contains("\"result\":\"pong\""), out2)
        assertEquals(2, asked.get())
    }

    @Test
    fun aFailingPairingPromptDeniesWithoutRemembering() {
        pairAnswer = { throw NoSuchMethodError("Notification.notify") }
        val out = run(python.toString(), clientPy.toString(), sock.toString())
        assertTrue(out.contains("pairing prompt failed"), out)
        pairAnswer = { Approval.Answer.Allowed }
        assertTrue(run(python.toString(), clientPy.toString(), sock.toString()).contains("pong"))
    }

    @Test
    fun aDenialIsRememberedNotReasked() {
        allowPairing = false
        run(python.toString(), clientPy.toString(), sock.toString())
        allowPairing = true
        val out = run(python.toString(), clientPy.toString(), sock.toString())
        assertTrue(out.contains("denied earlier") && !out.contains("pong"), "a denied claude must stay denied: $out")
        assertEquals(1, asked.get(), "a denial must not be re-asked")
        assertTrue(auditText().contains("denied earlier"), auditText())
    }

    @Test
    fun aUserOwnedBridgeBinaryIsRefusedWhenOwnershipIsEnforced() {
        server.close()
        val copy = dir.resolve("bridge-copy"); Files.copy(python, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        copy.toFile().setExecutable(true)
        server = Server(sock, PeerGate(copy, TrustedClaude(noConfig, me, jvm), requireRootOwned = true), tools,
            pair = { Approval.Answer.Allowed }, audit = Audit(audit))
        server.start()
        val out = run(copy.toString(), clientPy.toString(), sock.toString())
        assertEquals("", out)
        assertTrue(auditText().contains("bridge binary untrusted") && auditText().contains("not owned by root"), auditText())
    }

    @Test
    fun unknownToolIsAnErrorNotAFailure() {
        val c = dir.resolve("client-rm.py"); Files.writeString(c, clientSource(call("rm_rf")))
        val out = run(python.toString(), c.toString(), sock.toString())
        assertTrue(out.contains("\"ok\":false") && out.contains("unknown tool"), out)
    }

    @Test
    fun listToolsIsServedBeforePairingAndTheFirstCallPairs() {
        val c = dir.resolve("client-list.py"); Files.writeString(c, clientSource(call("list_tools"), call("ping")))
        val out = run(python.toString(), c.toString(), sock.toString()).lines()
        assertTrue(out[0].contains("\"name\":\"ping\"") && out[0].contains("\"additionalProperties\":false"), out[0])
        assertTrue(out[1].contains("\"result\":\"pong\""), out.toString())
        assertEquals(1, asked.get(), "listing must not ask; the first real call must")
    }

    @Test
    fun listToolsAloneNeverAsksToPair() {
        allowPairing = false
        val c = dir.resolve("client-list1.py"); Files.writeString(c, clientSource(call("list_tools")))
        val out = run(python.toString(), c.toString(), sock.toString())
        assertTrue(out.contains("\"echo\""), out)
        assertEquals(0, asked.get())
    }

    @Test
    fun anOversizeRequestGetsAnErrorNotASilentClose() {
        val c = dir.resolve("client-big.py")
        Files.writeString(c, """
            import socket,sys
            s=socket.socket(socket.AF_UNIX); s.connect(sys.argv[1])
            try: s.sendall(b'{"id":1,"tool":"ping","args":{"x":"' + b'a'*(3*1024*1024) + b'"}}\n')
            except OSError: pass
            print(s.makefile().readline().strip())
        """.trimIndent())
        val out = run(python.toString(), c.toString(), sock.toString())
        assertTrue(out.contains("request larger than"), out)
    }

    @Test
    fun argsAreSchemaCheckedBeforeTheHandlerRuns() {
        val c = dir.resolve("client-args.py")
        Files.writeString(c, clientSource(call("echo", """{"n":"7"}"""), call("echo", """{"n":7,"x":1}"""),
            call("echo", "{}"), call("echo", """{"n":7}""")))
        val out = run(python.toString(), c.toString(), sock.toString()).lines()
        assertTrue(out[0].contains("must be an integer"), out[0])
        assertTrue(out[1].contains("unknown key 'x'"), out[1])
        assertTrue(out[2].contains("args.n is required"), out[2])
        assertTrue(out[3].contains("\"result\":7"), out[3])
        assertEquals(1, echoed.get(), "the handler must only run for valid args")
    }

    @Test
    fun anyThrowableFromAToolIsThatCallsErrorAndTheSessionStaysUp() {
        val c = dir.resolve("client-throw.py")
        Files.writeString(c, clientSource(call("nsme"), call("ncdfe"), call("linkage"), call("runtime"), call("ping")))
        val out = run(python.toString(), c.toString(), sock.toString()).lines()
        assertEquals(5, out.size, "one reply per call: $out")
        for ((i, cls) in listOf("NoSuchMethodError", "NoClassDefFoundError", "LinkageError").withIndex()) {
            assertTrue(out[i].contains("\"ok\":false") && out[i].contains(cls) && out[i].contains("PY-263.test") &&
                out[i].contains("may need a rebuild"), out[i])
        }
        assertTrue(out[3].contains("state went odd") && out[3].contains("PY-263.test"), out[3])
        assertTrue(out[4].contains("\"result\":\"pong\""), "the connection must survive: $out")
        assertEquals(1, asked.get(), "the pairing must survive too")
        assertEquals(listOf(NoSuchMethodError::class, NoClassDefFoundError::class, LinkageError::class,
            IllegalStateException::class), warned.map { it::class }, "each failure is logged with its stack")
    }

    @Test
    fun aPlatformApiMismatchRefusesEveryToolCallUpFront() {
        server.close()
        start(apiMissing = listOf("com.intellij.Gone.method()"))
        val c = dir.resolve("client-api.py")
        Files.writeString(c, clientSource(call("list_tools"), call("echo", """{"n":1}""")))
        val out = run(python.toString(), c.toString(), sock.toString()).lines()
        assertTrue(out[0].contains("\"name\":\"echo\""), out[0])
        assertTrue("needs a rebuild for this IDE build (PY-263.test)" in out[1], out[1])
        assertTrue("com.intellij.Gone" in out[1], out[1])
        assertEquals(0, echoed.get())
        assertEquals(0, asked.get(), "no pairing prompt for a call that can't run")
    }

    private val sh = Path.of("/bin/sh").toRealPath()
    private val etc = dir.resolve("etc")
    private val config = etc.resolve("trusted-claude")

    /** A trusted-claude config owned by this user, so the test can stand in for root. */
    private fun configure(body: String, mode: String = "rw-r--r--") {
        Files.createDirectories(etc)
        Files.setPosixFilePermissions(etc, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        Files.writeString(config, body)
        Files.setPosixFilePermissions(config, java.nio.file.attribute.PosixFilePermissions.fromString(mode))
        Files.setPosixFilePermissions(etc, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** A user-owned copy of sh, playing a per-user claude install. */
    private fun userClaude(name: String): Path {
        val p = dir.resolve(name)
        Files.copy(sh, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        p.toFile().setExecutable(true)
        return p.toRealPath()
    }

    private fun restart(claudes: TrustedClaude) {
        server.close()
        start(claudes = claudes, requireRootOwned = true)
    }

    private fun viaShell(shell: Path, c: Path = clientPy) =
        run(shell.toString(), "-c", "\"$python\" \"$c\" \"$sock\"; true")

    @Test
    fun aListedRootOwnedClaudeIsAdmittedUnflagged() {
        configure("# system\n$sh\n")
        restart(TrustedClaude(config, me, jvm))
        assertTrue(viaShell(sh).contains("\"result\":\"pong\""), auditText())
        assertEquals(null, paired.single().claudeUserWritable)
        assertTrue("claude-binary-user-writable" !in auditText(), auditText())
        assertTrue(pairingPrompt(paired.single(), python).lines.none { USER_WRITABLE in it })
    }

    @Test
    fun aListedUserWritableClaudeIsAdmittedAuditedAndFlaggedInThePrompt() {
        val mine = userClaude("claude")
        configure("$mine\n")
        restart(TrustedClaude(config, me, jvm))
        assertTrue(viaShell(mine).contains("\"result\":\"pong\""), auditText())
        assertTrue(viaShell(mine).contains("\"result\":\"pong\""), auditText())
        val flagged = auditText().lines().filter { " claude-binary-user-writable " in it }
        assertEquals(2, flagged.size, "every admission is audited: ${auditText()}")
        assertTrue(flagged.all { "$mine" in it && "owned by uid $me" in it }, flagged.toString())
        assertTrue(USER_WRITABLE in pairingPrompt(paired.first(), python).lines, "the prompt must say so")
        assertTrue("this Claude binary can be modified by your user account" in USER_WRITABLE)
    }

    @Test
    fun aDirectoryEntryAdmitsAndFlagsABinaryDirectlyInsideButNotDeeper() {
        Files.createDirectories(dir.resolve("versions/sub"))
        val mine = userClaude("versions/2.1.0")
        val nested = userClaude("versions/sub/2.1.0")
        configure("${mine.parent}/\n")
        restart(TrustedClaude(config, me, jvm))
        assertTrue(viaShell(mine).contains("\"result\":\"pong\""), auditText())
        assertEquals(mine, paired.single().claude)
        assertTrue(auditText().lines().any { " claude-binary-user-writable " in it && "$mine" in it }, auditText())
        assertTrue(USER_WRITABLE in pairingPrompt(paired.single(), python).lines)
        assertTrue("only list_tools" in viaShell(nested), "a subdirectory is not covered")
    }

    @Test
    fun anUnlistedClaudeGetsTheRefusalAsAToolError() {
        val mine = userClaude("claude")
        val evil = userClaude("claude-evil")
        configure("$mine\n")
        restart(TrustedClaude(config, me, jvm))
        val out = viaShell(evil)
        val error = com.google.gson.JsonParser.parseString(out).asJsonObject.get("error").asString
        val head = "only list_tools is served to this connection: this bridge's parent runs $evil,"
        assertTrue(error.startsWith(head), error)
        assertTrue("as root, add the line $evil to /etc/idelink/trusted-claude" in error, error)
        assertTrue("root-owned and not group/other-writable" in error && TrustedClaude.TRADEOFF in error, error)
        assertTrue(error.lines().size <= 4, error)
        assertEquals(0, asked.get())
    }

    @Test
    fun aUserWritableDefaultClaudeIsRefused() {
        val mine = userClaude("claude")
        restart(TrustedClaude(noConfig, me, mine))
        val out = viaShell(mine)
        assertTrue("not a trusted Claude Code binary ($mine is owned by uid $me)" in out, out)
        assertEquals(0, asked.get())
    }

    @Test
    fun anUntrustedConfigTrustsNoClaude() {
        configure("$sh\n", mode = "rw-rw-r--")
        restart(TrustedClaude(config, me, sh))
        val out = viaShell(sh)
        assertTrue("$config is writable by group or others" in out && "no Claude Code binary is trusted" in out, out)
        assertEquals(0, asked.get())
    }

    @Test
    fun aClaudeReplacedWhileRunningIsRefused() {
        val mine = userClaude("claude")
        configure("$mine\n")
        restart(TrustedClaude(config, me, jvm))
        val p = ProcessBuilder(mine.toString(), "-c", "echo up; sleep 1; \"$python\" \"$clientPy\" \"$sock\"; true")
            .redirectErrorStream(true).start()
        val reader = p.inputStream.bufferedReader()
        assertEquals("up", reader.readLine(), "the claude stand-in must be running before it is replaced")
        Files.delete(mine)
        Files.copy(sh, mine)
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        val out = reader.readText()
        assertTrue("only list_tools" in out && "not a trusted Claude Code binary" in out, out)
        assertEquals(0, asked.get())
    }
}
