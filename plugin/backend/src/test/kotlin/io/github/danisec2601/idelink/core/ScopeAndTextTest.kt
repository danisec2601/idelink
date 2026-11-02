package io.github.danisec2601.idelink.core

import io.github.danisec2601.idelink.shared.Invisible
import io.github.danisec2601.idelink.shared.Plain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathScopeTest {
    private val base = Path.of(System.getProperty("user.home"), "dev/tmp/idelink-test/scope")
    private val proj = base.resolve("proj")
    private val other = base.resolve("other")
    private lateinit var roots: List<PathScope.Root>

    @BeforeTest
    fun up() {
        base.toFile().deleteRecursively()
        Files.createDirectories(proj.resolve("sub")); Files.createDirectories(other)
        Files.writeString(proj.resolve("a.txt"), "a\n"); Files.writeString(proj.resolve("sub/b.txt"), "b\n")
        Files.writeString(other.resolve("secret.txt"), "x\n"); Files.writeString(other.resolve("a.txt"), "o\n")
        Files.createSymbolicLink(proj.resolve("out-link"), other.resolve("secret.txt"))
        Files.createSymbolicLink(proj.resolve("out-dir"), other)
        Files.createSymbolicLink(proj.resolve("in-link"), proj.resolve("sub/b.txt"))
        roots = listOf(PathScope.realRoot("proj", proj.toString())!!)
    }

    private fun bad(p: String, r: List<PathScope.Root> = roots, project: String? = null) =
        assertFailsWith<BadArgs>(p) { PathScope.resolve(r, p, project) }

    @Test fun relativeAndAbsoluteInsideResolve() {
        assertEquals("sub/b.txt", PathScope.resolve(roots, "sub/b.txt").rel)
        assertEquals("a.txt", PathScope.resolve(roots, proj.resolve("a.txt").toString()).rel)
        assertEquals("a.txt", PathScope.resolve(roots, "sub/../a.txt").rel)
    }

    @Test fun escapesAreRefused() {
        bad("../other/secret.txt"); bad(other.resolve("secret.txt").toString()); bad("out-link"); bad("out-dir/secret.txt")
        bad("/etc/passwd"); bad("sub"); bad("nope.txt"); bad(""); bad("a\u0000.txt")
    }

    @Test fun aSymlinkThatStaysInsideResolvesToItsTarget() {
        assertEquals("sub/b.txt", PathScope.resolve(roots, "in-link").rel)
    }

    @Test fun projectSelectionAndAmbiguity() {
        val two = roots + PathScope.realRoot("other", other.toString())!!
        bad("a.txt", two)
        assertEquals("other", PathScope.resolve(two, "a.txt", "other").project)
        bad("a.txt", two, "nosuch")
        bad("../other/secret.txt", two, "proj")
    }

    @Test fun recheckNoticesASymlinkSwap() {
        val r = PathScope.resolve(roots, "a.txt")
        assertTrue(PathScope.recheck(r))
        Files.delete(proj.resolve("a.txt")); Files.createSymbolicLink(proj.resolve("a.txt"), other.resolve("secret.txt"))
        assertFalse(PathScope.recheck(r))
    }
}

class PlainAndInvisibleTest {
    @Test fun noAngleBracketSurvivesSoSwingCantSwitchToHtml() {
        val s = Plain.text("<html><img src='http://x/'>hi</html>", 200)
        assertFalse(s.contains('<') || s.contains('>'), s)
        assertTrue(s.startsWith("‹html›"), s)
    }

    @Test fun invisiblesAreMadeVisibleAndNewlinesFlattened() {
        assertEquals("a⟦U+202E⟧b⟦U+200B⟧c d", Plain.text("a‮b​c\nd", 100))
        assertEquals("x\ny", Plain.text("x\r\ny", 100, multiline = true))
        assertEquals("⟦U+0007⟧", Plain.text("\u0007", 100))
    }

    @Test fun truncationIsMarked() {
        assertEquals("abc…", Plain.text("abcdef", 3))
    }

    @Test fun scanFindsBidiZeroWidthTagsAndBom() {
        val text = "ok\nif x‮ {\n​z﻿ " + String(Character.toChars(0xE0041)) + "⁦"
        val hits = Invisible.scan(text)
        assertEquals(listOf(0x202E, 0x200B, 0xFEFF, 0xE0041, 0x2066), hits.map { it.codePoint })
        assertEquals(2 to 5, hits[0].line to hits[0].col)
        assertEquals(3 to 1, hits[1].line to hits[1].col)
        assertEquals(5, Invisible.count(text))
    }

