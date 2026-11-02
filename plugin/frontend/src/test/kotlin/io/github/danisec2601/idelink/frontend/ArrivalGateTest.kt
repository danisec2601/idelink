package io.github.danisec2601.idelink.frontend

import com.intellij.util.xmlb.XmlSerializer
import io.github.danisec2601.idelink.shared.ArrivalKind
import io.github.danisec2601.idelink.shared.ArrivalOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArrivalGateTest {
    @Test
    fun everyKindAsksByDefault() {
        val g = ArrivalGate(ArrivalPrefs())
        ArrivalKind.entries.forEach { assertTrue(g.asks(it, userAllowed = false), "$it") }
    }

    @Test
    fun dontAskAgainShowsAndStopsAskingForThatKindOnly() {
        val g = ArrivalGate(ArrivalPrefs())
        assertTrue(g.answer(ArrivalKind.OPEN, Choice.NEVER_ASK))
        assertFalse(g.asks(ArrivalKind.OPEN, userAllowed = false))
        assertTrue(g.asks(ArrivalKind.TOUR, userAllowed = false), "a file's Always show must not silence tours")
        assertTrue(g.answer(ArrivalKind.TOUR, Choice.NEVER_ASK))
        assertFalse(g.asks(ArrivalKind.TOUR, userAllowed = false))
    }

    @Test
    fun anAllowOnceSkipsTheQuestionWithoutChangingTheSetting() {
        val p = ArrivalPrefs()
        val g = ArrivalGate(p)
        ArrivalKind.entries.forEach { assertFalse(g.asks(it, userAllowed = true), "$it") }
        assertTrue(p.askTour && p.askOpen, "Allow once must not turn asking off for later arrivals")
        ArrivalKind.entries.forEach { assertTrue(g.asks(it, userAllowed = false), "$it") }
    }

    @Test
    fun alwaysButtonsAreNamedPerKind() {
        assertEquals("Always show", ArrivalText.always(ArrivalKind.OPEN))
        assertEquals("Always start", ArrivalText.always(ArrivalKind.TOUR))
    }

    @Test
    fun notNowAndDismissShowNothingAndKeepAsking() {
        val g = ArrivalGate(ArrivalPrefs())
        for (c in listOf(Choice.NOT_NOW, Choice.DISMISSED)) {
            ArrivalKind.entries.forEach { k ->
                assertFalse(g.answer(k, c), "$c on $k must not show")
                assertTrue(g.asks(k, userAllowed = false), "$c on $k must keep asking")
                assertEquals(ArrivalOutcome.DECLINED, g.outcome(c))
            }
        }
        assertTrue(g.answer(ArrivalKind.OPEN, Choice.GO))
        assertTrue(g.asks(ArrivalKind.OPEN, userAllowed = false), "Show once must keep asking")
        assertEquals(ArrivalOutcome.ACCEPTED, g.outcome(Choice.GO))
        assertEquals(ArrivalOutcome.ACCEPTED, g.outcome(Choice.NEVER_ASK))
    }

    @Test
    fun resetAsksAgainForEveryKind() {
        val p = ArrivalPrefs().apply { askTour = false; askOpen = false }
        ArrivalGate(p).reset()
        assertTrue(p.askTour)
        assertTrue(p.askOpen)
    }

    @Test
    fun onlyTheFirstAnswerCounts() {
        val got = mutableListOf<Choice>()
        val once = OneAnswer { got += it }
        once.answer(Choice.GO)
        once.answer(Choice.DISMISSED)
        assertEquals(listOf(Choice.GO), got, "the expiry after a click must not answer again")
    }

    @Test
    fun settingsSurviveThePlatformSerializer() {
        for ((tour, open) in listOf(false to true, true to false, false to false, true to true)) {
            val saved = XmlSerializer.serialize(ArrivalPrefs().apply { askTour = tour; askOpen = open })
            val back = XmlSerializer.deserialize(saved, ArrivalPrefs::class.java)
            assertEquals(tour to open, back.askTour to back.askOpen)
        }
    }

    @Test
    fun claudesStringsCantMarkUpTheNotification() {
        val (title, body) = ArrivalText.tour("<b>x</b> &#x202E; ‮", 2)
        assertEquals("Claude sent a review tour (2 stops)", title)
        assertFalse(body.contains('<') || body.contains("&#"), body)
        assertTrue(body.contains("⟦U+202E⟧"), body)
        assertFalse(ArrivalText.open("<img src=x>", 3).second.contains('<'))
        assertFalse(ArrivalText.diff("a&#60;b").second.contains("&#"))
    }
}
