package io.github.danisec2601.idelink.frontend

import io.github.danisec2601.idelink.shared.ArrivalKind
import io.github.danisec2601.idelink.shared.ArrivalOutcome
import io.github.danisec2601.idelink.shared.Plain
import java.util.concurrent.atomic.AtomicBoolean

/** The persisted arrival settings: whether to ask before a tour starts and before a file or diff opens. */
class ArrivalPrefs {
    /** Ask before starting a review tour. */
    var askTour: Boolean = true

    /** Ask before opening a file or diff. */
    var askOpen: Boolean = true
}

/** What the user clicked on an arrival notification, or [DISMISSED] if it closed without a click. */
enum class Choice {
    /** Start tour or Show. */
    GO,

    /** Not now. */
    NOT_NOW,

    /** Always show (files and diffs) or Always start (tours). */
    NEVER_ASK,

    /** Closed or replaced without a click. */
    DISMISSED,
}

/** The arrival gate's decisions over [prefs]. */
class ArrivalGate(private val prefs: ArrivalPrefs) {
    /** Whether an arrival of [kind] asks the user first; never when the backend says [userAllowed] it already. */
    fun asks(kind: ArrivalKind, userAllowed: Boolean): Boolean = !userAllowed && when (kind) {
        ArrivalKind.TOUR -> prefs.askTour
        ArrivalKind.OPEN -> prefs.askOpen
    }

    /** Applies [choice] for [kind]; returns whether the arrival should now be shown. */
    fun answer(kind: ArrivalKind, choice: Choice): Boolean = when (choice) {
        Choice.GO -> true
        Choice.NEVER_ASK -> { setAsk(kind, false); true }
        Choice.NOT_NOW, Choice.DISMISSED -> false
    }

    /** Asks again for every kind. */
    fun reset() = ArrivalKind.entries.forEach { setAsk(it, true) }

    private fun setAsk(kind: ArrivalKind, ask: Boolean) = when (kind) {
        ArrivalKind.TOUR -> prefs.askTour = ask
        ArrivalKind.OPEN -> prefs.askOpen = ask
    }

    /** The outcome reported to the backend for [choice]. */
    fun outcome(choice: Choice): ArrivalOutcome =
        if (choice == Choice.GO || choice == Choice.NEVER_ASK) ArrivalOutcome.ACCEPTED else ArrivalOutcome.DECLINED
}

/** Passes only the first [answer] to [onAnswer]: a click expires its notification, which must not answer again. */
class OneAnswer(private val onAnswer: (Choice) -> Unit) {
    private val done = AtomicBoolean()

    /** Delivers [choice] unless an answer was already given. */
    fun answer(choice: Choice) {
        if (done.compareAndSet(false, true)) onAnswer(choice)
    }
}

/** Notification texts; notification content is HTML, so Claude's strings go through Plain and are escaped. */
object ArrivalText {
    private const val MAX_TITLE = 200
    private const val MAX_PATH = 300

    /** The prompt for a tour titled [title] with [stops] stops. */
    fun tour(title: String, stops: Int): Pair<String, String> =
        "Claude sent a review tour ($stops stop${if (stops == 1) "" else "s"})" to html(Plain.text(title, MAX_TITLE))

    /** The prompt for opening [path] at [line]. */
    fun open(path: String, line: Int): Pair<String, String> =
        "Claude wants to show a file" to html(Plain.text("$path:$line", MAX_PATH))

    /** The prompt for a diff of [path]. */
    fun diff(path: String): Pair<String, String> = "Claude wants to show a diff" to html(Plain.text(path, MAX_PATH))

    /** The label of the button that stops asking for [kind]. */
    fun always(kind: ArrivalKind): String = when (kind) {
        ArrivalKind.TOUR -> "Always start"
        ArrivalKind.OPEN -> "Always show"
    }

    /** Escapes what Plain leaves that HTML would still interpret, like entities such as &#x202E;. */
    fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