    @Test fun cleanCodeHasNoHits() {
        assertEquals(0, Invisible.count("def f(x):\n\treturn x  # é ü 日本\r\n"))
    }

    @Test fun homoglyphsAreAKnownMiss() {
        // Documented limit of the heuristic: Cyrillic 'а' looks like Latin 'a' and is not flagged.
        assertEquals(0, Invisible.count("pаypal"))
    }

    @Test fun warningSummarises() {
        assertNull(Texts.invisibleWarning("clean"))
        assertTrue(Texts.invisibleWarning("a‮b")!!.contains("1 bidi/invisible character: 1:2 U+202E RIGHT-TO-LEFT OVERRIDE"))
    }
}

class SchemaTest {
    private val s = Schema.Obj(mapOf(
        "path" to Schema.Str(maxLength = 5),
        "line" to Schema.Integer(max = 10),
        "items" to Schema.Arr(Schema.Obj(mapOf("n" to Schema.Str()), required = setOf("n")), maxItems = 2),
    ), required = setOf("path"))

    private fun ok(json: String) = s.check(com.google.gson.JsonParser.parseString(json), "args")
    private fun bad(json: String, msg: String) {
        val e = assertFailsWith<BadArgs>(json) { ok(json) }
        assertTrue(e.message!!.contains(msg), "${e.message} !~ $msg")
    }

    @Test fun validPasses() = ok("""{"path":"a","line":3,"items":[{"n":"x"}]}""")
    @Test fun unknownKey() = bad("""{"path":"a","evil":1}""", "unknown key 'evil'")
    @Test fun missingRequired() = bad("""{"line":1}""", "args.path is required")
    @Test fun wrongType() = bad("""{"path":1}""", "args.path must be a string")
    @Test fun tooLong() = bad("""{"path":"abcdef"}""", "longer than 5")
    @Test fun fractionalInteger() = bad("""{"path":"a","line":1.5}""", "must be an integer")
    @Test fun outOfRange() = bad("""{"path":"a","line":11}""", "must be in 1..10")
    @Test fun nestedItemErrorsNameTheItem() = bad("""{"path":"a","items":[{"n":"x"},{"m":1}]}""", "args.items[1] has unknown key 'm'")
    @Test fun emptyAndOversizeArrays() {
        bad("""{"path":"a","items":[]}""", "must not be empty")
        bad("""{"path":"a","items":[{"n":"1"},{"n":"2"},{"n":"3"}]}""", "more than 2")
    }
    @Test fun nonObjectArgs() = bad("""[1]""", "args must be an object")
}

class TextsTest {
    private val repo = Path.of(System.getProperty("user.home"), "dev/tmp/idelink-test/gitrepo")

