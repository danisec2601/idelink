package io.github.danisec2601.idelink.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A prompt that answers [replies] in order, from another thread, as a click on the client would arrive. */
private class FakeShow(private vararg val replies: Approval.Reply) : Approval.Show {
    val prompts = CopyOnWriteArrayList<Approval.Prompt>()
    val closed = AtomicInteger()
    override fun show(prompt: Approval.Prompt, answer: (Approval.Reply) -> Unit): Approval.Shown {
        prompts += prompt
        Thread { Thread.sleep(20); replies.forEach(answer) }.start()
        return Approval.Shown.Open { closed.incrementAndGet() }
    }
}

private val PROMPT = Approval.Prompt(null, "t", listOf("l"))

class ApprovalTest {
    @Test fun allow() {
        val show = FakeShow(Approval.Reply.ALLOW)
        assertEquals(Approval.Answer.Allowed, Approval(show, 5_000).ask(PROMPT))
        assertEquals(1, show.closed.get(), "the prompt is closed once answered")
    }

    @Test fun deny() {
        val a = Approval(FakeShow(Approval.Reply.DENY), 5_000).ask(PROMPT)
        assertEquals(Approval.Answer.Denied("the user clicked Deny", asked = true), a)
    }

    @Test fun closedWithoutAClickIsADenyThatIsNotRemembered() {
        val a = assertIs<Approval.Answer.Denied>(Approval(FakeShow(Approval.Reply.DISMISSED), 5_000).ask(PROMPT))
        assertTrue(!a.asked && a.reason.contains("closed without an answer"), a.toString())
    }

    @Test fun timeoutIsADenyAndClosesThePrompt() {
        val show = FakeShow()
        val a = assertIs<Approval.Answer.Denied>(Approval(show, 200).ask(PROMPT))
        assertTrue(a.asked && a.reason.contains("no answer"), a.toString())
        assertEquals(1, show.closed.get())
    }

    @Test fun noClientDeniesAtOnceAndSaysWhy() {
        val t0 = System.nanoTime()
        val a = Approval({ _, _ -> Approval.Shown.Nowhere("no IDE client is connected") }, 60_000).ask(PROMPT)
        assertEquals(Approval.Answer.Denied("no IDE client is connected", asked = false), a)
        assertTrue(System.nanoTime() - t0 < 5_000_000_000, "must not wait for the timeout")
    }

    @Test fun aPromptThatThrowsIsADenyNotACrash() {
        val a = Approval({ _, _ -> throw NoSuchMethodError("x") }, 5_000).ask(PROMPT)
        assertIs<Approval.Answer.Denied>(a)
        assertFalse(a.asked)
    }

    @Test fun theFirstReplyWinsSoExpiringAfterTheClickKeepsTheClick() {
        assertEquals(Approval.Answer.Allowed,
            Approval(FakeShow(Approval.Reply.ALLOW, Approval.Reply.DISMISSED), 5_000).ask(PROMPT))
        assertIs<Approval.Answer.Denied>(
            Approval(FakeShow(Approval.Reply.DISMISSED, Approval.Reply.ALLOW), 5_000).ask(PROMPT))
    }
}

class PairingPromptTest {
    @Test fun showsOnlyGateFactsMadePlain() {
        val peer = PeerGate.Peer(4242, 4141, 0, "/home/d/<html><b>x</b>‮evil\nline", Path.of("/usr/bin/claude"))
        val p = pairingPrompt(peer, Path.of("/usr/local/libexec/idelink/idelink-bridge"))
        val all = p.title + p.lines.joinToString("\n")
        assertTrue(all.contains("4242") && all.contains("4141") && all.contains("/usr/bin/claude"), all)
        assertTrue(p.lines.none { it.contains('<') || it.contains('‮') || it.contains('\n') }, all)
        assertTrue(all.contains("⟦U+202E⟧"), all)
        assertEquals(null, p.project, "pairing is asked in every open project")
        assertEquals("Allow" to "Deny", p.allow to p.deny)
    }

    private fun nul(vararg s: String) = s.joinToString("\u0000", postfix = "\u0000").toByteArray()

