package io.github.danisec2601.idelink

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import io.github.danisec2601.idelink.core.BadArgs
import io.github.danisec2601.idelink.core.PathScope
import io.github.danisec2601.idelink.core.Schema
import io.github.danisec2601.idelink.core.Scrub
import io.github.danisec2601.idelink.core.ToolDef
import io.github.danisec2601.idelink.core.bool
import io.github.danisec2601.idelink.core.int
import io.github.danisec2601.idelink.core.str
import io.github.danisec2601.idelink.shared.Plain
import java.nio.file.Path

internal const val INDEXING = "IDE is indexing, retry shortly"

internal enum class MinSeverity { ERROR, WARNING, WEAK_WARNING }

private const val MAX_LINE_TEXT = 200
private const val MAX_NAME = 200
private const val MAX_QUALIFIED_NAME = 300
private const val MAX_OPEN_FILES_CHECKED = 500
private const val MAX_NAME_ECHOED = 64
private const val MAX_HEADER = 500
private const val MAX_DOC = 1000

/** The IDE side of the analysis tools: raw results with absolute paths, shaped and scoped by [AnalysisTools]. */
internal interface AnalysisIde {
    fun isDumb(project: String): Boolean

    /** Length of each line in UTF-16 units, without the line break; null if the IDE has no text document for it. */
    fun lineLengths(project: String, file: Path): IntArray?
    fun problems(project: String, file: Path, min: MinSeverity, limit: Int): Problems

    /** [line] and [column] are 1-based and already checked against [lineLengths]. */
    fun resolve(project: String, file: Path, line: Int, column: Int): Symbol?
    fun usages(project: String, file: Path, line: Int, column: Int, includeLibraries: Boolean, limit: Int): Usages?
    fun structure(project: String): Structure

    data class Problem(
        val severity: String, val line: Int, val column: Int, val description: String?, val inspection: String?,
    )
    data class Problems(val items: List<Problem>, val more: Boolean, val finished: Boolean)

    /** [file] is null when the declaration isn't in a local file; [inLibrary] covers SDKs too. */
    data class Location(
        val file: Path?, val line: Int, val lineText: String?,
        val inContent: Boolean, val inLibrary: Boolean, val library: String?,
    )
    data class Symbol(
        val kind: String?, val name: String?, val qualifiedName: String?,
        val header: String?, val doc: String?, val at: Location,
    )
    data class Usages(val target: Symbol, val items: List<Location>, val more: Boolean)

    data class Sdk(val name: String?, val version: String?, val type: String?)
    data class Folder(val path: Path, val test: Boolean)
    data class Module(val name: String, val contentRoots: List<Path>, val sourceFolders: List<Folder>, val sdk: Sdk?)
    data class Structure(val modules: List<Module>, val projectSdk: Sdk?)
}

/**
 * Read-only code-insight tools. Positions are checked against the document, paths go through [PathScope], and
 * every string that leaves comes from [Plain]; files outside a project root are counted or named by library only.
 */