    private fun git(vararg a: String) {
        val p = ProcessBuilder("git", "-C", repo.toString(), "-c", "user.name=t", "-c", "user.email=t@t", *a)
            .redirectErrorStream(true).start()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, p.exitValue(), String(p.inputStream.readAllBytes()))
    }

    @BeforeTest fun up() {
        repo.toFile().deleteRecursively(); Files.createDirectories(repo)
        git("init", "-q"); Files.writeString(repo.resolve("f.txt"), "old\n"); git("add", "f.txt"); git("commit", "-qm", "c1")
        Files.writeString(repo.resolve("f.txt"), "new\n")
    }

    @Test fun blobAtRevIsTheCommittedContent() {
        assertEquals("old\n", Texts.gitBlob(repo, "HEAD", "f.txt"))
        assertEquals("new\n", Texts.readFile(repo.resolve("f.txt")).text)
    }

    @Test fun optionAndPathSyntaxInRefsIsRefused() {
        for (r in listOf("--output=/tmp/x", "-p", "HEAD:other", "a..b", "HEAD f", "", ":0")) {
            assertFailsWith<BadArgs>(r) { Texts.gitBlob(repo, r, "f.txt") }
        }
    }

    @Test fun aProjectRootBelowTheRepoTopLevelGetsItsOwnFile() {
        Files.createDirectories(repo.resolve("sub")); Files.writeString(repo.resolve("sub/f.txt"), "inner\n")
        git("add", "sub/f.txt"); git("commit", "-qm", "c2")
        assertEquals("inner\n", Texts.gitBlob(repo.resolve("sub"), "HEAD", "f.txt"))
    }

    @Test fun missingRevIsAnErrorNotEmpty() {
        assertFailsWith<BadArgs> { Texts.gitBlob(repo, "nosuchbranch", "f.txt") }
    }

    @Test fun hashIsOfTheBytesOnDisk() {
        assertEquals(Texts.sha256("new\n".toByteArray()), Texts.readFile(repo.resolve("f.txt")).sha256)
    }

    /** HEAD:f.txt's blob is missing and the promisor remote's ext:: url touches [marker] if it is ever fetched. */
    private fun promisorRepo(marker: Path) {
        val blob = ProcessBuilder("git", "-C", repo.toString(), "rev-parse", "HEAD:f.txt").start()
            .inputStream.readAllBytes().let { String(it).trim() }
        Files.delete(repo.resolve(".git/objects/${blob.take(2)}/${blob.drop(2)}"))
        git("config", "core.repositoryformatversion", "1"); git("config", "extensions.partialClone", "origin")
        git("config", "remote.origin.promisor", "true"); git("config", "protocol.ext.allow", "always")
        git("config", "remote.origin.url", "ext::sh -c touch% $marker")
    }

    @Test fun aPromisorRemoteIsNeverFetched() {
        val marker = repo.resolveSibling("lazy-fetch-ran"); Files.deleteIfExists(marker)
        promisorRepo(marker)
        assertFailsWith<BadArgs> { Texts.gitBlob(repo, "HEAD", "f.txt") }
        assertFalse(Files.exists(marker), "cat-file lazily fetched from the promisor remote")
    }

    @Test fun gitThatIgnoresTheLazyFetchEnvRefusesInsteadOfFetching() {
        val marker = repo.resolveSibling("old-git-fetch-ran"); Files.deleteIfExists(marker)
        promisorRepo(marker)
        val real = ProcessBuilder("sh", "-c", "command -v git").start().inputStream.readAllBytes()
            .let { String(it).trim() }
        val oldGit = repo.resolveSibling("git-2.43")
        Files.writeString(oldGit, "#!/bin/sh\nfor a; do [ \"\$a\" = --no-lazy-fetch ] && " +
            "{ echo \"unknown option: \$a\" >&2; exit 129; }; done\nexec env -u GIT_NO_LAZY_FETCH $real \"\$@\"\n")
        oldGit.toFile().setExecutable(true)
        val e = assertFailsWith<BadArgs> { Texts.gitBlob(repo, "HEAD", "f.txt", oldGit.toString()) }
        assertFalse(Files.exists(marker), "an old git lazily fetched from the promisor remote")
        assertEquals("from_ref needs git 2.45 or newer", e.message)
    }

    @Test fun invalidUtf8IsRefused() {
        Files.write(repo.resolve("bin"), byteArrayOf(0x61, 0xC3.toByte(), 0x28))
        assertFailsWith<BadArgs> { Texts.readFile(repo.resolve("bin")) }
    }
}

class PolicyTest {
    @Test fun selectionWithholding() {
        assertNull(Policy.withholdSelection(1, 10, "/p/a.py", "/p", "a.py"))
        assertTrue(Policy.withholdSelection(2, 10, "/p/a.py", "/p", "a.py")!!.contains("multi-caret"))
        assertTrue(Policy.withholdSelection(1, Policy.MAX_SELECTION + 1, "/p/a.py", "/p", "a.py")!!.contains("cap"))
        assertNull(Policy.withholdSelection(1, Policy.MAX_SELECTION, "/p/a.py", "/p", "a.py"))
        assertTrue(Policy.withholdSelection(1, 10, "/p/.env", "/p", "conf/x")!!.contains("heuristic"))
    }

    @Test fun renderedTypesAreRefused() {
        for (n in listOf("README.md", "x.MD", "page.html", "i.svg", "n.ipynb", "d.puml")) assertTrue(Policy.refuseRendered(n) != null, n)
        for (n in listOf("main.py", "Makefile", "md", "notes.txt", "x.kt")) assertNull(Policy.refuseRendered(n), n)
    }

