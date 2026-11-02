package io.github.danisec2601.idelink.core

import io.github.danisec2601.idelink.shared.Plain
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private const val MAX_PATH_SHOWN = 200
private const val MAX_WARNING = 300
private val TIMEOUT_MINUTES = Approval.TIMEOUT_MS.milliseconds.inWholeMinutes

/** What the user is asked before a claude is paired: only facts the gate derived, never text Claude sent. */
internal fun pairingPrompt(peer: PeerGate.Peer, bridge: Path) = Approval.Prompt(
    project = null,
    title = "idelink: allow Claude?",
    lines = listOfNotNull(
        "Claude Code pid ${peer.ppid} (${Plain.text(peer.claude?.toString() ?: "?", MAX_PATH_SHOWN)}) wants to " +
            "connect through idelink, via bridge pid ${peer.pid} (${Plain.text(bridge.toString(), MAX_PATH_SHOWN)}).",
        peer.claudeUserWritable?.let { USER_WRITABLE },
    ) + peer.launchWarnings.map { Plain.text(it, MAX_WARNING) } + listOfNotNull(
        "Its working directory: ${Plain.text(peer.parentCwd, max = 300)}",
        "If allowed, it can see your open files and selected text (secret scrub is a heuristic) and show you " +
            "files, diffs and tours, for the rest of this IDE session. Deny unless you just asked Claude to use " +
            "the IDE.",
        "No answer within $TIMEOUT_MINUTES minutes counts as Deny.",
    ),
)

/** The pairing prompt's line for a claude binary [TrustedClaude.userWritable] flags. */
internal const val USER_WRITABLE = "⚠ this Claude binary can be modified by your user account"

/** Files outside every project root: refusals no approval lifts, then one Allow-once prompt per call. */
internal class OutsideGate(private val approval: Approval) {
    /** [PathScope.locate], with outside files that no approval could lift refused here. */
    fun locate(roots: List<PathScope.Root>, input: String, projectName: String?): PathScope.Resolved =
        when (val l = PathScope.locate(roots, input, projectName)) {
            is PathScope.Located.Inside -> l.resolved
            is PathScope.Located.Outside -> l.resolved.also { r -> refusal(input, r)?.let { throw BadArgs(it) } }
        }

    /** Why [r] is refused by name alone, or null if the user may be asked. [input] is the path as Claude gave it. */
    fun refusal(input: String, r: PathScope.Resolved): String? =
        (Scrub.secretBearingName(input) ?: Scrub.secretBearingName(r.real.toString()))
            ?.let { "$it; idelink never shows such files from outside the project" }
            ?: Policy.refuseRendered(r.real.fileName.toString())

    /**
     * Asks once for the outside ones among [files] (to [what], e.g. "open"; [tourStops] for a tour); throws
     * [BadArgs] unless allowed. Returns true only when the user clicked Allow once, false when nothing needed asking.
     */
    fun confirm(what: String, files: List<PathScope.Resolved>, tourStops: Int = 0): Boolean {
        val out = files.filter { it.outside }
        if (out.isEmpty()) return false
        val project = out[0].project
        val paths = out.map { it.real }.distinct()
        if (paths.size > MAX_LISTED) {
            throw BadArgs("${paths.size} files are outside the project; at most $MAX_LISTED fit in one approval")
        }
        val noun = if (paths.size == 1) "this file" else "these files"
        val lines = listOf("Claude wants to $what $noun outside the project:") +
            paths.map { "• " + Plain.text(it.toString(), max = 500) } +
            allowOnce(tourStops) + "No answer within $TIMEOUT_MINUTES minutes counts as Deny."
        val a = approval.ask(Approval.Prompt(project, "idelink: file outside the project", lines, allow = "Allow once"))
        if (a is Approval.Answer.Denied) throw BadArgs("outside the project and not allowed: ${a.reason}")
        return true
    }

    private fun allowOnce(tourStops: Int) = if (tourStops > 0) {
        "Allow once also starts the whole tour ($tourStops stop${if (tourStops == 1) "" else "s"}, including any " +
            "inside the project) right away, without a second prompt. "
    } else "Allow once covers this one request and shows it right away, without a second prompt. "

    companion object {
        const val MAX_LISTED = 20
    }
}
