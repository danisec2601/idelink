package io.github.danisec2601.idelink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectLabelsTest {
    private fun e(name: String, base: String?) = ProjectLabels.Entry(name, base)

    @Test
    fun aUniqueNameIsItsOwnLabel() {
        assertEquals(listOf("idelink", "other"), ProjectLabels.of(listOf(e("idelink", "/h/a"), e("other", "/h/b"))))
    }

    @Test
    fun namesakesGetTheFewestRootDirectoriesThatTellThemApart() {
        val labels = ProjectLabels.of(listOf(
            e("idelink", "/home/d/dev/idelink"), e("idelink", "/home/d/dev/idelink-wt-rcor"),
            e("idelink", "/srv/x/idelink"), e("solo", "/s"),
        ))
        assertEquals(
            listOf("idelink (dev/idelink)", "idelink (idelink-wt-rcor)", "idelink (x/idelink)", "solo"), labels)
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun aLabelIsFoundOnlyWhenExactlyOneProjectHasIt() {
        assertEquals(1, ProjectLabels.find(listOf("a", "b (x)"), "b (x)"))
        assertNull(ProjectLabels.find(listOf("a", "b (x)"), "b"), "a bare name must not match a namesake's label")
        assertNull(ProjectLabels.find(listOf("a (x)", "a (x)"), "a (x)"), "a label two projects share must match none")
    }

    @Test
    fun pathScopeRefusesALabelTwoRootsShare() {
        val roots = listOf("/", "/tmp").map { PathScope.Root("p", java.nio.file.Path.of(it)) }
        val e = kotlin.runCatching { PathScope.locate(roots, "/etc/hostname", "p") }.exceptionOrNull()
        assertTrue(e is BadArgs && e.message!!.contains("several open projects"), e.toString())
    }

    @Test
    fun onlyAKnownControllerCallerIsAccepted() {
        assertTrue(ControllerCheck.accepts("c1", setOf("c1", "c2")))
        assertFalse(ControllerCheck.accepts("guest", setOf("c1")))
        assertFalse(ControllerCheck.accepts(null, setOf("c1")))
        assertFalse(ControllerCheck.accepts("c1", emptySet()))
    }
}
