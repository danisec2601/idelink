package io.github.danisec2601.idelink

import io.github.danisec2601.idelink.core.PathScope
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TourStoreTest {
    private fun stop(name: String, line: Int) =
        TourStore.Stop(PathScope.Resolved("p", Path.of("/r"), Path.of("/r/$name"), name), line)

    @Test
    fun aStopIsFoundOnlyByTheCurrentTourOfItsProject() {
        val s = TourStore()
        val old = s.put("p", listOf(stop("a.py", 1)))
        val cur = s.put("p", listOf(stop("b.py", 2), stop("c.py", 3)))
        assertNotEquals(old, cur)
        assertEquals("c.py", s.stop("p", cur, 1)?.file?.rel)
        assertNull(s.stop("p", old, 0), "a replaced tour's stops must not open")
        assertNull(s.stop("other", cur, 0), "a tour id from another project must not open")
        assertNull(s.stop("p", cur, 2))
        assertNull(s.stop("p", cur, -1))
    }

    @Test
    fun idsFromTwoStoresDoNotCollide() {
        // Stands in for a backend restart while the client still shows the old tour.
        val before = TourStore().put("p", listOf(stop("a.py", 1)))
        Thread.sleep(2)
        assertNull(TourStore().apply { put("p", listOf(stop("a.py", 1))) }.stop("p", before, 0))
    }
}
