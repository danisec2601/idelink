@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.danisec2601.idelink.core.Arrivals
import io.github.danisec2601.idelink.core.BadArgs
import io.github.danisec2601.idelink.core.OutsideGate
import io.github.danisec2601.idelink.core.PathScope
import io.github.danisec2601.idelink.core.Policy
import io.github.danisec2601.idelink.core.Schema
import io.github.danisec2601.idelink.core.Scrub
import io.github.danisec2601.idelink.core.Texts
import io.github.danisec2601.idelink.core.ToolDef
import io.github.danisec2601.idelink.core.int
import io.github.danisec2601.idelink.core.str
import io.github.danisec2601.idelink.shared.ArrivalKind
import io.github.danisec2601.idelink.shared.OpenOfferEvent
import io.github.danisec2601.idelink.shared.Plain
import io.github.danisec2601.idelink.shared.ShowDiffEvent
import io.github.danisec2601.idelink.shared.Topics
import io.github.danisec2601.idelink.shared.TourEvent
import io.github.danisec2601.idelink.shared.TourItem
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private val LOG = logger<PresentTools>()
private const val NO_CLIENT = "no IDE client is connected to this project"
private const val ASK_NOTE = "The IDE asks the user before showing what Claude sends, unless they chose 'Always " +
    "show' ('Always start' for tours) for that kind or just allowed it as an outside file; the result's arrival " +
    "field says which happened."
private const val MAX_OPEN_FILES = 500
private val SCHEMA_LANGUAGES = listOf("JSON", "JSON5", "JSON Lines", "yaml", "TOML")

/** [Policy.schemaTyped], or a name the backend's file type associations give a JSON-schema language. */
internal fun schemaTyped(p: Path): Boolean {
    val name = p.fileName.toString()
    if (Policy.schemaTyped(name)) return true
    val lang = (FileTypeManager.getInstance().getFileTypeByFileName(name) as? LanguageFileType)?.language
    return lang != null && SCHEMA_LANGUAGES.any { lang.isKindOf(it) }
}

/** The `project` argument's description, shared by every tool that takes one. */
internal const val PROJECT_ARG = "Project as tools report it: its name, or 'name (dir)' when two open projects " +
    "share a name. Only needed if several projects are open"

/**
 * The tools this plugin serves. Paths go through PathScope and Claude-supplied strings through Plain at each
 * call site below (a convention, not a type: a new call site has to remember it).
 */
