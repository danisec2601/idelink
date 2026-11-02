package io.github.danisec2601.idelink.core

import java.nio.file.Files
import java.nio.file.Path

/** Decisions the presentation tools make before anything is shown; pure except the refuse* checks that read files. */
internal object Policy {
    const val MAX_SELECTION = 64 * 1024

    /** Why selection text is withheld (range only), or null if it may be read and scrubbed. */
    fun withholdSelection(carets: Int, chars: Int, editorPath: String, basePath: String?, realRel: String): String? {
        if (carets > 1) return "multi-caret or column selection: range only"
        Scrub.secretBearingAny(editorPath, basePath, realRel)?.let { return "$it (heuristic): range only" }
        if (chars > MAX_SELECTION) return "selection is $chars characters, over the ${MAX_SELECTION / 1024}K-character cap"
        return null
    }

    /**
     * File types whose editor renders content (Markdown/HTML/SVG/notebook previews, diagrams), where text Claude
     * wrote could make the client fetch a URL. HEURISTIC by extension: misses other preview plugins and sniffed types.
     */
    private val rendered = setOf("md", "markdown", "mdx", "html", "htm", "xhtml", "svg", "adoc", "asciidoc",
        "puml", "plantuml", "iuml", "mmd", "mermaid", "ipynb")

    fun refuseRendered(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return if (ext in rendered) "'.$ext' files open with a rendering preview that could fetch remote content; " +
            "idelink won't open them (show_diff shows them as text)" else null
    }

    /** Extensions and names the IDE maps to JSON, JSON5, JSON Lines, YAML or TOML (bundled plugin.xml, 262 and 263). */
    private val schemaExt = setOf("json", "jsonc", "json5", "jsonl", "jsonlines", "ldjson", "ndjson", "har", "jsb2",
        "jsb3", "jshintrc", "postman_collection", "postman_environment", "kubernetes-helm-json",
        "kubernetes-helm-yaml", "yaml", "yml", "toml")
    private val schemaNames = setOf(".babelrc", ".eslintrc", "jest.config", ".ws-context", ".prettierrc",
        ".stylelintrc", "cargo.lock", "cargo.toml.orig", "gopkg.lock", "pipfile", "pdm.lock", "poetry.lock", "uv.lock",
        "babel.config.js", ".babelrc.js", "stylelint.config.js", ".stylelintrc.js", ".eslintrc.js")

    /** JS/TS configs the bundled JSON schema enablers accept: Prettier's by name, webpack's by "webpack" in it. */
    private val schemaJs = Regex("""(?:prettier\.config|\.prettierrc)\.[cm]?[jt]s|.*webpack.*\.[cm]?[jt]sx?""")

    /** Whether the IDE applies JSON schemas to [fileName]. HEURISTIC: misses associations the user added. */
    fun schemaTyped(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.substringAfterLast('.', "") in schemaExt || n in schemaNames || schemaJs.matches(n)
    }

    /**
     * Schema references the IDE downloads on open: `$schema`/`$ref` URLs, yaml-language-server modelines, `#:schema`.
     * HEURISTIC: misses YAML aliases and other escapes (docs/security.md).
     */
    private val remoteSchemaRef = Regex(
        """(?i)(?:(?:\$|\\u0024)(?:schema|ref)["']?\s*+[:=]\s*+["']?\s*+|#:schema\s++)(https?:[^\s"'<>]*+)""")
    private val modeline = Regex("""(?i)yaml-language-server\s*+:\s*+\x24schema=\s*+(https?:[^\s"'<>]*+)""")

    /** The JSON Schema metaschemas, which the IDE bundles rather than downloads. */
    private val metaschema =
        Regex("""https?://json-schema\.org/(?:draft-0[467]/schema|draft/20(?:19-09|20-12)/schema)#?""")

    /** Why [text] may make the IDE fetch a remote schema, or null; only modelines count unless [typed]. */
    fun remoteSchema(text: CharSequence, typed: Boolean = true): String? {
        (if (typed) remoteSchemaRef else modeline).findAll(text).map { it.groupValues[1].replace("\\", "") }
            .firstOrNull { !metaschema.matches(it.lowercase()) } ?: return null
        return "the file references a remote JSON schema (http/https URL) that the IDE would download when it " +
            "opens the file; idelink won't show it"
    }

    /** Throws [BadArgs] if any of [texts] has a [remoteSchema] reference. */
    fun requireNoRemoteSchema(typed: Boolean, vararg texts: String) {
        texts.firstNotNullOfOrNull { remoteSchema(it, typed) }?.let { throw BadArgs(it) }
    }

    /**
     * [refuseRendered] by name, then [remoteSchema] over [real]'s content. A [typed] file must fit the scan and be
     * UTF-8 or carry a BOM; an untyped one is scanned for modelines in its first [Texts.MAX_FILE] bytes.
     */
    fun refuseOpen(real: Path, typed: Boolean = schemaTyped(real.fileName.toString())): String? =
        refuseRendered(real.fileName.toString())
            ?: refuseContent(Files.newInputStream(real).use { it.readNBytes(Texts.MAX_FILE + 1) }, typed)

    private fun refuseContent(b: ByteArray, typed: Boolean): String? = when {
        !typed -> remoteSchema(String(b, 0, minOf(b.size, Texts.MAX_FILE), Charsets.ISO_8859_1), false)
        b.size > Texts.MAX_FILE -> "the file is over ${Texts.MAX_FILE / 1024} KiB, more than idelink scans for a " +
            "remote JSON schema reference; idelink won't show it"
        else -> when (val text = schemaText(b)) {
            null -> "the file has NUL bytes but no UTF-16/32 byte-order mark, so idelink can't scan it for a remote " +
                "JSON schema reference; idelink won't show it"
            else -> remoteSchema(text, true)
        }
    }

    /** Longest mark first: a UTF-32LE BOM starts with the UTF-16LE one. */
    private val boms = listOf(charset("UTF-32BE"), charset("UTF-32LE"), Charsets.UTF_16BE, Charsets.UTF_16LE)
        .map { "\uFEFF".toByteArray(it) to it }

    /** [b] decoded by its UTF-16/32 BOM; else byte per char (ASCII survives), or null if it has NUL bytes. */
    private fun schemaText(b: ByteArray): String? {
        val (mark, cs) = boms.firstOrNull { (m, _) -> b.size >= m.size && m.indices.all { b[it] == m[it] } }
            ?: return if (0.toByte() in b) null else String(b, Charsets.ISO_8859_1)
        return String(b, mark.size, b.size - mark.size, cs)
    }

    /**
     * Why [r], sent by Claude earlier, must not open now (re-run just before a deferred open), or null.
     * [since] completes "… since <since>".
     */
    fun refuseDeferred(r: PathScope.Resolved, typed: Boolean, since: String): String? =
        if (!PathScope.recheck(r)) "the file moved, became a symlink or is gone since $since"
        else runCatching { refuseOpen(r.real, typed) }
            .fold({ why -> why?.let { "the file changed since $since: $it" } }, { "the file can't be read" })

    /** show_diff takes exactly one source. */
    fun diffSource(proposed: String?, ref: String?) {
        if ((proposed == null) == (ref == null)) throw BadArgs("give exactly one of proposed_text or from_ref")
    }
}
