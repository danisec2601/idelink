@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.shared

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.serialization.Serializable

/** Backend -> frontend: answer [requestId] with the shared tabs ([terminalId] null) or the last [lines] of one. */
@Serializable
data class TerminalRequestEvent(val requestId: String, val terminalId: String? = null, val lines: Int = 0)

/** A tab the user shared: its opaque [id], its tab [name] and the shell's [title]. */
@Serializable
data class SharedTerminalDto(
    /** Opaque, random per share. */
    val id: String,
    /** The shell's title, which follows the running program or directory. */
    val title: String,
    /** The tab's name as the client shows it, without [TerminalText.SHARED_SUFFIX]; not unique. */
    val name: String = "",
)

/** Whether a read found the tab still shared. */
@Serializable
enum class TerminalStatus {
    /** The request was answered. */
    OK,

    /** The tab was unshared or closed. */
    NOT_SHARED,
}

/** [shared] and [unshared] answer a list; [text], [title], [name] and [alternateScreen] answer a read. */
@Serializable
data class TerminalAnswerDto(
    /** [TerminalStatus.NOT_SHARED] when a read names a tab that isn't shared. */
    val status: TerminalStatus = TerminalStatus.OK,
    val shared: List<SharedTerminalDto> = emptyList(),
    val unshared: Int = 0,
    val text: String? = null,
    val title: String? = null,
    val alternateScreen: Boolean = false,
    val name: String? = null,
)

/** Frontend -> backend: the answer to a [TerminalRequestEvent]. The backend accepts one answer per request id. */
@Rpc
interface TerminalApi : RemoteApi<Unit> {
    /** Resolves the backend's implementation. */
    companion object {
        /** The [TerminalApi] proxy for the current client. */
        suspend fun getInstance(): TerminalApi = RemoteApiProviderService.resolve(remoteApiDescriptor<TerminalApi>())
    }

    /** Delivers [answer] to the backend's pending [requestId] for [projectId]. */
    suspend fun answer(projectId: ProjectId, requestId: String, answer: TerminalAnswerDto)
}

/** Limits and text shared by the terminal's two halves. */
object TerminalText {
    /** The most lines one read returns. */
    const val MAX_LINES = 500

    /** The most characters one read returns, counted from the end. */
    const val MAX_CHARS = 256 * 1024

    /** Appended to a shared tab's title so the user can see it is shared. */
    const val SHARED_SUFFIX = " · shared with Claude"

    /** The last [lines] lines of [text] without trailing blank lines, and at most [MAX_CHARS] from its end. */
    fun tail(text: CharSequence, lines: Int): String {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        var start = end
        var n = 0
        val want = lines.coerceIn(1, MAX_LINES)
        while (start > 0 && end - start < MAX_CHARS) {
            if (text[start - 1] == '\n' && ++n == want) break
            start--
        }
        return text.subSequence(start, end).toString()
    }
}
