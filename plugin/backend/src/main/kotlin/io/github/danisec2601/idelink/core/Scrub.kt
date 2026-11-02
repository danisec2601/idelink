package io.github.danisec2601.idelink.core

import java.security.SecureRandom

/** Secret protection for selection and terminal text. Both halves are HEURISTICS: listed names and shapes only. */
internal object Scrub {
    /**
     * File names that usually hold secrets; selection text from these is never returned. Matched
     * case-insensitively against every path component. Misses secrets in ordinarily named files (config.yaml).
     */
    private val secretNames = listOf(
        """\.env.*""", """.*\.pem""", """.*\.key""", """.*\.p12""", """.*\.pfx""", """id_rsa.*""", """id_ed25519.*""",
        """.*secret.*""", """.*credential.*""", """\.netrc""", """\.pgpass""",
        """id_ecdsa.*""", """id_dsa.*""", """.*\.ppk""", """.*\.jks""", """.*\.kdbx""", """.*\.tfvars""", """.*\.tfstate.*""",
        """kubeconfig.*""", """\.npmrc""", """\.pypirc""", """\.htpasswd""", """\.vault-token""", """.*\.gpg""",
        // <name>.env files: docker env_file, prod.env, prod.env.local
        """.+\.env""", """.+\.env\..*""",
    ).map(::Regex)

    fun secretBearingName(relPath: String): String? {
        for (part in relPath.split('/')) {
            val lc = part.lowercase()
            secretNames.firstOrNull { it.matches(lc) }?.let { return "'$part' matches the secret-bearing name pattern ${it.pattern}" }
        }
        return null
    }

    /**
     * Checks every name the file goes by: the path the editor shows (a symlink like .env -> config/app.txt) and
     * the real path, each relative to the project root. Lexical for the editor path, so ".." isn't resolved there.
     */
    fun secretBearingAny(editorPath: String, basePath: String?, realRel: String): String? {
        val editorRel = if (basePath != null && editorPath.startsWith(basePath.trimEnd('/') + "/"))
            editorPath.substring(basePath.trimEnd('/').length + 1) else editorPath.substringAfterLast('/')
        return secretBearingName(editorRel) ?: secretBearingName(realRel)
    }

    /**
     * Shapes that are scrubbed. Misses bare hex/base64/UUID secrets, passwords, user:pass@ URLs, PEM bodies without
     * BEGIN, a JWT glued to a word by '-' and unlisted providers (OpenAI sk-proj-, Google GOCSPX-).
     */
    private val patterns: List<Pair<String, Regex>> = listOf(
        "private-key" to Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----[\s\S]*?(?:-----END [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----|\z)"""),
        "aws-key" to Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""),
        "github-token" to Regex("""\bgh[pousr]_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{20,}"""),
        "slack-token" to Regex("""\bxox[abposr]-[A-Za-z0-9-]{10,}|\bxapp-[A-Za-z0-9-]{10,}"""),
        "google-key" to Regex("""\bAIza[0-9A-Za-z_-]{30,}"""),
        "anthropic-key" to Regex("""\bsk-ant-[A-Za-z0-9_-]{10,}"""),
        "age-key" to Regex("""AGE-SECRET-KEY-1[0-9A-Z]{20,}"""),
        // Starts only where a run begins: a \b start would rescan the rest of an "ey-ey-…" run from every "ey" in it.
        "jwt" to Regex("""(?<![A-Za-z0-9_-])ey[A-Za-z0-9_-]{8,}+\.ey[A-Za-z0-9_-]{8,}+\.[A-Za-z0-9_-]{8,}+"""),
        "authorization" to Regex("""(?i)(?<=\bauthorization)(["']?\s*[:=]\s*)[^\r\n]+"""),
        "bearer" to Regex("""(?i)(?<=\bbearer\s)\s*[A-Za-z0-9._~+/=-]{8,}"""),
    )

    data class Result(val text: String, val redactions: Map<String, Int>)

    private val rng = SecureRandom()

    /** Each distinct secret gets a fresh random placeholder per call, so placeholders can't be correlated across reads. */
    fun scrub(text: String): Result {
        val byValue = HashMap<String, String>()
        val counts = LinkedHashMap<String, Int>()
        var s = text
        for ((kind, re) in patterns) {
            s = re.replace(s) { m ->
                counts.merge(kind, 1, Int::plus)
                val lead = if (kind == "authorization") m.groupValues[1] else ""
                lead + byValue.getOrPut(m.value) { "[REDACTED:$kind:%08x]".format(rng.nextInt()) }
            }
        }
        return Result(s, counts)
    }
}