internal class AnalysisTools(
    private val ide: AnalysisIde = PlatformAnalysis(),
    private val roots: () -> List<PathScope.Root> = { OpenProjects.roots() },
    private val openFiles: (String) -> List<String>? = { label ->
        OpenProjects.byLabel(label)?.let { p ->
            ApplicationManager.getApplication().getService(EditorStateStore::class.java).get(OpenProjects.key(p))
        }?.state?.openFiles
    },
    private val home: String? = System.getProperty("user.home"),
) {
    companion object {
        const val MAX_PROBLEMS = 200
        const val MAX_USAGES = 200
        const val SCAN_USAGES = 5000
        const val MAX_MODULES = 100
        const val MAX_FOLDERS = 200
        const val NOT_ANALYSED = "not analysed: the file isn't open in an editor, so the IDE has no results for it; " +
            "open it first with open_file, then retry"
    }

    private val pathArg =
        Schema.Str("File path: project-relative, or absolute inside an open project", maxLength = 4096)
    private val projectArg = Schema.Str(PROJECT_ARG, maxLength = 256)
    private val position = mapOf(
        "path" to pathArg,
        "line" to Schema.Integer("1-based line"),
        "column" to Schema.Integer("1-based character column on that line (a tab counts as one)"),
        "project" to projectArg,
    )

    fun all(): List<ToolDef> = listOf(
        ToolDef("get_problems",
            "The IDE's current errors and warnings for one file, as its background analysis computed them: " +
                "severity, 1-based line and column, description, inspection id. Never runs analysis itself: only " +
                "files open in the user's editor are analysed, and for a file that isn't open and has no results " +
                "this says 'not_analysed' (open it with open_file, then retry). analysis_finished=false means " +
                "results may still change. Max 200 items. Descriptions are scrubbed of recognisable secret shapes " +
                "(heuristic) and withheld for secret-bearing file names.",
            Schema.Obj(mapOf(
                "path" to pathArg,
                "min_severity" to Schema.Str("error | warning | weak_warning (default weak_warning)", maxLength = 16),
                "project" to projectArg,
            ), required = setOf("path"))) { problems(it) },
        ToolDef("find_usages",
            "References to the symbol at a position, from the IDE's index. Searches project content only unless " +
                "include_libraries. Each usage inside the project: project-relative path, line, and the line's text " +
                "(max 200 chars, scrubbed of recognisable secret shapes). Usages outside the project root are " +
                "counted, not named. Max 200 usages. Fails with a retry message while the IDE is indexing.",
            Schema.Obj(
                position + ("include_libraries" to Schema.Bool("Also search libraries and the SDK (default false)")),
                required = setOf("path", "line", "column"),
            )) { usages(it) },
        ToolDef("go_to_definition",
            "Resolve the symbol at a position to its declaration. Inside the project: project-relative path, line " +
                "and the declaration line's text. In a library or the SDK: the library/SDK name and the qualified " +
                "name only (no path, no contents); for a Python module that is its import name. Paths in the name " +
                "show as ./relative inside the project, [path] outside it. Fails with a retry message while the " +
                "IDE is indexing.",
            Schema.Obj(position, required = setOf("path", "line", "column"))) { definition(it) },
        ToolDef("symbol_info",
            "The symbol at a position: kind, name, qualified name (when the language has one), declaration header, " +
                "docstring, and where it is declared (as go_to_definition). The header is the declaration's line; " +
                "for Python it is the whole def/class signature. docstring is Python-only: present for Python " +
                "symbols in IDEs with the Python plugin, absent otherwise. Header and docstring come from project, " +
                "library and SDK sources, scrubbed of recognisable secret shapes (heuristic); they are withheld " +
                "for anything else outside the project. Fails with a retry message while indexing.",
            Schema.Obj(position, required = setOf("path", "line", "column"))) { symbolInfo(it) },
        ToolDef("project_structure",
            "An open project's modules with their content roots and source/test folders as project-relative paths " +
                "(folders outside the project root are counted, not named), and the project and per-module SDK: " +
                "name, type, version, as the IDE's project settings name it. In IDEs with the Python plugin a " +
                "module's SDK is its Python interpreter where it has one. Paths in SDK names show as ./relative " +
                "inside the project and [path] outside it.",
            Schema.Obj(mapOf("project" to projectArg))) { structure(it) },
    )

    private class At(val r: PathScope.Resolved, val root: PathScope.Root)

    private fun resolved(args: JsonObject): At {
        val r = PathScope.resolve(roots(), args.str("path")!!, args.str("project"))
        if (!PathScope.recheck(r)) throw BadArgs("the file moved or became a symlink during the call")
        return At(r, PathScope.Root(r.project, r.root))
    }

    private fun positioned(args: JsonObject): Triple<At, Int, Int> {
        val at = resolved(args)
        val line = args.int("line")!!
        val column = args.int("column")!!
        checkPosition(ide.lineLengths(at.r.project, at.r.real) ?: throw BadArgs("not a text file"), line, column)
        if (ide.isDumb(at.r.project)) throw IllegalStateException(INDEXING)
        return Triple(at, line, column)
    }

    private fun head(at: At) =
        JsonObject().apply { addProperty("project", at.r.project); addProperty("path", at.r.rel) }

    private fun problems(args: JsonObject): JsonElement {
        val min = minSeverity(args.str("min_severity") ?: "weak_warning")
        val at = resolved(args)
        val scan = ide.problems(at.r.project, at.r.real, min, MAX_PROBLEMS)
        val open = isOpen(at)
        val out = head(at)
        if (scan.items.isEmpty() && !open) return out.apply { addProperty("not_analysed", NOT_ANALYSED) }
        val withheld = Scrub.secretBearingName(at.r.rel)
        val arr = JsonArray()
        scan.items.sortedWith(compareBy({ it.line }, { it.column })).take(MAX_PROBLEMS)
            .forEach { arr.add(problemJson(it, withDescription = withheld == null)) }
        out.addProperty("analysis_finished", scan.finished)
        out.add("problems", arr)
        out.addProperty("truncated", scan.more || scan.items.size > MAX_PROBLEMS)
        if (withheld != null) out.addProperty("descriptions_withheld", Plain.text(withheld, max = 200))
        if (!open) out.addProperty("warning", "the file isn't open in an editor; these results may be stale")
        else if (!scan.finished && arr.isEmpty) out.addProperty("note", "analysis is still running; retry shortly")
        return out
    }

    private fun isOpen(at: At): Boolean =
        openFiles(at.r.project).orEmpty().take(MAX_OPEN_FILES_CHECKED).any { p ->
            runCatching { PathScope.under(at.root, Path.of(p))?.real == at.r.real }.getOrDefault(false)
        }

    private fun usages(args: JsonObject): JsonElement {
        val (at, line, column) = positioned(args)
        val libraries = args.bool("include_libraries") ?: false
        val found = ide.usages(at.r.project, at.r.real, line, column, libraries, SCAN_USAGES)
            ?: return head(at).apply { addProperty("none", "no symbol at that position") }
        val out = head(at)
        out.add("symbol", symbolSummary(found.target))
        val sorted = found.items.sortedWith(compareBy({ it.file?.toString() ?: "" }, { it.line }))
        val named = sorted.mapNotNull { u -> inside(at.root, u)?.let { u to it } }
        val arr = JsonArray()
        for ((u, inside) in named.take(MAX_USAGES)) arr.add(JsonObject().apply {
            addProperty("path", inside.rel); addProperty("line", u.line); excerpt(this, inside.rel, u.lineText)
        })
        out.add("usages", arr)
        if (sorted.size > named.size) out.addProperty("usages_outside_project", sorted.size - named.size)
        out.addProperty("truncated", found.more || named.size > MAX_USAGES)
        return out
    }

    private fun definition(args: JsonObject): JsonElement {
        val (at, line, column) = positioned(args)
        val s = ide.resolve(at.r.project, at.r.real, line, column)
            ?: return head(at).apply { addProperty("none", "no symbol at that position, or it doesn't resolve") }
        return head(at).apply { add("definition", place(at.root, s, home)) }
    }

    private fun symbolInfo(args: JsonObject): JsonElement {
        val (at, line, column) = positioned(args)
        val s = ide.resolve(at.r.project, at.r.real, line, column)
            ?: return head(at).apply { addProperty("none", "no symbol at that position, or it doesn't resolve") }
        val o = symbolSummary(s)
        val inside = inside(at.root, s.at)
        val source = inside?.rel ?: if (s.at.inLibrary) s.at.file?.fileName?.toString() ?: "" else null
        when {
            source == null ->
                o.addProperty("source_withheld", "declared outside the project and not in a library or SDK")
            Scrub.secretBearingName(source) != null -> o.addProperty("source_withheld", "secret-bearing file name")
            else -> {
                s.header?.let {
                    o.addProperty("header", Plain.text(Scrub.scrub(it).text, MAX_HEADER, multiline = true))
                }
                s.doc?.let { o.addProperty("docstring", Plain.text(Scrub.scrub(it).text, MAX_DOC, multiline = true)) }
            }
        }
        o.add("declared", place(at.root, s, home))
        return head(at).apply { add("symbol", o) }
    }

    private fun structure(args: JsonObject): JsonElement {
        val root = pickRoot(roots(), args.str("project"))
        val st = ide.structure(root.project)
        val mods = JsonArray()
        st.modules.take(MAX_MODULES).forEach { mods.add(moduleJson(root, it, home)) }
        return JsonObject().apply {
            addProperty("project", root.project)
            add("modules", mods)
            addProperty("truncated", st.modules.size > MAX_MODULES)
            st.projectSdk?.let { add("project_sdk", sdk(root, it, home)) }
        }
    }
}

