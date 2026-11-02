package io.github.danisec2601.idelink.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.danisec2601.idelink.shared.Plain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** The plugin end of the bridge's line protocol, one JSON object per line (docs/architecture.md). */
internal class Server(
    private val socketPath: Path,
    private val gate: PeerGate,
    tools: List<ToolDef>,
    /** Asked once per Claude process (pid + start time); the answer holds for the rest of this IDE session. */
    private val pair: (PeerGate.Peer) -> Approval.Answer,
    private val audit: Audit,
    private val ideBuild: String = "unknown",
    /** Platform APIs the startup check found missing; non-empty means every tool call is refused up front. */
    private val apiMissing: List<String> = emptyList(),
    private val warn: (String, Throwable) -> Unit = { _, _ -> },
    private val maxConnections: Int = MAX_CONNECTIONS,
    /** How long a connection may sit idle before its first tool call (list-only peers: always). */
    private val idleMs: Long = IDLE_MS,
) : AutoCloseable {
    private val tools: Map<String, ToolDef> = tools.associateBy { it.name }.also {
        require(it.size == tools.size && LIST_TOOLS !in it) { "duplicate or reserved tool name" }
    }
    private val approved = ConcurrentHashMap.newKeySet<String>()
    private val denied = ConcurrentHashMap.newKeySet<String>()
    private val asking = Any()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "idelink-conn").apply { isDaemon = true } }
    @Volatile private var listenFd = -1
    @Volatile private var closed = false

    private val conns = Connections(maxConnections)

    fun start() {
        val dir = socketPath.parent
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        // A leftover socket from a crashed IDE: the dir is 0700 and ours, so nothing else could have bound it.
        Files.deleteIfExists(socketPath)
        listenFd = Sys.listenUnix(socketPath.toString())
        Thread({ acceptLoop() }, "idelink-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        val backoff = Backoff()
        while (!closed) {
            val fd = LibC.accept4(listenFd, Sys.SOCK_CLOEXEC)
            if (fd < 0) {
                if (closed) return
                Thread.sleep(backoff.next())
                continue
            }
            backoff.reset()
            if (!conns.admit(fd)) {
                if (!closed) audit.line("deny", 0, 0, "-", "over $maxConnections open connections")
                continue
            }
            try {
                pool.execute { handle(fd) }
            } catch (_: RejectedExecutionException) {
                conns.release(fd)
            }
        }
    }

    private fun handle(fd: Int) {
        try {
            Sys.setReadTimeout(fd, idleMs)
            when (val v = gate.check(fd)) {
                is PeerGate.Verdict.Deny -> audit.line("deny", 0, 0, "-", v.reason)
                is PeerGate.Verdict.Allow -> {
                    v.peer.claudeUserWritable?.let {
                        audit.line("claude-binary-user-writable", v.peer.pid, v.peer.ppid, "-", "${v.peer.claude}: $it")
                    }
                    serve(fd, v.peer, listOnly = null)
                }
                is PeerGate.Verdict.ListOnly -> {
                    audit.line("list-only", v.peer.pid, v.peer.ppid, "-", v.reason)
                    serve(fd, v.peer, listOnly = v.reason)
                }
            }
        } finally {
            conns.release(fd)
        }
    }

    /**
     * Null once [peer]'s claude is paired, else why not. A denial the user saw is remembered, so a refused claude
     * can't re-ask until they give in; settled answers skip the lock, so an open prompt holds up only the unpaired.
     */
    @Suppress("ReturnCount") // double-checked: a settled answer returns before the lock and again inside it
    private fun unpaired(peer: PeerGate.Peer): String? {
        val key = "${peer.ppid}:${peer.parentStart}"
        if (key in approved) return null
        if (key in denied) return deniedEarlier(peer)
        synchronized(asking) {
            if (key in approved) return null
            if (key in denied) return deniedEarlier(peer)
            val answer = runCatching { pair(peer) }.getOrElse { t ->
                warn("idelink: pairing prompt failed", t)
                Approval.Answer.Denied("the pairing prompt failed (${t.javaClass.name})", asked = false)
            }
            if (answer is Approval.Answer.Denied) {
                if (answer.asked) denied += key
                audit.line("unpaired", peer.pid, peer.ppid, "-", "declined: ${answer.reason}")
                return answer.reason
            }
            approved += key
            audit.line("paired", peer.pid, peer.ppid, "-", peer.parentCwd)
            return null
        }
    }

    private fun deniedEarlier(peer: PeerGate.Peer): String {
        audit.line("unpaired", peer.pid, peer.ppid, "-", "denied earlier")
        return "pairing was denied earlier"
    }

    /** A [listOnly] peer gets list_tools and nothing else, and is never asked to pair. */
    private fun serve(fd: Int, peer: PeerGate.Peer, listOnly: String?) {
        val reader = LineReader(fd)
        var called = false
        while (!closed) {
            val line = try { reader.next() ?: return } catch (e: TooLong) {
                audit.line("call", peer.pid, peer.ppid, "-", "request over ${MAX_LINE / 1024} KiB")
                write(fd, error(null, "request larger than ${MAX_LINE / 1024} KiB; connection closed")); return
            }
            val req = try { JsonParser.parseString(line).asJsonObject } catch (e: Exception) {
                write(fd, error(null, "bad request")); continue
            }
            val id = req.get("id")?.takeIf { it.isJsonPrimitive }
            val name = req.get("tool")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            if (name == LIST_TOOLS) {
                // Static, public descriptions of what the plugin does; served before pairing (gate still applies).
                audit.line("list", peer.pid, peer.ppid, name, "ok")
                write(fd, ok(id, JsonArray().apply { tools.values.forEach { add(it.spec()) } }))
                continue
            }
            if (listOnly != null) {
                audit.line("call", peer.pid, peer.ppid, name.take(64), "refused: list-only peer")
                write(fd, error(id, "only $LIST_TOOLS is served to this connection: $listOnly")); return
            }
            val tool = tools[name]
            if (tool == null) {
                audit.line("call", peer.pid, peer.ppid, name.take(64), "unknown tool")
                write(fd, error(id, "unknown tool")); continue
            }
            if (apiMissing.isNotEmpty()) {
                audit.line("call", peer.pid, peer.ppid, name, "refused: platform API mismatch")
                val more = if (apiMissing.size > MISSING_SHOWN) "; …" else ""
                write(fd, error(id, "idelink needs a rebuild for this IDE build ($ideBuild): missing " +
                    apiMissing.take(MISSING_SHOWN).joinToString("; ") + more))
                continue
            }
            unpaired(peer)?.let { write(fd, error(id, "not paired with this IDE: $it")); return }
            if (!called) { called = true; Sys.setReadTimeout(fd, 0) }
            val resp = call(id, tool, req.get("args") ?: JsonObject())
            audit.line("call", peer.pid, peer.ppid, name,
                if (resp.get("ok").asBoolean) "ok" else resp.get("error").asString)
            write(fd, resp)
        }
    }

    /** Every failure, down to a LinkageError from a changed platform API, becomes this call's error. */
    @Suppress("TooGenericExceptionCaught") // catching everything is the point: see above
    private fun call(id: JsonElement?, tool: ToolDef, args: JsonElement): JsonObject = try {
        tool.schema.check(args, "args")
        ok(id, tool.handler(args.asJsonObject))
    } catch (e: BadArgs) {
        error(id, e.message ?: "bad arguments")
    } catch (e: Exception) {
        warn("idelink: ${tool.name} failed", e)
        val detail = e.message?.take(MAX_DETAIL) ?: "no message"
        error(id, "${tool.name} failed: $detail (${e.javaClass.name}, IDE build $ideBuild)")
    } catch (t: Throwable) {
        warn("idelink: ${tool.name} failed on IDE build $ideBuild", t)
        error(id, "${tool.name} failed with ${t.javaClass.name} on IDE build $ideBuild; idelink may need a rebuild " +
            "for this build" + (t.message?.let { " (${it.take(MAX_CAUSE)})" } ?: ""))
    }

    private fun ok(id: JsonElement?, result: JsonElement) =
        JsonObject().apply { add("id", id); addProperty("ok", true); add("result", result) }

    private fun error(id: JsonElement?, msg: String) =
        JsonObject().apply { add("id", id); addProperty("ok", false); addProperty("error", msg) }

    private fun write(fd: Int, obj: JsonObject) {
        val b = (obj.toString() + "\n").toByteArray()
        var off = 0
        while (off < b.size) {
            val chunk = b.copyOfRange(off, b.size)
            val n = LibC.write(fd, chunk, chunk.size.toLong())
            if (n <= 0) return
            off += n.toInt()
        }
    }

    /** Stops accepting and hangs up every open connection, which ends its blocked read. */
    override fun close() {
        closed = true
        if (!conns.hangUpAll()) return
        if (listenFd >= 0) { LibC.shutdown(listenFd, Sys.SHUT_RDWR); LibC.close(listenFd) }
        runCatching { Files.deleteIfExists(socketPath) }
        pool.shutdownNow()
    }

    private class TooLong : Exception()

    /** Open connection fds. An fd leaves the set before it is closed, so [hangUpAll] never hits a reused number. */
    private class Connections(private val max: Int) {
        private val fds = HashSet<Int>()
        private var closed = false

        /** Tracks [fd], or closes it when shut down or full. */
        @Synchronized
        fun admit(fd: Int): Boolean = (!closed && fds.size < max).also { if (it) fds += fd else LibC.close(fd) }

        @Synchronized
        fun release(fd: Int) { fds -= fd; LibC.close(fd) }

        /** Shuts down every tracked fd, ending its blocked read; false if already done. */
        @Synchronized
        fun hangUpAll(): Boolean {
            if (closed) return false
            closed = true
            fds.forEach { LibC.shutdown(it, Sys.SHUT_RDWR) }
            return true
        }
    }

    /** Delays between failed accepts (e.g. EMFILE): doubling from 10 ms to 1 s, back to 10 ms after a success. */
    internal class Backoff {
        private var ms = MIN_BACKOFF_MS
        fun next(): Long = ms.also { ms = minOf(ms * 2, MAX_BACKOFF_MS) }
        fun reset() { ms = MIN_BACKOFF_MS }
    }

    /** Newline-delimited reads from a raw fd, capped so a peer can't grow our memory without bound. */
    private class LineReader(private val fd: Int) {
        private var buf = ByteArray(65_536)
        private var len = 0
        private var scanned = 0
        private val chunk = ByteArray(65_536)
        fun next(): String? {
            while (true) {
                for (i in scanned until len) if (buf[i] == '\n'.code.toByte()) {
                    val line = String(buf, 0, i)
                    System.arraycopy(buf, i + 1, buf, 0, len - i - 1)
                    len -= i + 1; scanned = 0
                    return line
                }
                scanned = len
                if (len > MAX_LINE) throw TooLong()
                val n = LibC.read(fd, chunk, chunk.size.toLong()).toInt()
                if (n <= 0) return null
                if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
                System.arraycopy(chunk, 0, buf, len, n); len += n
            }
        }
    }

    companion object {
        const val MAX_LINE = 2 * 1024 * 1024
        const val LIST_TOOLS = "list_tools"
        const val MAX_CONNECTIONS = 32
        const val IDLE_MS = 30_000L
        private const val MIN_BACKOFF_MS = 10L
        private const val MAX_BACKOFF_MS = 1_000L
        private const val MISSING_SHOWN = 3
        private const val MAX_DETAIL = 300
        private const val MAX_CAUSE = 200
    }
}

/** Append-only audit trail of every connection decision and tool call (never tool output). */
internal class Audit(private val file: Path) {
    @Synchronized
    fun line(event: String, pid: Int, ppid: Int, tool: String, detail: String) {
        val parent = file.parent
        if (!Files.exists(parent)) Files.createDirectories(parent,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        if (!Files.exists(file)) Files.createFile(file,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(file, "${Instant.now()} $event pid=$pid ppid=$ppid tool=$tool ${Plain.text(detail, 500)}\n",
            java.nio.file.StandardOpenOption.APPEND)
    }
}
