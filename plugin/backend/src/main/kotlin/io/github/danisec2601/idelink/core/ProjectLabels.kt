package io.github.danisec2601.idelink.core

/**
 * How tools name open projects: the IDE's project name, which isn't unique (two worktrees of one repo), followed
 * where it collides by the fewest trailing root directories that tell the namesakes apart: `idelink (wt-a)`.
 */
internal object ProjectLabels {
    data class Entry(val name: String, val basePath: String?)

    /** One label per entry of [entries], in order. */
    fun of(entries: List<Entry>): List<String> {
        val byName = entries.groupBy { it.name }
        return entries.map { e ->
            val namesakes = byName.getValue(e.name)
            if (namesakes.size == 1) e.name else "${e.name} (${suffix(e, namesakes)})"
        }
    }

    /** The index of the only label equal to [label]; null when none or several are, so nothing is guessed. */
    fun find(labels: List<String>, label: String): Int? = labels.indices.singleOrNull { labels[it] == label }

    private fun suffix(e: Entry, namesakes: List<Entry>): String {
        val mine = segments(e.basePath) ?: return "no root"
        val others = namesakes.filter { it !== e }.mapNotNull { segments(it.basePath) }
        val k = (1..mine.size).firstOrNull { k -> others.none { it.takeLast(k) == mine.takeLast(k) } }
        return k?.let { mine.takeLast(it).joinToString("/") } ?: mine.joinToString("/", prefix = "/")
    }

    private fun segments(path: String?): List<String>? = path?.split('/')?.filter { it.isNotEmpty() }
}
