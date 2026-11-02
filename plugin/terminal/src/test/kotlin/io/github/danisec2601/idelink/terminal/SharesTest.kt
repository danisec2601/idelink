package io.github.danisec2601.idelink.terminal

import io.github.danisec2601.idelink.shared.TerminalStatus
import io.github.danisec2601.idelink.shared.TerminalText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharesTest {
    /** Equal by value on purpose: the registry must still tell two tabs apart. */
    private data class Tab(val name: String)

    private val a = Tab("zsh")
    private val b = Tab("zsh")
    private var open = listOf(a, b)
    private val shares = ShareRegistry<Tab>()

    private fun read(id: String, lines: Int = 5, onRead: () -> Unit = {}) =
        TerminalAnswers.read(shares, { open }, id, lines) { t, n ->
            onRead()
            lastN = n
            TabRead("${t.name}\nl1\nl2\n\n", t.name, false)
        }

    private var lastN = 0

    @Test fun nothingIsSharedByDefault() {
        val l = TerminalAnswers.list(shares, open, { it.name }) { "" }
        assertTrue(l.shared.isEmpty())
        assertEquals(2, l.unshared)
    }

    @Test fun sharingIsPerTabByIdentity() {
        val id = shares.share(a)
        assertNull(shares.idOf(b))
        assertEquals(id, shares.share(a))
        val l = TerminalAnswers.list(shares, open, { it.name }) { "" }
        assertEquals(listOf(id), l.shared.map { it.id })
        assertEquals(1, l.unshared)
    }

    @Test fun idsAreOpaqueAndFreshPerShare() {
        val first = shares.share(a)
        shares.unshare(a)
        val second = shares.share(a)
        assertNotEquals(first, second)
        assertTrue(Regex("[0-9a-f]{16}").matches(second), second)
    }

    @Test fun aSharedTabIsRead() {
        val id = shares.share(a)
        val r = read(id)
        assertEquals(TerminalStatus.OK, r.status)
        assertEquals("zsh\nl1\nl2", r.text)
    }

    @Test fun anUnsharedOrUnknownTabIsNotRead() {
        val id = shares.share(a)
        shares.unshare(a)
        var reads = 0
        assertEquals(TerminalStatus.NOT_SHARED, read(id) { reads++ }.status)
        assertEquals(TerminalStatus.NOT_SHARED, read("0000000000000000") { reads++ }.status)
        assertEquals(0, reads)
    }

    @Test fun aClosedTabIsNotSharedAndDropped() {
        val id = shares.share(a)
        open = listOf(b)
        assertEquals(TerminalStatus.NOT_SHARED, read(id).status)
        open = listOf(a, b)
        assertEquals(TerminalStatus.NOT_SHARED, read(id).status)
    }

    @Test fun unsharingDuringTheReadWithholdsTheAnswer() {
        val id = shares.share(a)
        val r = read(id) { shares.unshare(a) }
        assertEquals(TerminalStatus.NOT_SHARED, r.status)
        assertNull(r.text)
    }

    @Test fun closingDuringTheReadWithholdsTheAnswer() {
        val id = shares.share(a)
        val r = read(id) { open = listOf(b) }
        assertEquals(TerminalStatus.NOT_SHARED, r.status)
    }

    @Test fun reshareDuringTheReadWithholdsTheAnswer() {
        val id = shares.share(a)
        val r = read(id) { shares.unshare(a); shares.share(a) }
        assertEquals(TerminalStatus.NOT_SHARED, r.status)
    }

    @Test fun linesAreCapped() {
        read(shares.share(a), lines = 100_000)
        assertEquals(TerminalText.MAX_LINES, lastN)
        read(shares.share(a), lines = -3)
        assertEquals(1, lastN)
    }

    @Test fun theListNamesOnlySharedTabs() {
        val other = Tab("secret-project-shell")
        open = listOf(a, other)
        val id = shares.share(a)
        val l = TerminalAnswers.list(shares, open, { it.name }) { "" }
        assertEquals(1, l.shared.size)
        assertSame(id, l.shared.single().id)
        assertTrue(l.shared.none { it.title == other.name })
    }

    @Test fun theListCarriesNameAndTitleAndDuplicateNamesKeepDistinctIds() {
        val ia = shares.share(a)
        val ib = shares.share(b)
        val l = TerminalAnswers.list(shares, open, { "~/x" }) { "Remote (2)" }
        assertEquals(listOf(ia, ib), l.shared.map { it.id })
        assertEquals(listOf("Remote (2)", "Remote (2)"), l.shared.map { it.name })
        assertEquals(listOf("~/x", "~/x"), l.shared.map { it.title })
    }

    @Test fun aReadCarriesTheTabName() {
        val id = shares.share(a)
        val r = TerminalAnswers.read(shares, { open }, id, 1) { _, _ -> TabRead("x", "~/x", false, "Local") }
        assertEquals("Local", r.name)
        assertEquals("~/x", r.title)
    }

    @Test fun tabNameDropsOnlyTheSharedMarker() {
        assertEquals("Remote (2)", tabName("Remote (2)" + TerminalText.SHARED_SUFFIX))
        assertEquals("Remote (2)", tabName("Remote (2)"))
        assertEquals("a" + TerminalText.SHARED_SUFFIX, tabName("a" + TerminalText.SHARED_SUFFIX.repeat(2)))
        assertEquals("", tabName(null))
    }
}