    @Test fun remoteSchemaReferencesAreRefused() {
        for (t in listOf("{\"\$schema\": \"https://x.example/s.json\"}", "{\n  \"\$ref\" : 'http://x.example/a'}",
                "# yaml-language-server: \$schema=https://x.example/s", "\$schema: https://x.example/s",
                "#:schema https://x.example/t.json", "{\"\\u0024schema\":\"https:\\/\\/x.example\"}",
                "{\"\$SCHEMA\":\"HTTPS://x.example\"}", "{\"\$schema\":\"https://json-schema.org@x.example/\"}",
                "{\"\$schema\":\"https://json-schema.org/draft-07/schema\", \"\$ref\":\"https://x.example/\"}")) {
            assertTrue(Policy.remoteSchema(t) != null, t)
        }
        for (t in listOf("{\"\$schema\": \"https://json-schema.org/draft-07/schema#\"}", "{\"\$ref\": \"#/defs/a\"}",
                "{\"\$schema\": \"./local.json\"}", "url = \"https://x.example\"", "")) {
            assertNull(Policy.remoteSchema(t), t)
        }
    }

    @Test fun eitherDiffSideWithARemoteSchemaIsRefused() {
        val bad = "{\"\$schema\": \"https://x.example\"}"
        Policy.requireNoRemoteSchema(true, "a", "b")
        assertFailsWith<BadArgs> { Policy.requireNoRemoteSchema(true, "a", bad) }
        assertFailsWith<BadArgs> { Policy.requireNoRemoteSchema(true, bad, "b") }
        Policy.requireNoRemoteSchema(false, bad, "b")
        val modeline = "# yaml-language-server: \$schema=http://x"
        assertFailsWith<BadArgs> { Policy.requireNoRemoteSchema(false, modeline, "b") }
    }

    @Test fun schemaTypesAreTheOnesTheIdeMapsToJsonYamlOrToml() {
        for (n in listOf("a.json", "a.JSONC", "a.json5", "a.jsonl", "c.yml", "c.yaml", "p.toml", ".babelrc",
                "Cargo.lock", "Pipfile", ".eslintrc.js", "prettier.config.mjs", ".prettierrc.ts", "webpack.config.js",
                "x.har")) {
            assertTrue(Policy.schemaTyped(n), n)
        }
        for (n in listOf("run.sh", "ScopeAndTextTest.kt", "ApprovalTest.kt", "main.py", "json", "webpack.md", "a.js")) {
            assertFalse(Policy.schemaTyped(n), n)
        }
    }

    @Test fun onlySchemaTypesAreScannedForEmbeddedSchemas() {
        val config = "{\"\$schema\": \"https://opencode.ai/config.json\"}"
        assertNull(Policy.remoteSchema(config, typed = false))
        assertTrue(Policy.remoteSchema(config, typed = true) != null)
        val modeline = "x\n# yaml-language-server: \$schema=https://x.example/s\n"
        assertTrue(Policy.remoteSchema(modeline, typed = false) != null)
        assertNull(Policy.remoteSchema("#:schema https://x.example/t.json", typed = false))
    }

    @Test fun openReadsTheFileForRemoteSchemas() {
        val dir = Path.of(System.getProperty("user.home"), "dev/tmp/idelink-test/policy-rsec")
        dir.toFile().deleteRecursively(); Files.createDirectories(dir)
        val bad = dir.resolve("conf.json")
        Files.writeString(bad, " ".repeat(1000) + "{\"\$schema\":\"https://x.example/a\"}")
        val ok = dir.resolve("ok.json"); Files.writeString(ok, "{\"a\": 1}")
        assertTrue(Policy.refuseOpen(bad)!!.contains("remote JSON schema"))
        assertNull(Policy.refuseOpen(ok))
        assertTrue(Policy.refuseOpen(dir.resolve("README.md").also { Files.writeString(it, "x") }) != null)
        val script = dir.resolve("setup.sh")
        Files.writeString(script, "cat > opencode.json <<'E'\n{\"\$schema\": \"https://opencode.ai/config.json\"}\nE\n")
        assertNull(Policy.refuseOpen(script))
        Files.writeString(script, "# yaml-language-server: \$schema=https://x.example/s\n")
        assertTrue(Policy.refuseOpen(script) != null)
    }

