package io.github.danisec2601.idelink.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** Asks the user a yes/no question through [show], waiting up to [timeoutMs]; anything but an Allow click denies. */
internal class Approval(private val show: Show, private val timeoutMs: Long = TIMEOUT_MS) {
    enum class Reply { ALLOW, DENY, DISMISSED }

    /** [project] null means every open project. Lines are plain text; the shower escapes them for its UI. */
    data class Prompt(val project: String?, val title: String, val lines: List<String>, val allow: String = "Allow",
                      val deny: String = "Deny")

    sealed class Shown {
        class Open(val close: () -> Unit) : Shown()
        class Nowhere(val reason: String) : Shown()
    }

    /** Displays a prompt and reports the first click or dismissal through `answer`; later calls are ignored. */
    fun interface Show {
        fun show(prompt: Prompt, answer: (Reply) -> Unit): Shown
    }

    sealed class Answer {
        data object Allowed : Answer()

        /** [asked] is false when no prompt reached the user, so the denial says nothing about their choice. */
        data class Denied(val reason: String, val asked: Boolean) : Answer()
    }

    /** Shows [prompt] and blocks until it is answered, dismissed, or times out. */
    fun ask(prompt: Prompt): Answer {
        val reply = CompletableFuture<Reply?>()
        val shown = runCatching { show.show(prompt) { reply.complete(it) } }
            .getOrElse { Shown.Nowhere("the prompt could not be shown (${it.javaClass.name})") }
        if (shown is Shown.Nowhere) return Answer.Denied(shown.reason, asked = false)
        val r = try {
            reply.completeOnTimeout(null, timeoutMs, TimeUnit.MILLISECONDS).get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); reply.complete(null); reply.getNow(null)
        }
        runCatching { (shown as Shown.Open).close() }
        return when (r) {
            Reply.ALLOW -> Answer.Allowed
            Reply.DENY -> Answer.Denied("the user clicked ${prompt.deny}", asked = true)
            // Clearing the balloon from the Notifications panel expires it unanswered: neither Allow nor Deny.
            Reply.DISMISSED -> Answer.Denied("the prompt closed without an answer", asked = false)
            null -> Answer.Denied("no answer within ${timeoutMs.milliseconds.inWholeSeconds} s", asked = true)
        }
    }

    companion object {
        const val TIMEOUT_MS = 2 * 60 * 1000L
    }
}
