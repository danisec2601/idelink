package io.github.danisec2601.idelink

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.danisec2601.idelink.core.BadArgs
import io.github.danisec2601.idelink.core.PathScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisToolsTest {
    private val base = Path.of(System.getProperty("user.dir"), "build", "tmp", "analysis-test")
    private val proj = base.resolve("proj")
    private val outside = base.resolve("outside")
    private lateinit var main: Path
    private lateinit var lib: Path

    private class Fake : AnalysisIde {
        var dumb = false
        var lines = intArrayOf(10, 0, 4)
        var problems = AnalysisIde.Problems(emptyList(), false, true)
        var symbol: AnalysisIde.Symbol? = null
        var found: List<AnalysisIde.Location> = emptyList()
        var more = false
        var structure = AnalysisIde.Structure(emptyList(), null)
        val calls = mutableListOf<String>()

        override fun isDumb(project: String) = dumb
        override fun lineLengths(project: String, file: Path) = lines.also { calls += "lines $file" }
        override fun problems(project: String, file: Path, min: MinSeverity, limit: Int) =
            problems.also { calls += "problems $file $min" }
        override fun resolve(project: String, file: Path, line: Int, column: Int) =
            symbol.also { calls += "resolve $file $line:$column" }
        override fun usages(
            project: String, file: Path, line: Int, column: Int, includeLibraries: Boolean, limit: Int,
        ) = symbol?.let { AnalysisIde.Usages(it, found, more) }.also { calls += "usages $includeLibraries" }
        override fun structure(project: String) = structure.also { calls += "structure $project" }
    }

    private val ide = Fake()
    private var open: List<String>? = null
    private lateinit var tools: Map<String, (JsonObject) -> com.google.gson.JsonElement>

    @BeforeTest fun setUp() {
        base.toFile().deleteRecursively()
        Files.createDirectories(proj.resolve("src"))
        Files.createDirectories(proj.resolve("tests"))
        Files.createDirectories(outside)
        main = Files.writeString(proj.resolve("src/main.py"), "import os\n\nx = 1\n").toRealPath()
        lib = Files.writeString(outside.resolve("lib.py"), "def secret_helper(): pass\n").toRealPath()
        Files.createSymbolicLink(proj.resolve("escape.py"), lib)
        Files.writeString(proj.resolve(".env"), "A=1\n")
        val root = PathScope.realRoot("p", proj.toString())!!
        tools = AnalysisTools(ide, { listOf(root) }, { open }, home = base.toString()).all().associate { def ->
            def.name to { a: JsonObject -> def.schema.check(a, "args"); def.handler(a) }
        }
    }

    private fun call(tool: String, json: String) =
        tools.getValue(tool)(JsonParser.parseString(json).asJsonObject).asJsonObject
    private fun loc(
        file: Path?, line: Int = 3, text: String? = "x = 1",
        inContent: Boolean = true, inLibrary: Boolean = false, library: String? = null,
    ) = AnalysisIde.Location(file, line, text, inContent, inLibrary, library)
    private fun sym(at: AnalysisIde.Location, header: String? = "def f(a, b):", doc: String? = "Does f.") =
        AnalysisIde.Symbol("function", "f", "pkg.mod.f", header, doc, at)

    @Test fun pathsGoThroughPathScopeBeforeTheIdeIsAsked() {
        for (tool in listOf("get_problems", "find_usages", "go_to_definition", "symbol_info")) {
            for (p in listOf("escape.py", lib.toString(), "../outside/lib.py", "nope.py")) {
                val args = """{"path":"$p","line":1,"column":1}""".fixFor(tool)
                assertFailsWith<BadArgs>("$tool $p") { call(tool, args) }
            }
        }
        assertTrue(ide.calls.isEmpty(), ide.calls.toString())
        call("get_problems", """{"path":"src/main.py"}""")
        assertEquals(listOf("problems $main WEAK_WARNING"), ide.calls)
    }

    private fun String.fixFor(tool: String) =
        if (tool == "get_problems") replace(""","line":1,"column":1""", "") else this

    @Test fun positionsAreCheckedAgainstTheDocument() {
        ide.symbol = sym(loc(main))
        for ((l, c) in listOf(4 to 1, 1 to 12, 2 to 2, 3 to 6)) {
            val args = """{"path":"src/main.py","line":$l,"column":$c}"""
            val e = assertFailsWith<BadArgs>("$l:$c") { call("go_to_definition", args) }
            assertTrue(e.message!!.contains("outside"), e.message)
        }
        for ((l, c) in listOf(1 to 1, 1 to 11, 2 to 1, 3 to 5)) {
            call("go_to_definition", """{"path":"src/main.py","line":$l,"column":$c}""")
        }
        assertFailsWith<BadArgs> { call("go_to_definition", """{"path":"src/main.py","line":0,"column":1}""") }
        ide.lines = IntArray(0)
        call("go_to_definition", """{"path":"src/main.py","line":1,"column":1}""")
    }

    @Test fun checkPositionBounds() {
        checkPosition(intArrayOf(3), 1, 4)
        assertFailsWith<BadArgs> { checkPosition(intArrayOf(3), 1, 5) }
        assertFailsWith<BadArgs> { checkPosition(intArrayOf(3), 2, 1) }
        checkPosition(IntArray(0), 1, 1)
        assertFailsWith<BadArgs> { checkPosition(IntArray(0), 1, 2) }
    }

    @Test fun indexingIsAClearErrorNotABlock() {
        ide.dumb = true
        ide.symbol = sym(loc(main))
        for (tool in listOf("find_usages", "go_to_definition", "symbol_info")) {
            val args = """{"path":"src/main.py","line":1,"column":1}"""
            val e = assertFailsWith<IllegalStateException> { call(tool, args) }
            assertEquals(INDEXING, e.message)
        }
        assertTrue(ide.calls.none { it.startsWith("resolve") || it.startsWith("usages") })
    }

    @Test fun problemsAreCappedSortedAndPlain() {
        val items = (1..250).map {
            AnalysisIde.Problem("WEAK WARNING", 251 - it, 2, "<b>bad</b>‮" + "x".repeat(400), "PyUnused")
        }
        ide.problems = AnalysisIde.Problems(items, false, true)
        open = listOf(main.toString())
        val out = call("get_problems", """{"path":"src/main.py","min_severity":"warning"}""")
        val arr = out.getAsJsonArray("problems")
        assertEquals(AnalysisTools.MAX_PROBLEMS, arr.size())
        assertTrue(out.get("truncated").asBoolean)
        val first = arr[0].asJsonObject
        assertEquals(1, first.get("line").asInt)
        assertEquals("weak_warning", first.get("severity").asString)
        val d = first.get("description").asString
        assertTrue(d.startsWith("‹b›bad‹/b›⟦U+202E⟧"), d)
        assertTrue(d.length <= 301, "${d.length}")
        assertEquals("PyUnused", first.get("inspection").asString)
        assertEquals("problems $main WARNING", ide.calls.last())
        assertFailsWith<BadArgs> { call("get_problems", """{"path":"src/main.py","min_severity":"info"}""") }
    }

    @Test fun problemsTruncatedFlagFollowsTheIde() {
        ide.problems = AnalysisIde.Problems(listOf(AnalysisIde.Problem("ERROR", 1, 1, "e", null)), true, true)
        open = listOf(main.toString())
        assertTrue(call("get_problems", """{"path":"src/main.py"}""").get("truncated").asBoolean)
    }

    @Test fun anUnopenedFileWithNoResultsSaysNotAnalysed() {
        val out = call("get_problems", """{"path":"src/main.py"}""")
        assertEquals(AnalysisTools.NOT_ANALYSED, out.get("not_analysed").asString)
        assertFalse(out.has("problems"))
        open = listOf(proj.resolve("src/../src/main.py").toString())
        val o2 = call("get_problems", """{"path":"src/main.py"}""")
        assertEquals(0, o2.getAsJsonArray("problems").size())
        assertTrue(o2.get("analysis_finished").asBoolean)
        assertFalse(o2.has("not_analysed"))
        ide.problems = AnalysisIde.Problems(listOf(AnalysisIde.Problem("ERROR", 1, 1, "e", null)), false, false)
        open = null
        val o3 = call("get_problems", """{"path":"src/main.py"}""")
        assertEquals(1, o3.getAsJsonArray("problems").size())
        assertTrue(o3.has("warning"))
    }

    @Test fun secretNamedFilesKeepTheirDescriptionsBack() {
        val problem = AnalysisIde.Problem("ERROR", 1, 1, "value 'hunter2' looks wrong", null)
        ide.problems = AnalysisIde.Problems(listOf(problem), false, true)
        val out = call("get_problems", """{"path":".env"}""")
        assertFalse(out.toString().contains("hunter2"))
        assertTrue(out.has("descriptions_withheld"))
    }

    @Test fun usagesOutsideTheRootAreCountedNotNamed() {
        ide.symbol = sym(loc(main))
        ide.found = listOf(
            loc(main, 3, "x = 1  # mine"),
            loc(lib, 1, "def secret_helper(): pass"),
            loc(proj.resolve("escape.py"), 1, "via symlink: secret_helper"),
            loc(proj.resolve("src/main.py"), 1, "excluded dir text", inContent = false),
            loc(null, 0, "no file text"),
        )
        val out = call("find_usages", """{"path":"src/main.py","line":3,"column":1,"include_libraries":true}""")
        val s = out.toString()
        for (leak in listOf("secret_helper", "/outside", "lib.py", "escape.py", "excluded dir text", "no file text")) {
            assertFalse(s.contains(leak), "$leak in $s")
        }
        assertEquals(1, out.getAsJsonArray("usages").size())
        assertEquals("src/main.py", out.getAsJsonArray("usages")[0].asJsonObject.get("path").asString)
        assertEquals(4, out.get("usages_outside_project").asInt)
        assertEquals("usages true", ide.calls.last())
        assertEquals("pkg.mod.f", out.getAsJsonObject("symbol").get("qualified_name").asString)
    }

    @Test fun usagesAreCappedAndTheirTextBounded() {
        ide.symbol = sym(loc(main))
        ide.found = (1..260).map { loc(main, it, "y".repeat(500) + " AKIAIOSFODNN7EXAMPLE") }
        val out = call("find_usages", """{"path":"src/main.py","line":3,"column":1}""")
        val arr = out.getAsJsonArray("usages")
        assertEquals(AnalysisTools.MAX_USAGES, arr.size())
        assertTrue(out.get("truncated").asBoolean)
        assertTrue(arr.all { it.asJsonObject.get("text").asString.length <= 201 })
        ide.found = listOf(loc(main, 1, "t AKIAIOSFODNN7EXAMPLE"))
        val o2 = call("find_usages", """{"path":"src/main.py","line":3,"column":1}""")
        assertFalse(o2.toString().contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(o2.get("truncated").asBoolean)
        ide.more = true
        assertTrue(call("find_usages", """{"path":"src/main.py","line":3,"column":1}""").get("truncated").asBoolean)
    }

    @Test fun definitionInsideTheProjectIsNamed() {
        ide.symbol = sym(loc(main, 3, "x = 1‮"))
        val d = call("go_to_definition", """{"path":"src/main.py","line":1,"column":1}""").getAsJsonObject("definition")
        assertEquals("src/main.py", d.get("path").asString)
        assertEquals(3, d.get("line").asInt)
        assertEquals("x = 1⟦U+202E⟧", d.get("text").asString)
    }

    @Test fun definitionInALibraryGivesOnlyItsNameAndQualifiedName() {
        val at = loc(lib, 1, "def secret_helper(): pass", inContent = false, inLibrary = true,
            library = "Python 3.12 ($outside)")
        ide.symbol = sym(at)
        val out = call("go_to_definition", """{"path":"src/main.py","line":1,"column":1}""")
        val d = out.getAsJsonObject("definition")
        assertEquals(setOf("outside_project", "library", "qualified_name"), d.keySet())
        assertEquals("Python 3.12 ([path])", d.get("library").asString)
        assertEquals("pkg.mod.f", d.get("qualified_name").asString)
        assertFalse(out.toString().contains("secret_helper"))
        assertFalse(out.toString().contains(base.toString()))
    }

    @Test fun definitionThroughASymlinkOutOfTheRootIsNotNamed() {
        ide.symbol = sym(loc(proj.resolve("escape.py"), 1, "def secret_helper(): pass"))
        val out = call("go_to_definition", """{"path":"src/main.py","line":1,"column":1}""")
        assertFalse(out.toString().contains("secret_helper"))
        assertFalse(out.toString().contains("escape"))
        assertFalse(out.getAsJsonObject("definition").has("library"))
    }

    @Test fun symbolInfoShowsHeaderForProjectAndLibraryButNotOtherOutsideFiles() {
        ide.symbol = sym(loc(main), header = "def f(a: int) -> <str>:", doc = "Line one.\n\nMore.")
        val s = call("symbol_info", """{"path":"src/main.py","line":1,"column":1}""").getAsJsonObject("symbol")
        assertEquals("function", s.get("kind").asString)
        assertEquals("def f(a: int) -› ‹str›:", s.get("header").asString)
        assertEquals("Line one.\n\nMore.", s.get("docstring").asString)
        assertEquals("src/main.py", s.getAsJsonObject("declared").get("path").asString)

        ide.symbol = sym(loc(lib, inContent = false, inLibrary = true, library = "requests"))
        val l = call("symbol_info", """{"path":"src/main.py","line":1,"column":1}""").getAsJsonObject("symbol")
        assertTrue(l.has("header"))
        assertFalse(l.getAsJsonObject("declared").has("path"))

        ide.symbol = sym(loc(lib, inContent = false), header = "def secret_helper():", doc = "secret doc")
        val o = call("symbol_info", """{"path":"src/main.py","line":1,"column":1}""").getAsJsonObject("symbol")
        assertFalse(o.toString().contains("secret"))
        assertTrue(o.has("source_withheld"))
        assertTrue(o.getAsJsonObject("declared").get("outside_project").asBoolean)
    }

    @Test fun symbolInfoBoundsHeaderAndDocstring() {
        ide.symbol = sym(loc(main), header = "h".repeat(5000), doc = "d".repeat(5000))
        val s = call("symbol_info", """{"path":"src/main.py","line":1,"column":1}""").getAsJsonObject("symbol")
        assertTrue(s.get("header").asString.length <= 501)
        assertTrue(s.get("docstring").asString.length <= 1001)
    }

    @Test fun symbolInfoWithoutADocstringOmitsTheField() {
        ide.symbol = sym(loc(main), header = "func Serve(addr string) error {", doc = null)
        val s = call("symbol_info", """{"path":"src/main.py","line":1,"column":1}""").getAsJsonObject("symbol")
        assertEquals("func Serve(addr string) error {", s.get("header").asString)
        assertFalse(s.has("docstring"), s.toString())
    }

    @Test fun noSymbolIsSaidNotEmpty() {
        val out = call("go_to_definition", """{"path":"src/main.py","line":1,"column":1}""")
        assertTrue(out.has("none"))
        assertTrue(call("find_usages", """{"path":"src/main.py","line":1,"column":1}""").has("none"))
    }

    @Test fun structureNamesOnlyFoldersInsideTheRoot() {
        ide.structure = AnalysisIde.Structure(listOf(
            AnalysisIde.Module("p", listOf(proj, outside), listOf(
                AnalysisIde.Folder(proj.resolve("src"), false), AnalysisIde.Folder(proj.resolve("tests"), true),
                AnalysisIde.Folder(outside, false), AnalysisIde.Folder(proj.resolve("escape.py"), false),
            ), AnalysisIde.Sdk("Python 3.12 virtualenv at ${proj.toRealPath()}/.venv", "Python 3.12.3", "Python SDK")),
        ), AnalysisIde.Sdk("uv (/home/someone/x)", null, "Python SDK"))
        val out = call("project_structure", "{}")
        val s = out.toString()
        assertFalse(s.contains(base.toString()), s)
        assertFalse(s.contains("/home/someone"), s)
        val m = out.getAsJsonArray("modules")[0].asJsonObject
        assertEquals(listOf("."), m.getAsJsonArray("content_roots").map { it.asString })
        assertEquals(listOf("src"), m.getAsJsonArray("source_folders").map { it.asString })
        assertEquals(listOf("tests"), m.getAsJsonArray("test_folders").map { it.asString })
        assertEquals(3, m.get("folders_outside_project").asInt)
        assertEquals("Python 3.12 virtualenv at ./.venv", m.getAsJsonObject("sdk").get("name").asString)
        assertEquals("uv ([path])", out.getAsJsonObject("project_sdk").get("name").asString)
        assertFailsWith<BadArgs> { call("project_structure", """{"project":"other"}""") }
    }

    @Test fun structureReportsANonPythonSdk() {
        ide.structure = AnalysisIde.Structure(listOf(
            AnalysisIde.Module("p", listOf(proj), emptyList(), AnalysisIde.Sdk("Go 1.24.4", "go1.24.4", "Go SDK")),
        ), AnalysisIde.Sdk("corretto-25", "25.0.1", "JavaSDK"))
        val out = call("project_structure", "{}")
        val m = out.getAsJsonArray("modules")[0].asJsonObject.getAsJsonObject("sdk")
        val sdk = listOf("name", "version", "type").map { m.get(it).asString }
        assertEquals(listOf("Go 1.24.4", "go1.24.4", "Go SDK"), sdk)
        assertEquals("JavaSDK", out.getAsJsonObject("project_sdk").get("type").asString)
    }

    @Test fun structureIsCapped() {
        val modules = (1..150).map { AnalysisIde.Module("m$it", emptyList(), emptyList(), null) }
        ide.structure = AnalysisIde.Structure(modules, null)
        val out = call("project_structure", "{}")
        assertEquals(AnalysisTools.MAX_MODULES, out.getAsJsonArray("modules").size())
        assertTrue(out.get("truncated").asBoolean)
    }

    @Test fun maskPathsKeepsProjectPathsRelative() {
        val r = Path.of("/w/proj")
        assertEquals("a [path] b ./x c . d", maskPaths("a /etc/passwd b /w/proj/x c /w/proj d", r, "/w"))
        assertEquals("[path]", maskPaths("~/secret/thing", r, "/w"))
        assertEquals("[path]", maskPaths("/w/projector", r, "/w"))
        assertEquals("[path]", maskPaths("/w/proj/../secret", r, "/w"))
        assertEquals("[path]", maskPaths("~/proj/../secret", r, "/w"))
        assertEquals("Python 3.12 and 3/4", maskPaths("Python 3.12 and 3/4", r, "/w"))
        assertEquals("Python 3.13 (pycharm)", maskPaths("Python 3.13 (pycharm)", r, "/w"))
    }

    @Test fun maskPathsExpandsTildeToHome() {
        val r = Path.of("/w/proj")
        assertEquals("./.venv", maskPaths("~/proj/.venv", r, "/w"))
        assertEquals("./.venv", maskPaths("~/proj/.venv", r, "/w/"))
        assertEquals(".", maskPaths("~/proj", r, "/w"))
        assertEquals("[path]", maskPaths("~/proj/.venv", r, "/elsewhere"))
        assertEquals("[path]", maskPaths("~/proj/.venv", r, null))
        assertEquals("[path]", maskPaths("~/proj/.venv", r, "relative"))
    }

    @Test fun sdkAndLibraryNamesUnderTheProjectStayReadable() {
        Files.createDirectories(proj.resolve(".venv"))
        ide.structure = AnalysisIde.Structure(listOf(
            AnalysisIde.Module("p", listOf(proj), emptyList(), AnalysisIde.Sdk("~/proj/.venv", null, "Python SDK")),
        ), AnalysisIde.Sdk("~/outside", null, "Python SDK"))
        val out = call("project_structure", "{}")
        val module = out.getAsJsonArray("modules")[0].asJsonObject
        assertEquals("./.venv", module.getAsJsonObject("sdk").get("name").asString)
        assertEquals("[path]", out.getAsJsonObject("project_sdk").get("name").asString)

        ide.symbol = sym(loc(lib, 1, null, inContent = false, inLibrary = true, library = "~/proj/.venv"))
        val d = call("go_to_definition", """{"path":"src/main.py","line":1,"column":1}""").getAsJsonObject("definition")
        assertEquals("./.venv", d.get("library").asString)
        ide.symbol = sym(loc(lib, 1, null, inContent = false, inLibrary = true, library = "Python 3.13 (pycharm)"))
        val n = call("go_to_definition", """{"path":"src/main.py","line":1,"column":1}""").getAsJsonObject("definition")
        assertEquals("Python 3.13 (pycharm)", n.get("library").asString)
    }

    @Test fun aSymlinkFromTheProjectOutIsNamedOnlyByItsProjectPath() {
        Files.createSymbolicLink(proj.resolve("venvlink"), outside)
        ide.structure = AnalysisIde.Structure(emptyList(), AnalysisIde.Sdk("$proj/venvlink/bin", null, null))
        val out = call("project_structure", "{}")
        assertEquals("./venvlink/bin", out.getAsJsonObject("project_sdk").get("name").asString)
        assertFalse(out.toString().contains(outside.toString()), out.toString())
    }
}