    @Test fun launchFlagsShowHostPortOnly() {
        val w = LaunchFlags.warnings(nul("claude", "--mcp-config", "x.json"), listOf(
            nul("PATH=/bin", "ANTHROPIC_BASE_URL=https://user:pw@evil.example:8443/v1?k=sekrit"),
            nul("https_proxy=10.0.0.9:3128", "ANTHROPIC_BASE_URL=https://other.example",
                "CLAUDE_CODE_API_BASE_URL=<b>"),
        ))
        val all = w.joinToString("\n")
        assertTrue(w.size == 4 && w.all { it.startsWith("⚠") }, all)
        assertTrue("--mcp-config" in w[0], all)
        assertTrue("ANTHROPIC_BASE_URL is set (evil.example:8443)" in all, all)
        assertTrue("https_proxy is set (10.0.0.9:3128)" in all, all)
        assertTrue("CLAUDE_CODE_API_BASE_URL is set (not a URL)" in all, all)
        assertTrue(listOf("user", "pw", "/v1", "sekrit", "other.example", "<b>").none { it in all }, all)
        assertTrue(LaunchFlags.warnings(nul("claude", "--mcp-config=a"), emptyList()).single().contains("--mcp-config"))
        assertTrue(LaunchFlags.warnings(nul("claude", "-p"), listOf(nul("HOME=/h", "NO_PROXY=x"))).isEmpty())
    }

    @Test fun launchFlagsAreReadFromTheRealProcessAndShownInThePrompt() {
        val pb = ProcessBuilder("sh", "-c", "sleep 30", "--mcp-config", "x")
        pb.environment()["ANTHROPIC_BASE_URL"] = "http://redirect.example:9999/path"
        val proc = pb.start()
        try {
            val w = LaunchFlags.read(proc.pid().toInt(), proc.pid().toInt())
            val peer = PeerGate.Peer(1, 2, 0, "/", Path.of("/usr/bin/claude"), launchWarnings = w)
            val all = pairingPrompt(peer, Path.of("/b")).lines.joinToString("\n")
            assertTrue("--mcp-config" in all && "ANTHROPIC_BASE_URL is set (redirect.example:9999)" in all, all)
            assertFalse("/path" in all, all)
        } finally { proc.destroy() }
    }
}

class OutsideGateTest {
    private val base = Path.of(System.getProperty("user.home"), "dev/tmp/idelink-test/outside")
    private val proj = base.resolve("proj")
    private val other = base.resolve("other")
    private lateinit var roots: List<PathScope.Root>

    @BeforeTest
    fun up() {
        base.toFile().deleteRecursively()
        Files.createDirectories(proj); Files.createDirectories(other)
        Files.writeString(proj.resolve("in.txt"), "in\n")
        Files.writeString(other.resolve("target.txt"), "t\n"); Files.writeString(other.resolve("b.txt"), "b\n")
        Files.writeString(other.resolve(".env"), "K=v\n"); Files.writeString(other.resolve("notes.md"), "# n\n")
        Files.createDirectories(other.resolve("dir"))
        Files.createSymbolicLink(proj.resolve("link-out"), other.resolve("target.txt"))
        Files.createSymbolicLink(proj.resolve("creds.pem"), other.resolve("b.txt"))
        Files.createSymbolicLink(proj.resolve("innocent.txt"), other.resolve(".env"))
        roots = listOf(PathScope.realRoot("proj", proj.toString())!!)
    }

    /** What a path tool does: locate, then confirm before touching the file. */
    private fun access(gate: OutsideGate, input: String): PathScope.Resolved =
        gate.locate(roots, input, null).also { gate.confirm("open", listOf(it)) }

    private fun gate(show: Approval.Show, timeout: Long = 5_000) = OutsideGate(Approval(show, timeout))

    @Test fun insideNeedsNoPrompt() {
        val show = FakeShow(Approval.Reply.DENY)
        val r = access(gate(show), "in.txt")
        assertFalse(r.outside); assertEquals("in.txt", r.rel)
        assertTrue(show.prompts.isEmpty())
    }

    @Test fun outsideAllowedOnce() {
        val show = FakeShow(Approval.Reply.ALLOW)
        val g = gate(show)
        val r = access(g, other.resolve("target.txt").toString())
        assertTrue(r.outside); assertEquals(other.resolve("target.txt").toRealPath(), r.real)
        assertEquals(1, show.prompts.size)
        assertEquals("proj", show.prompts.single().project)
        assertEquals("Allow once", show.prompts.single().allow)
        assertTrue(show.prompts.single().lines.any { it.contains(r.real.toString()) }, show.prompts.toString())
        assertTrue(PathScope.recheck(r))
        access(g, r.real.toString())
        assertEquals(2, show.prompts.size, "Allow once must not carry over to the next call")
    }

