package io.github.danisec2601.idelink.core

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Resolves Claude-supplied paths against project roots that come only from the IDE, comparing realpaths
 * (all symlinks followed), so a symlink that points out of the project counts as outside.
 */
internal object PathScope {
    data class Root(val project: String, val root: Path)
    /** [rel] is the absolute realpath when [outside]. */
    data class Resolved(
        val project: String, val root: Path, val real: Path, val rel: String, val outside: Boolean = false,
    )

    sealed class Located {
        data class Inside(val resolved: Resolved) : Located()
        data class Outside(val resolved: Resolved) : Located()
    }

    fun realRoot(project: String, basePath: String?): Root? {
        val b = basePath ?: return null
        return runCatching { Root(project, Path.of(b).toRealPath()) }.getOrNull()
    }

    /**
     * [projectName] picks the root; otherwise an absolute path picks the root containing it, and a relative path
     * must resolve inside exactly one open project.
     */
    fun resolve(roots: List<Root>, input: String, projectName: String? = null): Resolved =
        when (val l = locate(roots, input, projectName)) {
            is Located.Inside -> l.resolved
            is Located.Outside -> throw BadArgs(NOT_INSIDE)
        }

    /** Like [resolve], but an existing regular file outside every root comes back as [Located.Outside]. */
    @Suppress("ThrowsCount") // one refusal per reason, each with its own message
    fun locate(roots: List<Root>, input: String, projectName: String? = null): Located {
        if (input.isEmpty() || input.length > MAX_PATH || input.contains('\u0000')) throw BadArgs("bad path")
        val p = try { Path.of(input) } catch (e: InvalidPathException) { throw BadArgs("bad path") }
        val candidates = if (projectName != null) {
            roots.filter { it.project == projectName }
                .ifEmpty { throw BadArgs("no open project named '${projectName.take(64)}'") }
                .also { if (it.size > 1) throw BadArgs("several open projects are named '${projectName.take(64)}'") }
        } else roots
        if (candidates.isEmpty()) throw BadArgs("no open projects")
        val hits = candidates.mapNotNull { under(it, p) }
        return when {
            hits.size == 1 -> Located.Inside(hits[0])
            hits.isEmpty() -> Located.Outside(outside(roots, candidates, p))
            else -> throw BadArgs("path resolves in several projects (${hits.joinToString { it.project }}); pass project")
        }
    }

    @Suppress("ThrowsCount") // one refusal per reason, each with its own message
    private fun outside(roots: List<Root>, candidates: List<Root>, p: Path): Resolved {
        val home = candidates.singleOrNull()
        val joined = if (p.isAbsolute) p else (home ?: throw BadArgs(NOT_INSIDE)).root.resolve(p)
        val real = runCatching { joined.toRealPath() }.getOrNull() ?: throw BadArgs("path does not exist")
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) throw BadArgs("path is not a regular file")
        roots.firstOrNull { real.startsWith(it.root) }
            ?.let { throw BadArgs("path is in project '${it.project}'; pass that project") }
        home ?: throw BadArgs("path is outside every open project; pass project to say which IDE window shows it")
        return Resolved(home.project, home.root, real, real.toString(), outside = true)
    }

    private const val NOT_INSIDE = "path is not an existing file inside an open project root"
    private const val MAX_PATH = 4096

    fun under(root: Root, p: Path): Resolved? {
        val joined = if (p.isAbsolute) p else root.root.resolve(p)
        val real = runCatching { joined.toRealPath() }.getOrNull() ?: return null
        if (!real.startsWith(root.root)) return null
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return null
        return Resolved(root.project, root.root, real, root.root.relativize(real).toString())
    }

    /** [p]'s real path relative to [root] ("." for the root itself) if it is a directory inside it, else null. */
    fun dirUnder(root: Root, p: Path): String? =
        runCatching { p.toRealPath() }.getOrNull()
            ?.takeIf { it.startsWith(root.root) && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            ?.let { root.root.relativize(it).toString().ifEmpty { "." } }

    /** Re-check before use: [r] is still its own realpath (no symlink swapped in) and, unless outside, in its root. */
    fun recheck(r: Resolved): Boolean =
        runCatching { r.real.toRealPath() == r.real && (r.outside || r.real.startsWith(r.root)) }.getOrDefault(false)
}
