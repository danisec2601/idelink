@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.shared

import com.intellij.platform.rpc.topics.ProjectRemoteTopic
import kotlinx.serialization.Serializable

// Backend -> frontend events, every Claude-supplied string already through Plain (the frontend applies it again).
// None carries a file to open: the backend opens files (ClientNavigator), since the client can't resolve them.

/** Text-only contents: no side is file-backed, so the diff viewer has nothing on disk it could write to. */
@Serializable
data class ShowDiffEvent(
    /** Identifies this diff in [ArrivalApi.report]. */
    val offerId: Long,
    /** Project-relative (or real, outside the project) path, display only. */
    val path: String,
    /** The viewer's window title. */
    val title: String,
    /** Picks the syntax highlighting; never opened. */
    val fileName: String,
    /** Left side's caption. */
    val leftTitle: String,
    /** Left side's text. */
    val leftText: String,
    /** Right side's caption. */
    val rightTitle: String,
    /** Right side's text. */
    val rightText: String,
    /** The user already allowed this in the out-of-project prompt: the client shows it without asking again. */
    val userAllowed: Boolean = false,
)

/** An open_file waiting for the client's arrival gate; Show calls [ArrivalApi.openOffer] with [offerId]. */
@Serializable
data class OpenOfferEvent(
    /** Identifies this offer in [ArrivalApi]. */
    val offerId: Long,
    /** Project-relative (or real, outside the project) path, display only. */
    val path: String,
    /** 1-based. */
    val line: Int,
    /** The user already allowed this in the out-of-project prompt: the client shows it without asking again. */
    val userAllowed: Boolean = false,
)

/** One tour stop as displayed: project-relative [path], 1-based [line], and Claude's [note]. */
@Serializable
data class TourItem(
    /** Project-relative, display only. */
    val path: String,
    /** 1-based. */
    val line: Int,
    /** Claude's note, already through [Plain]. */
    val note: String,
)

/** A stop is opened by index through TourApi; [tourId] makes a click on a replaced tour a no-op. */
@Serializable
data class TourEvent(
    /** Identifies this tour in [TourApi.openStop]. */
    val tourId: Long,
    /** The panel's heading. */
    val title: String,
    /** The stops, in order. */
    val items: List<TourItem>,
    /** The user already allowed this in the out-of-project prompt: the client shows it without asking again. */
    val userAllowed: Boolean = false,
)

/** The backend -> frontend topics and the ids the frontend's module xml registers them under. */
object Topics {
    /** Id of [SHOW_DIFF]. */
    const val SHOW_DIFF_ID = "io.github.danisec2601.idelink.showDiff"

    /** Id of [TOUR]. */
    const val TOUR_ID = "io.github.danisec2601.idelink.tour"

    /** Id of [OPEN_OFFER]. */
    const val OPEN_OFFER_ID = "io.github.danisec2601.idelink.openOffer"

    /** Id of [TERMINAL_REQUEST]. */
    const val TERMINAL_REQUEST_ID = "io.github.danisec2601.idelink.terminalRequest"

    /** Show a text-only diff. */
    val SHOW_DIFF: ProjectRemoteTopic<ShowDiffEvent> = ProjectRemoteTopic(SHOW_DIFF_ID, ShowDiffEvent.serializer())

    /** Show a review tour in the tour panel. */
    val TOUR: ProjectRemoteTopic<TourEvent> = ProjectRemoteTopic(TOUR_ID, TourEvent.serializer())

    /** Offer to open a file at a line. */
    val OPEN_OFFER: ProjectRemoteTopic<OpenOfferEvent> = ProjectRemoteTopic(OPEN_OFFER_ID, OpenOfferEvent.serializer())

    /** Backend -> frontend: list the shared tabs or read one. */
    val TERMINAL_REQUEST: ProjectRemoteTopic<TerminalRequestEvent> =
        ProjectRemoteTopic(TERMINAL_REQUEST_ID, TerminalRequestEvent.serializer())
}