private fun minSeverity(arg: String) = when (arg) {
    "error" -> MinSeverity.ERROR
    "warning" -> MinSeverity.WARNING
    "weak_warning" -> MinSeverity.WEAK_WARNING
    else -> throw BadArgs("min_severity must be error, warning or weak_warning")
}

private fun pickRoot(all: List<PathScope.Root>, want: String?): PathScope.Root = when {
    want != null -> all.singleOrNull { it.project == want }
        ?: throw BadArgs("no open project named '${want.take(MAX_NAME_ECHOED)}'")
    all.size == 1 -> all[0]
    all.isEmpty() -> throw BadArgs("no open projects")
    else -> throw BadArgs("several projects are open (${all.joinToString { it.project }}); pass project")
}

private fun problemJson(p: AnalysisIde.Problem, withDescription: Boolean) = JsonObject().apply {
    addProperty("severity", Plain.text(p.severity.lowercase().replace(' ', '_'), max = 40))
    addProperty("line", p.line)
    addProperty("column", p.column)
    if (withDescription) p.description?.let { addProperty("description", Plain.text(Scrub.scrub(it).text, max = 300)) }
    p.inspection?.let { addProperty("inspection", Plain.text(it, max = 100)) }
}

private fun moduleJson(root: PathScope.Root, m: AnalysisIde.Module, home: String?) = JsonObject().apply {
    val max = AnalysisTools.MAX_FOLDERS
    addProperty("name", Plain.text(m.name, MAX_NAME))
    var outside = 0
    val content = JsonArray()
    for (p in m.contentRoots.take(max)) PathScope.dirUnder(root, p)?.let { content.add(it) } ?: outside++
    val src = JsonArray()
    val test = JsonArray()
    for (f in m.sourceFolders.take(max)) {
        PathScope.dirUnder(root, f.path)?.let { (if (f.test) test else src).add(it) } ?: outside++
    }
    add("content_roots", content); add("source_folders", src); add("test_folders", test)
    if (outside > 0) addProperty("folders_outside_project", outside)
    if (m.contentRoots.size > max || m.sourceFolders.size > max) addProperty("truncated", true)
    m.sdk?.let { add("sdk", sdk(root, it, home)) }
}

