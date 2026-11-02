package io.github.danisec2601.idelink

import java.nio.file.Path

private val pathLike = Regex("""(?<![\w.~/-])(?:~/|/)[^\s()\[\]{}'",;]*""")

/**
 * Replaces absolute and ~/ paths in [s] with "[path]", or with a project-relative form if under [root], expanding ~
 * to [home]. HEURISTIC: misses relative paths, paths containing spaces or brackets, and Windows paths.
 */
internal fun maskPaths(s: String, root: Path, home: String?): String =
    pathLike.replace(s) { m -> absolute(m.value, home)?.let { relativeToRoot(it, root) } ?: "[path]" }

/** [p] with a leading ~ replaced by [home], normalized; null if [home] can't stand in for ~. */
private fun absolute(p: String, home: String?): Path? {
    val abs = if (!p.startsWith("~/")) p else home?.takeIf { it.startsWith("/") }?.let { it.trimEnd('/') + p.drop(1) }
    return abs?.let { runCatching { Path.of(it).normalize() }.getOrNull() }
}

/** [path] as "." or "./rel" if it is under [root] lexically or once symlinks are resolved, else null. */
private fun relativeToRoot(path: Path, root: Path): String? {
    val inside = path.takeIf { it.startsWith(root) }
        ?: runCatching { path.toRealPath() }.getOrNull()?.takeIf { it.startsWith(root) }
    return inside?.let { root.relativize(it).toString() }?.let { if (it.isEmpty()) "." else "./$it" }
}
