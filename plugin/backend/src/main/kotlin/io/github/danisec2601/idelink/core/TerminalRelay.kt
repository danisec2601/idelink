package io.github.danisec2601.idelink.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.danisec2601.idelink.shared.Plain
import io.github.danisec2601.idelink.shared.TerminalAnswerDto
import io.github.danisec2601.idelink.shared.TerminalRequestEvent
import io.github.danisec2601.idelink.shared.TerminalStatus
import io.github.danisec2601.idelink.shared.TerminalText
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Asks a project's controller clients for shared terminal output. Answers are untrusted text: the checks bound
 * their shape and size, not their truth. Projects are addressed by key; a label only goes into results.
 */
internal class TerminalRelay(
    private val transport: Transport,
    private val waitMs: Long = 10_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    interface Transport {
        /** The ClientIds a [send] to [project] reaches: its controller sessions. */
        fun clients(project: String): Set<String>
        fun send(project: String, event: TerminalRequestEvent)
    }

    private class Pending(val project: String, val asked: Set<String>, val deadline: Long) {
        val result = CompletableFuture<TerminalAnswerDto>()
        @Volatile var refused: String? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    fun list(project: String, label: String = project): JsonObject {
        val a = ask(project, TerminalRequestEvent(newRequestId()))
        val terminals = JsonArray()
        for (t in a.shared.take(MAX_TABS)) {
            if (!ID.matches(t.id)) continue
            terminals.add(JsonObject().apply {
                addProperty("id", t.id)
                name(t.name)?.let { addProperty("name", it) }
                addProperty("shell_title", title(t.title))
            })
        }
        return JsonObject().apply {
            addProperty("project", label)
            add("terminals", terminals)
            addProperty("unshared", a.unshared.coerceIn(0, MAX_UNSHARED))
        }
    }

    fun read(project: String, terminalId: String, lines: Int, label: String = project): JsonObject {
        if (!ID.matches(terminalId)) throw BadArgs("id must be a terminal id from list_terminals")
        val n = lines.coerceIn(1, TerminalText.MAX_LINES)
        val a = ask(project, TerminalRequestEvent(newRequestId(), terminalId, n))
        val out = JsonObject().apply { addProperty("project", label); addProperty("id", terminalId) }
        val raw = a.text
        if (a.status != TerminalStatus.OK || raw == null) {
            return out.apply {
                addProperty("not_shared", "this terminal is not shared (unshared or closed); the user shares a tab " +
                    "from its context menu: Share with Claude")
            }
        }
        val tail = TerminalText.tail(raw, n)
        val s = Scrub.scrub(tail)
        val text = Plain.text(s.text, MAX_TEXT, multiline = true)
        a.name?.let(::name)?.let { out.addProperty("name", it) }
        a.title?.let { out.addProperty("shell_title", title(it)) }
        out.addProperty("lines", if (text.isEmpty()) 0 else text.count { it == '\n' } + 1)
        if (a.alternateScreen) out.addProperty("alternate_screen", true)
        if (tail.length >= TerminalText.MAX_CHARS) out.addProperty("truncated_to_chars", TerminalText.MAX_CHARS)
        out.addProperty("text", text)
        if (s.redactions.isNotEmpty()) {
            out.add("redacted", JsonObject().apply { s.redactions.forEach { (k, v) -> addProperty(k, v) } })
        }
        return out
    }

    /**
     * Accepts [answer] once for an outstanding [requestId]; returns why it was refused, or null. A refused answer
     * leaves the request waiting, so a client that wasn't asked can't spoil the real one.
     */
    fun answer(project: String, requestId: String, caller: String?, answer: TerminalAnswerDto): String? {
        val p = pending[requestId] ?: return refuse(UNKNOWN)
        val matched = ControllerCheck.accepts(caller, p.asked)
        log("idelink: terminal answer matched=$matched caller=$caller asked=${p.asked.size}")
        val why = when {
            now() > p.deadline -> "the request expired"
            p.project != project -> "the answer came from another project"
            !matched -> "the answer came from a client that wasn't asked"
            !pending.remove(requestId, p) -> UNKNOWN
            else -> null
        }
        if (why == null) p.result.complete(answer) else p.refused = refuse(why)
        return why
    }

    private fun refuse(why: String): String = why.also { log("idelink: terminal answer refused: $it") }

    @Suppress("ThrowsCount") // one refusal per reason, each with its own message
    private fun ask(project: String, event: TerminalRequestEvent): TerminalAnswerDto {
        val asked = transport.clients(project)
        if (asked.isEmpty()) throw BadArgs(NO_CLIENT)
        val p = Pending(project, asked, now() + waitMs)
        pending[event.requestId] = p
        try {
            transport.send(project, event)
            return p.result.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            p.refused?.let { throw BadArgs("the IDE client's answer was refused: $it", e) }
            throw BadArgs("no answer from the IDE client within ${waitMs.milliseconds.inWholeSeconds} s " +
                "(idelink frontend and Terminal plugin installed there?)", e)
        } finally {
            pending.remove(event.requestId)
        }
    }

    private fun title(t: String) = Plain.text(Scrub.scrub(t).text, max = 120)

    private fun name(n: String) = title(n).ifEmpty { null }

    companion object {
        const val NO_CLIENT = "no IDE client is connected to this project"
        private const val UNKNOWN = "unknown or already answered request"
        const val MAX_TABS = 50
        private const val MAX_UNSHARED = 10_000
        private const val REQUEST_ID_BYTES = 16
        private const val MAX_TEXT = 2 * TerminalText.MAX_CHARS
        private val ID = Regex("[0-9a-f]{16}")
        private val rng = SecureRandom()

        fun newRequestId(): String =
            ByteArray(REQUEST_ID_BYTES).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
