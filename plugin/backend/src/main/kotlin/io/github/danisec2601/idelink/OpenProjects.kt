package io.github.danisec2601.idelink

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.danisec2601.idelink.core.BadArgs
import io.github.danisec2601.idelink.core.PathScope
import io.github.danisec2601.idelink.core.ProjectLabels

/**
 * Open projects as tools see them: by [ProjectLabels] label in tool I/O, by [key] in the backend's stores.
 * A project's name alone is never a key: two worktrees of one repo share it.
 */
internal object OpenProjects {
    fun all(): List<Project> = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }

    fun labelled(): List<Pair<Project, String>> {
        val ps = all()
        return ps.zip(ProjectLabels.of(ps.map { ProjectLabels.Entry(it.name, it.basePath) }))
    }

    fun label(project: Project): String = labelled().firstOrNull { it.first == project }?.second ?: project.name

    fun byLabel(label: String): Project? {
        val ls = labelled()
        return ProjectLabels.find(ls.map { it.second }, label)?.let { ls[it].first }
    }

    /** [byLabel], or a [BadArgs] that names the open projects. */
    fun require(label: String): Project = byLabel(label) ?: throw BadArgs(
        "no open project labelled '${label.take(MAX_ECHOED)}'; open: ${labelled().joinToString { it.second }}")

    fun roots(): List<PathScope.Root> = labelled().mapNotNull { (p, l) -> PathScope.realRoot(l, p.basePath) }

    /** Unique per open project, unlike its name. */
    fun key(project: Project): String = project.locationHash

    fun byKey(key: String): Project? = all().firstOrNull { key(it) == key }

    /** The open project [r] was resolved in: same label and same real root, so a namesake never stands in. */
    fun of(r: PathScope.Resolved): Project = labelled().firstOrNull { (p, l) ->
        l == r.project && PathScope.realRoot(l, p.basePath)?.root == r.root
    }?.first ?: throw BadArgs("project closed")

    private const val MAX_ECHOED = 64
}