private fun sdk(root: PathScope.Root, s: AnalysisIde.Sdk, home: String?) = JsonObject().apply {
    s.name?.let { addProperty("name", Plain.text(maskPaths(it, root.root, home), MAX_NAME)) }
    s.type?.let { addProperty("type", Plain.text(maskPaths(it, root.root, home), max = 100)) }
    s.version?.let { addProperty("version", Plain.text(maskPaths(it, root.root, home), max = 100)) }
}

private fun inside(root: PathScope.Root, l: AnalysisIde.Location): PathScope.Resolved? =
    l.file?.takeIf { l.inContent }?.let { PathScope.under(root, it) }

private fun symbolSummary(s: AnalysisIde.Symbol) = JsonObject().apply {
    s.kind?.let { addProperty("kind", Plain.text(it, max = 60)) }
    s.name?.let { addProperty("name", Plain.text(it, MAX_NAME)) }
    s.qualifiedName?.let { addProperty("qualified_name", Plain.text(it, MAX_QUALIFIED_NAME)) }
}

private fun place(root: PathScope.Root, s: AnalysisIde.Symbol, home: String?): JsonObject {
    val o = JsonObject()
    val inside = inside(root, s.at)
    if (inside != null) {
        o.addProperty("path", inside.rel); o.addProperty("line", s.at.line); excerpt(o, inside.rel, s.at.lineText)
        return o
    }
    o.addProperty("outside_project", true)
    s.at.library?.takeIf { s.at.inLibrary }
        ?.let { o.addProperty("library", Plain.text(maskPaths(it, root.root, home), MAX_NAME)) }
    s.qualifiedName?.let { o.addProperty("qualified_name", Plain.text(it, MAX_QUALIFIED_NAME)) }
    return o
}

private fun excerpt(o: JsonObject, rel: String, text: String?) {
    if (text == null) return
    if (Scrub.secretBearingName(rel) != null) o.addProperty("text_withheld", "secret-bearing file name")
    else o.addProperty("text", Plain.text(Scrub.scrub(text.trim()).text, MAX_LINE_TEXT))
}

/** Throws [BadArgs] unless 1-based [line] exists and [column] is on it (one past the last character is allowed). */
internal fun checkPosition(lineLengths: IntArray, line: Int, column: Int) {
    val n = lineLengths.size.coerceAtLeast(1)
    if (line < 1 || line > n) throw BadArgs("line $line is outside the file (1..$n)")
    val len = lineLengths.getOrElse(line - 1) { 0 }
    if (column < 1 || column > len + 1) throw BadArgs("column $column is outside line $line (1..${len + 1})")
}
