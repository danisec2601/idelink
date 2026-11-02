package io.github.danisec2601.idelink.core

import io.github.danisec2601.idelink.shared.Plain
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Launch options that put someone else between Claude and the user: extra MCP servers, a redirected model API or a
 * proxy. HEURISTIC: sees only the exec-time command line and environment, not settings files or later changes.
 */
internal object LaunchFlags {
    private const val MAX_READ = 1 shl 20
    private const val MAX_LINE = 300
    private const val MAX_ERROR = 60
    private val redirectKey =
        Regex("""(?:ANTHROPIC|CLAUDE_CODE)_[A-Z0-9_]*BASE_URL|(?i:(?:CLAUDE_CODE_)?(?:HTTPS?|ALL)_PROXY)""")

    /** Warning lines for [claudePid], whose environment is read along with its child [bridgePid]'s. */
    fun read(claudePid: Int, bridgePid: Int): List<String> = try {
        warnings(proc(claudePid, "cmdline"), listOf(proc(claudePid, "environ"), proc(bridgePid, "environ")))
    } catch (e: IOException) {
        listOf("⚠ couldn't read how this Claude was started (${Plain.text(e.javaClass.simpleName, MAX_ERROR)})")
    }

    private fun proc(pid: Int, what: String) =
        Files.newInputStream(Path.of("/proc/$pid/$what")).use { it.readNBytes(MAX_READ) }

    /** Warnings from a NUL-separated [cmdline] and [environs]; values are reduced to a URL's host:port. */
    fun warnings(cmdline: ByteArray, environs: List<ByteArray>): List<String> {
        val out = mutableListOf<String>()
        if (nul(cmdline).any { it == "--mcp-config" || it.startsWith("--mcp-config=") }) {
            out += "⚠ started with --mcp-config: it may use MCP servers from a file outside your usual configuration"
        }
        val seen = sortedMapOf<String, String>()
        for (entry in environs.flatMap(::nul)) {
            val key = entry.substringBefore('=')
            if ('=' in entry && redirectKey.matches(key) && key !in seen) {
                seen[key] = hostPort(entry.substringAfter('='))
            }
        }
        seen.forEach { (k, v) -> out += "⚠ $k is set ($v): model or network traffic may not go straight to Anthropic" }
        return out.map { Plain.text(it, MAX_LINE) }
    }

    private fun nul(b: ByteArray) = String(b, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }

    private fun hostPort(v: String): String {
        val t = v.trim()
        val u = runCatching { URI(if ("://" in t) t else "http://$t") }.getOrNull()
        val host = u?.host ?: return "not a URL"
        return if (u.port >= 0) "$host:${u.port}" else host
    }
}