    @Test fun onlyAnAnsweredAllowOnceCountsAsAllowed() {
        val show = FakeShow(Approval.Reply.ALLOW)
        val g = gate(show)
        val inside = listOf(g.locate(roots, "in.txt", null))
        assertFalse(g.confirm("open", inside), "an in-project file must not count as allowed")
        assertTrue(show.prompts.isEmpty())
        assertTrue(g.confirm("open", listOf(g.locate(roots, other.resolve("target.txt").toString(), null))))
        assertTrue(g.confirm("add to a review tour", inside + g.locate(roots, "link-out", null)))
        assertEquals(2, show.prompts.size)
        assertTrue(show.prompts.all { p -> p.lines.any { it.contains("shows it right away") } },
            show.prompts.toString())
    }

    @Test fun allowOnceOnATourSaysItStartsTheWholeTour() {
        val show = FakeShow(Approval.Reply.ALLOW)
        val g = gate(show)
        val stops = listOf("in.txt", other.resolve("target.txt").toString()).map { g.locate(roots, it, null) }
        assertTrue(g.confirm("add to a review tour", stops, tourStops = stops.size))
        val text = show.prompts.single().lines.joinToString("\n")
        assertTrue("Allow once also starts the whole tour (2 stops, including any inside the project)" in text, text)
        assertFalse("covers this one request" in text, text)
    }

    @Test fun outsideDenied() {
        val e = assertFailsWith<BadArgs> { access(gate(FakeShow(Approval.Reply.DENY)), "../other/target.txt") }
        assertTrue(e.message!!.contains("not allowed"), e.message)
    }

    @Test fun outsideTimesOut() {
        val target = other.resolve("target.txt").toString()
        val e = assertFailsWith<BadArgs> { access(gate(FakeShow(), timeout = 200), target) }
        assertTrue(e.message!!.contains("no answer"), e.message)
    }

    @Test fun anInsideSymlinkToOutsidePromptsWithTheRealTarget() {
        val show = FakeShow(Approval.Reply.ALLOW)
        val r = access(gate(show), "link-out")
        assertEquals(1, show.prompts.size)
        val target = other.resolve("target.txt").toRealPath().toString()
        assertEquals(target, r.real.toString())
        val text = show.prompts.single().lines.joinToString("\n")
        assertTrue(text.contains(target) && !text.contains("link-out"), text)
    }

    @Test fun anOutsideFilesContentIsNotJudgedBeforeTheUserAnswers() {
        val show = FakeShow(Approval.Reply.DENY)
        val f = other.resolve("conf.yaml")
        Files.writeString(f, "# yaml-language-server: \$schema=https://x.example/s\n")
        val e = assertFailsWith<BadArgs> { access(gate(show), f.toString()) }
        assertTrue(e.message!!.contains("not allowed"), e.message)
        assertEquals(1, show.prompts.size)
    }

    @Test fun secretNamesAreRefusedWithoutAPrompt() {
        val show = FakeShow(Approval.Reply.ALLOW)
        for (p in listOf(other.resolve(".env").toString(), "creds.pem", "innocent.txt")) {
            val e = assertFailsWith<BadArgs>(p) { access(gate(show), p) }
            assertTrue(e.message!!.contains("secret-bearing"), e.message)
        }
        assertTrue(show.prompts.isEmpty())
    }

    @Test fun nonexistentNonRegularAndPreviewTypesAreRefusedWithoutAPrompt() {
        val show = FakeShow(Approval.Reply.ALLOW)
        for (p in listOf(other.resolve("nope.txt").toString(), "../other/nope.txt", other.resolve("dir").toString(),
            other.resolve("notes.md").toString())) {
            assertFailsWith<BadArgs>(p) { access(gate(show), p) }
        }
        assertTrue(show.prompts.isEmpty())
    }

    @Test fun aTourAsksOnceForEveryOutsideStop() {
        val show = FakeShow(Approval.Reply.ALLOW)
        val g = gate(show)
        val rs = listOf("in.txt", other.resolve("target.txt").toString(), "link-out", other.resolve("b.txt").toString())
            .map { g.locate(roots, it, "proj") }
        g.confirm("add to a review tour", rs)
        assertEquals(1, show.prompts.size)
        val lines = show.prompts.single().lines
        assertEquals(2, lines.count { it.startsWith("• ") }, lines.toString())
    }

    @Test fun tooManyOutsideStopsAreRefusedWithoutAPrompt() {
        val show = FakeShow(Approval.Reply.ALLOW)
        val many = (0..OutsideGate.MAX_LISTED).map {
            val f = other.resolve("f$it.txt").also { f -> Files.writeString(f, "x") }
            PathScope.Resolved("proj", proj, f, f.toString(), true)
        }
        assertFailsWith<BadArgs> { gate(show).confirm("add to a review tour", many) }
        assertTrue(show.prompts.isEmpty())
    }
}
