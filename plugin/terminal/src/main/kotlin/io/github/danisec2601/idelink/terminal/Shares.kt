package io.github.danisec2601.idelink.terminal

import io.github.danisec2601.idelink.shared.SharedTerminalDto
import io.github.danisec2601.idelink.shared.TerminalAnswerDto
import io.github.danisec2601.idelink.shared.TerminalStatus
import io.github.danisec2601.idelink.shared.TerminalText
import java.security.SecureRandom
import java.util.Collections
import java.util.IdentityHashMap

private val rng = SecureRandom()
private const val ID_BYTES = 8

internal fun randomTerminalId(): String =
    ByteArray(ID_BYTES).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

/** The tabs the user shared, each under an opaque id. Tabs are held by identity; a closed tab counts as unshared. */
internal class ShareRegistry<T : Any>(private val newId: () -> String = ::randomTerminalId) {
    private val ids = IdentityHashMap<T, String>()

    @Synchronized fun share(tab: T): String = ids.getOrPut(tab, newId)

    @Synchronized fun unshare(tab: T): Boolean = ids.remove(tab) != null

    @Synchronized fun idOf(tab: T): String? = ids[tab]

    /** The tab shared as [id], if it is still among [open]; closed tabs are dropped first. */
    @Synchronized fun resolve(id: String, open: List<T>): T? {
        prune(open)
        return ids.entries.firstOrNull { it.value == id }?.key
    }

    @Synchronized fun prune(open: List<T>) {
        val live = Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(open) }
        ids.keys.retainAll(live)
    }
}

/** The tab name the user sees, from [displayName] without the marker idelink appends to shared tabs. */
internal fun tabName(displayName: String?): String = displayName.orEmpty().removeSuffix(TerminalText.SHARED_SUFFIX)

/** What a read of one tab produced; [text] is the tail of its active screen. */
internal data class TabRead(val text: String, val title: String, val alternateScreen: Boolean, val name: String = "")

/** Builds answers to backend requests. [open] is called again at answer time, so a tab closed mid-read is refused. */
internal object TerminalAnswers {
    fun <T : Any> list(
        shares: ShareRegistry<T>, open: List<T>, title: (T) -> String, name: (T) -> String,
    ): TerminalAnswerDto {
        shares.prune(open)
        val shared = open.mapNotNull { t -> shares.idOf(t)?.let { SharedTerminalDto(it, title(t), name(t)) } }
        return TerminalAnswerDto(shared = shared, unshared = open.size - shared.size)
    }

    fun <T : Any> read(shares: ShareRegistry<T>, open: () -> List<T>, id: String, lines: Int,
                       read: (T, Int) -> TabRead): TerminalAnswerDto {
        val tab = shares.resolve(id, open()) ?: return NOT_SHARED
        val n = lines.coerceIn(1, TerminalText.MAX_LINES)
        val r = read(tab, n)
        return if (shares.resolve(id, open()) !== tab) NOT_SHARED
        else TerminalAnswerDto(
            text = TerminalText.tail(r.text, n), title = r.title, alternateScreen = r.alternateScreen, name = r.name,
        )
    }

    private val NOT_SHARED = TerminalAnswerDto(status = TerminalStatus.NOT_SHARED)
}