internal class PresentTools(
    private val store: EditorStateStore,
    private val outside: OutsideGate,
    private val arrivals: Arrivals<OpenTarget>,
) {

    private val pathArg = Schema.Str("File path: project-relative or absolute. Outside the project root the user is " +
        "asked to allow it once (secret-named files and preview types are refused there)", 4096)
    private val projectArg = Schema.Str(PROJECT_ARG, 256)

    fun all(): List<ToolDef> = listOf(
        ToolDef("get_active_file",
            "The file the user has active in the IDE: project, project-relative path, caret line. " +
                "Files outside the project root are never reported.",
            Schema.Obj(emptyMap())) { activeFile() },
        ToolDef("get_ide_state",
            "Per open project: the active file, caret line, selected line range, and the open editor tabs " +
                "(project-relative). Files outside the project root are left out (counted, not named). No file contents.",
            Schema.Obj(emptyMap())) { ideState() },
        ToolDef("get_selection",
            "The text the user has selected in the active editor, with its line range. Protections are HEURISTICS: " +
                "selections in files whose name looks secret-bearing (.env, .env.*, *.env, *.env.*, *.pem, *.key, *.p12, *.pfx, id_rsa*, " +
                "id_ed25519*, *secret*, *credential*, .netrc, .pgpass) return the range only; other text is scrubbed " +
                "of recognisable secret shapes (PEM private keys, AWS AKIA/ASIA, GitHub ghp_/gho_/github_pat_, Slack " +
                "xox*, Google AIza, Anthropic sk-ant-, AGE-SECRET-KEY-, Authorization:/Bearer values, JWTs) into " +
                "per-read random placeholders. Bare hex, base64 or UUID secrets and passwords are NOT caught. " +
                "Multi-caret/column selections return the range only. Capped at 64K characters.",
            Schema.Obj(mapOf("project" to projectArg))) { selection(it) },
        ToolDef("open_file",
            "Open a file in the user's editor at a line, scroll it into view and highlight lines line..end_line, " +
                "without taking keyboard focus. $ASK_NOTE A file outside the project needs the user's Allow once. " +
                "Refuses types with rendering previews (Markdown, HTML, SVG, notebooks, diagrams); use show_diff " +
                "to show those as text. Refuses files that reference a remote JSON schema (\$schema/\$ref URL).",
            Schema.Obj(mapOf(
                "path" to pathArg,
                "line" to Schema.Integer("1-based first line"),
                "end_line" to Schema.Integer("1-based last line to highlight (default: line)"),
                "project" to projectArg,
            ), required = setOf("path", "line"))) { openFile(it) },
        ToolDef("show_diff",
            "SHOW-ONLY: open the IDE diff viewer for a file. Give proposed_text to compare the on-disk file (left) " +
                "with your proposal (right), or from_ref to compare the file at a git revision (left) with disk " +
                "(right). Nothing is applied and nothing writes: make changes with your normal edit tools. The " +
                "viewer shows the on-disk sha256 at presentation time and warns about bidi/invisible characters. " +
                "Asks the user first like open_file.",
            Schema.Obj(mapOf(
                "path" to pathArg,
                "proposed_text" to Schema.Str("Full proposed file content", Texts.MAX_FILE),
                "from_ref" to Schema.Str("git revision: branch, tag, sha, HEAD~n", 200),
                "title" to Schema.Str("Short title", 200),
                "project" to projectArg,
            ), required = setOf("path"))) { showDiff(it) },
        ToolDef("review_tour",
            "Give the user a navigable list of locations with a note each, in an 'idelink Tour' panel they click " +
                "through. Notes are shown as plain text. All items must be in one project. Same file-type limits as " +
                "open_file; stops outside the project are allowed together in one prompt. The tour always waits in " +
                "the panel; the user is asked whether to start it at stop 1 (or it starts directly, by their choice).",
            Schema.Obj(mapOf(
                "title" to Schema.Str("Tour title", 200),
                "items" to Schema.Arr(Schema.Obj(mapOf(
                    "path" to pathArg,
                    "line" to Schema.Integer("1-based line"),
                    "note" to Schema.Str("What to look at here", 2000),
                ), required = setOf("path", "line", "note")), maxItems = 200),
                "project" to projectArg,
            ), required = setOf("items"))) { tour(it) },
    )

    private fun entry(project: Project) = store.get(OpenProjects.key(project))

    private fun underRoot(project: Project, label: String, path: String): PathScope.Resolved? {
        val root = PathScope.realRoot(label, project.basePath) ?: return null
        return PathScope.under(root, java.nio.file.Path.of(path))
    }

    private fun outsideSelection(project: Project, label: String, path: String): PathScope.Resolved? {
        val root = PathScope.realRoot(label, project.basePath) ?: return null
        val located = runCatching { PathScope.locate(listOf(root), path, label) }.getOrNull()
        return (located as? PathScope.Located.Outside)?.resolved
    }

    private fun arrival(
        o: JsonObject, routed: Arrivals.Routed, direct: String,
        asked: String = "the user is asked whether to show it; nothing opens unless they choose Show",
    ) {
        o.addProperty("arrival", routed.name.lowercase())
        o.addProperty("status", when (routed) {
            Arrivals.Routed.DIRECT -> "$direct without asking (the user chose that); display is not confirmed back"
            Arrivals.Routed.ALLOWED -> "$direct without a second prompt: the user allowed it in the out-of-project " +
                "prompt; display is not confirmed back"
            Arrivals.Routed.ASKED -> "$asked. Their answer doesn't come back: don't resend to retry"
            Arrivals.Routed.NO_REPLY -> "sent to the IDE client, which didn't say within " +
                "${Arrivals.WAIT_MS.milliseconds.inWholeSeconds} s whether it showed it or asked the user: it is " +
                "busy, a modal dialog is open there (it shows once that closes), or its idelink plugin is older"
        })
    }

    private fun clientSeen(o: JsonObject, project: Project) {
        val e = entry(project)
        if (e == null) o.addProperty("warning", "no report from this project's IDE client yet (idelink frontend installed there?)")
        else o.addProperty("client_reported_ms_ago", System.currentTimeMillis() - e.atMillis)
    }

    private fun activeFile(): JsonElement {
        val out = JsonObject()
        val why = mutableListOf<String>()
        for ((project, label) in OpenProjects.labelled()) {
            val entry = entry(project)
            if (entry == null) {
                why += "$label: no report from the thin client yet (frontend module installed there?)"; continue
            }
            val path = entry.state.path
            if (path == null) { why += "$label: client reports no active file"; continue }
            val r = underRoot(project, label, path)
            if (r == null) {
                // The path comes from the client, not Claude, so saying it's gone is no probe of the filesystem.
                val gone = runCatching { !java.nio.file.Files.exists(java.nio.file.Path.of(path)) }.getOrDefault(false)
                LOG.info("idelink: client-reported active file is not under $label's root (gone=$gone): $path")
                why += if (gone) "$label: the active file no longer exists (renamed or deleted?)"
                else "$label: active file is outside the project root"
                continue
            }
            out.addProperty("project", label)
            out.addProperty("path", r.rel)
            entry.state.line?.let { out.addProperty("line", it) }
            out.addProperty("reported_ms_ago", System.currentTimeMillis() - entry.atMillis)
            break
        }
        // Never return a bare {}: an empty success can't be told apart from a broken lookup.
        if (out.size() == 0) out.addProperty("none", if (why.isEmpty()) "no open projects" else why.joinToString("; "))
        return out
    }

    private fun ideState(): JsonElement {
        val arr = JsonArray()
        for ((project, label) in OpenProjects.labelled()) {
            val o = JsonObject().apply { addProperty("project", label) }
            val entry = entry(project)
            if (entry == null) { o.addProperty("none", "no report from the thin client yet"); arr.add(o); continue }
            val s = entry.state
            val active = s.path?.let { underRoot(project, label, it) }
            if (active != null) {
                o.addProperty("active_file", active.rel)
                s.line?.let { o.addProperty("caret_line", it) }
                s.selection?.takeIf { it.endOffset > it.startOffset }?.let {
                    o.add("selection_lines", JsonArray().apply { add(it.startLine); add(it.endLine) })
                }
            } else if (s.path != null) o.addProperty("active_file_outside_root", true)
            val open = JsonArray()
            var outsideCount = 0
            for (p in s.openFiles.take(MAX_OPEN_FILES)) {
                val r = underRoot(project, label, p)
                if (r == null) outsideCount++ else open.add(r.rel)
            }
            o.add("open_files", open)
            if (outsideCount > 0) o.addProperty("open_files_outside_root", outsideCount)
            o.addProperty("reported_ms_ago", System.currentTimeMillis() - entry.atMillis)
            arr.add(o)
        }
        return JsonObject().apply { add("projects", arr) }
    }

    private fun selection(args: JsonObject): JsonElement {
        val want = args.str("project")
        val all = OpenProjects.labelled()
        val wanted = want?.let(OpenProjects::require)
        val (project, label, entry) = all.filter { wanted == null || it.first == wanted }
            .mapNotNull { (p, l) -> entry(p)?.let { Triple(p, l, it) } }.maxByOrNull { it.third.atMillis }
            ?: throw BadArgs("no report from the IDE client yet")
        val out = JsonObject().apply { addProperty("project", label) }
        out.addProperty("reported_ms_ago", System.currentTimeMillis() - entry.atMillis)
        val sel = entry.state.selection?.takeIf { it.endOffset > it.startOffset }
            ?: return out.apply { addProperty("none", "nothing selected") }
        // The selection's own editor file, not the selected tab: in a split layout they can differ.
        val r = underRoot(project, label, sel.path) ?: outsideSelection(project, label, sel.path)
            ?: return out.apply {
                addProperty("none", "selected file is outside the project root and not a readable file")
            }
        if (r.outside) {
            outside.refusal(sel.path, r)?.let {
                return out.apply { addProperty("none", "selected file is outside the project root: $it") }
            }
            outside.confirm("read your selection in", listOf(r))
        }
        out.addProperty("path", r.rel)
        out.add("lines", JsonArray().apply { add(sel.startLine); add(sel.endLine) })
        Policy.withholdSelection(sel.carets, sel.endOffset - sel.startOffset, sel.path, project.basePath, r.rel)
            ?.let { return out.apply { addProperty("withheld", it) } }
        val text = runReadActionBlocking {
            val doc = document(r.real) ?: return@runReadActionBlocking null
            if (sel.endOffset > doc.textLength ||
                doc.getLineNumber(sel.startOffset) + 1 != sel.startLine || doc.getLineNumber(sel.endOffset) + 1 != sel.endLine) null
            else doc.getText(TextRange(sel.startOffset, sel.endOffset))
        } ?: return out.apply { addProperty("withheld", "the backend's copy of the document doesn't match the client's selection (unsynced?)") }
        val s = Scrub.scrub(text)
        out.addProperty("text", s.text)
        if (s.redactions.isNotEmpty()) out.add("redacted", JsonObject().apply { s.redactions.forEach { (k, n) -> addProperty(k, n) } })
        return out
    }

    private fun vfile(r: PathScope.Resolved): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(r.real) ?: throw BadArgs("IDE can't find the file")

    private fun document(real: java.nio.file.Path): Document? =
        LocalFileSystem.getInstance().findFileByNioFile(real)?.let { FileDocumentManager.getInstance().getDocument(it) }

    private fun lineCount(vf: VirtualFile): Int = runReadActionBlocking {
        FileDocumentManager.getInstance().getDocument(vf)?.lineCount ?: throw BadArgs("not a text file")
    }.coerceAtLeast(1)

    /** An outside file comes back unconfirmed: pass it to [OutsideGate.confirm] before touching it. */
    private fun located(args: JsonObject, projectName: String? = args.str("project")) =
        outside.locate(OpenProjects.roots(), args.str("path")!!, projectName)

    private fun requireFresh(r: PathScope.Resolved) {
        if (!PathScope.recheck(r)) throw BadArgs("the file moved or became a symlink during the call")
    }

    private fun openFile(args: JsonObject): JsonElement {
        val r = located(args)
        val allowed = outside.confirm("open", listOf(r))
        requireFresh(r)
        Policy.refuseOpen(r.real, schemaTyped(r.real))?.let { throw BadArgs(it) }
        val n = lineCount(vfile(r))
        val line = args.int("line")!!
        val end = args.int("end_line") ?: line
        if (line > n) throw BadArgs("line $line is past the end of the file ($n lines)")
        if (end < line || end > n) throw BadArgs("end_line must be in $line..$n")
        val project = OpenProjects.of(r)
        val out = JsonObject().apply {
            addProperty("project", r.project); addProperty("path", r.rel)
            addProperty("line", line); addProperty("end_line", end)
        }
        if (ClientNavigator.controllers(project).isEmpty()) return out.apply { addProperty("status", NO_CLIENT) }
        val key = OpenProjects.key(project)
        val id = arrivals.offer(key, OpenTarget(r, line, end))
        val routed = arrivals.send(key, ArrivalKind.OPEN, id, allowed) {
            ClientNavigator.send(Topics.OPEN_OFFER, project, OpenOfferEvent(id, Plain.text(r.rel, 500), line, allowed))
        }
        return out.apply { arrival(this, routed, "opened in the editor"); clientSeen(this, project) }
    }

    private fun showDiff(args: JsonObject): JsonElement {
        val proposed = args.str("proposed_text")
        val ref = args.str("from_ref")
        Policy.diffSource(proposed, ref)
        val r = located(args)
        val allowed = outside.confirm("show a diff of", listOf(r))
        val disk = Texts.readFile(r.real)
        val short = disk.sha256.take(12)
        val sides = if (proposed != null)
            Sides("On disk · sha256 $short…", disk.text, "Proposed by Claude · not applied", proposed)
        else Sides("At ${Texts.checkRef(ref!!)}", gitBlob(r, ref), "On disk · sha256 $short…", disk.text)
        Policy.requireNoRemoteSchema(schemaTyped(r.real), sides.left, sides.right)
        val lw = Texts.invisibleWarning(sides.left)
        val rw = Texts.invisibleWarning(sides.right)
        val title = args.str("title")?.let { "$it — " } ?: ""
        requireFresh(r)
        val project = OpenProjects.of(r)
        val name = r.real.fileName.toString()
        val out = JsonObject().apply {
            addProperty("project", r.project); addProperty("path", r.rel); addProperty("disk_sha256", disk.sha256)
            lw?.let { addProperty("left_warning", it) }; rw?.let { addProperty("right_warning", it) }
        }
        if (ClientNavigator.controllers(project).isEmpty()) return out.apply { addProperty("status", NO_CLIENT) }
        val id = arrivals.newId()
        val event = ShowDiffEvent(
            offerId = id,
            path = Plain.text(r.rel, 500),
            title = Plain.text("idelink (show-only): $title${r.rel}", 300),
            fileName = Plain.text(name, 255),
            leftTitle = Plain.text(sides.leftLabel + (lw?.let { "  $it" } ?: ""), 400),
            leftText = sides.left,
            rightTitle = Plain.text(sides.rightLabel + (rw?.let { "  $it" } ?: ""), 400),
            rightText = sides.right,
            userAllowed = allowed,
        )
        val routed = arrivals.send(OpenProjects.key(project), ArrivalKind.OPEN, id, allowed) {
            ClientNavigator.send(Topics.SHOW_DIFF, project, event)
        }
        return out.apply { arrival(this, routed, "the diff viewer opened"); clientSeen(this, project) }
    }

    private fun gitBlob(r: PathScope.Resolved, ref: String) =
        if (r.outside) Texts.gitBlob(r.real.parent, ref, r.real.fileName.toString())
        else Texts.gitBlob(r.root, ref, r.rel)

    private data class Sides(val leftLabel: String, val left: String, val rightLabel: String, val right: String)

    private fun tour(args: JsonObject): JsonElement {
        val items = args.getAsJsonArray("items").map { it.asJsonObject }
        var projectName = args.str("project")
        val resolved = items.mapIndexed { i, item ->
            val r = try { located(item, projectName) } catch (e: BadArgs) { throw BadArgs("items[$i]: ${e.message}") }
            projectName = r.project
            r
        }
        fun check(outsideOnes: Boolean) = items.forEachIndexed { i, item ->
            val r = resolved[i]
            if (r.outside != outsideOnes) return@forEachIndexed
            if (outsideOnes) requireFresh(r)
            Policy.refuseOpen(r.real, schemaTyped(r.real))?.let { m -> throw BadArgs("items[$i]: $m") }
            val line = item.int("line")!!
            val n = lineCount(vfile(r))
            if (line > n) throw BadArgs("items[$i]: line $line is past the end of the file ($n lines)")
        }
        check(outsideOnes = false)
        val allowed = outside.confirm("add to a review tour", resolved, tourStops = resolved.size)
        check(outsideOnes = true)
        val out = mutableListOf<TourItem>()
        val stops = mutableListOf<TourStore.Stop>()
        items.forEachIndexed { i, item ->
            val r = resolved[i]
            requireFresh(r)
            val line = item.int("line")!!
            stops += TourStore.Stop(r, line)
            out += TourItem(Plain.text(r.rel, 500), line, Plain.text(item.str("note")!!, 2000, multiline = true))
        }
        val project = OpenProjects.of(resolved.firstOrNull() ?: throw BadArgs("items is empty"))
        val title = Plain.text(args.str("title") ?: "Review tour", 200)
        val result = JsonObject().apply { addProperty("project", projectName); addProperty("items", out.size) }
        if (ClientNavigator.controllers(project).isEmpty()) return result.apply { addProperty("status", NO_CLIENT) }
        val key = OpenProjects.key(project)
        val tourId = ApplicationManager.getApplication().getService(TourStore::class.java).put(key, stops)
        val routed = arrivals.send(key, ArrivalKind.TOUR, tourId, allowed) {
            ClientNavigator.send(Topics.TOUR, project, TourEvent(tourId, title, out, allowed))
        }
        return result.apply {
            arrival(this, routed, "the tour is in the idelink Tour panel and opened at stop 1",
                "the tour is waiting in the user's idelink Tour panel; they are asked whether to start it")
            clientSeen(this, project)
        }
    }
}