    @Test fun aSchemaTypeLargerThanTheScanIsRefused() {
        val dir = Path.of(System.getProperty("user.home"), "dev/tmp/idelink-test/policy-size")
        dir.toFile().deleteRecursively(); Files.createDirectories(dir)
        val tail = "{\"\$schema\":\"https://x.example/a\"}"
        val big = dir.resolve("big.json").also { Files.writeString(it, " ".repeat(Texts.MAX_FILE) + tail) }
        assertTrue(assertNotNull(Policy.refuseOpen(big)).contains("KiB"))
        val fits = dir.resolve("fits.json").also { Files.writeString(it, " ".repeat(Texts.MAX_FILE - 2) + "{}") }
        assertNull(Policy.refuseOpen(fits))
        val log = dir.resolve("big.log").also { Files.writeString(it, " ".repeat(Texts.MAX_FILE) + tail) }
        assertNull(Policy.refuseOpen(log))
    }

    @Test fun byteOrderMarkedSchemaTypesAreDecodedBeforeTheScan() {
        val dir = Path.of(System.getProperty("user.home"), "dev/tmp/idelink-test/policy-bom")
        dir.toFile().deleteRecursively(); Files.createDirectories(dir)
        val bad = "{\"\$schema\":\"https://x.example/a\"}"
        val boms = mapOf("UTF-16LE" to intArrayOf(0xFF, 0xFE), "UTF-16BE" to intArrayOf(0xFE, 0xFF),
            "UTF-32LE" to intArrayOf(0xFF, 0xFE, 0, 0), "UTF-32BE" to intArrayOf(0, 0, 0xFE, 0xFF),
            "UTF-8" to intArrayOf(0xEF, 0xBB, 0xBF))
        for ((cs, bom) in boms) {
            val f = dir.resolve("c.json")
            fun write(t: String) = Files.write(f, bom.map { it.toByte() }.toByteArray() + t.toByteArray(charset(cs)))
            write(bad); assertTrue(assertNotNull(Policy.refuseOpen(f), cs).contains("remote JSON schema"), cs)
            write("{\"a\": 1}"); assertNull(Policy.refuseOpen(f), cs)
        }
        val noBom = dir.resolve("n.json").also { Files.write(it, "{\"a\": 1}".toByteArray(Charsets.UTF_16LE)) }
        assertTrue(assertNotNull(Policy.refuseOpen(noBom)).contains("NUL"))
    }

    @Test fun aDeferredOpenRechecksTheFileAsItIsNow() {
        val dir = Path.of(System.getProperty("user.home"), "dev/tmp/idelink-test/policy-deferred").toAbsolutePath()
        dir.toFile().deleteRecursively(); Files.createDirectories(dir)
        val root = dir.toRealPath()
        val f = root.resolve("c.json").also { Files.writeString(it, "{\"a\": 1}") }
        val r = PathScope.Resolved("p", root, f, "c.json")
        assertNull(Policy.refuseDeferred(r, true, "Claude sent it"))
        Files.writeString(f, "{\"\$schema\":\"https://x.example/a\"}")
        val why = assertNotNull(Policy.refuseDeferred(r, true, "Claude sent it"))
        assertTrue(why.startsWith("the file changed since Claude sent it") && "remote JSON schema" in why, why)
        Files.delete(f)
        assertTrue(assertNotNull(Policy.refuseDeferred(r, true, "Claude sent it")).contains("gone"))
    }

    @Test fun remoteSchemaScanIsLinear() {
        for (unit in listOf("\$schema", "\$schema:", "\$schema: ", " ", "#:schema ", "\$schema\": \"http")) {
            val input = unit.repeat(Texts.MAX_FILE / unit.length).let { it + " ".repeat(Texts.MAX_FILE - it.length) }
            val t0 = System.nanoTime()
            Policy.remoteSchema(input); Policy.remoteSchema(unit + " ".repeat(Texts.MAX_FILE))
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 1500, "'$unit': $ms ms")
        }
    }

    @Test fun diffNeedsExactlyOneSource() {
        assertFailsWith<BadArgs> { Policy.diffSource(null, null) }
        assertFailsWith<BadArgs> { Policy.diffSource("x", "HEAD") }
        Policy.diffSource("x", null); Policy.diffSource(null, "HEAD")
    }
}
