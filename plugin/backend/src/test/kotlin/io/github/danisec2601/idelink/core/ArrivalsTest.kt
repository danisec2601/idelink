package io.github.danisec2601.idelink.core

import io.github.danisec2601.idelink.shared.ArrivalKind
import io.github.danisec2601.idelink.shared.ArrivalOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArrivalsTest {
    @Test
    fun theFirstRoutingReportAnswersTheCall() {
        val a = Arrivals<String>(waitMs = 2_000)
        for ((outcome, want) in listOf(ArrivalOutcome.DIRECT to Arrivals.Routed.DIRECT,
            ArrivalOutcome.ASKED to Arrivals.Routed.ASKED)) {
            val id = a.newId()
            val routed = a.send("p", ArrivalKind.TOUR, id, userAllowed = false) {
                assertNull(a.report("p", ArrivalKind.TOUR, id, outcome))
            }
            assertEquals(want, routed)
        }
    }

    @Test
    fun anAllowedReportCountsOnlyForACallTheUserAllowed() {
        val a = Arrivals<String>(waitMs = 300)
        val yes = a.newId()
        assertEquals(Arrivals.Routed.ALLOWED, a.send("p", ArrivalKind.OPEN, yes, userAllowed = true) {
            assertNull(a.report("p", ArrivalKind.OPEN, yes, ArrivalOutcome.ALLOWED))
        })
        val no = a.newId()
        val routed = a.send("p", ArrivalKind.OPEN, no, userAllowed = false) {
            assertNotNull(a.report("p", ArrivalKind.OPEN, no, ArrivalOutcome.ALLOWED))
        }
        assertEquals(Arrivals.Routed.NO_REPLY, routed, "a client can't claim an Allow once the user never gave")
    }

    @Test
    fun noReportMeansNoReplyAfterTheWait() {
        val a = Arrivals<String>(waitMs = 50)
        assertEquals(Arrivals.Routed.NO_REPLY, a.send("p", ArrivalKind.OPEN, a.newId(), userAllowed = false) {})
    }

    @Test
    fun reportsForAnotherCallProjectOrKindDontAnswer() {
        val a = Arrivals<String>(waitMs = 100)
        val id = a.newId()
        val routed = a.send("p", ArrivalKind.OPEN, id, userAllowed = false) {
            assertNotNull(a.report("other", ArrivalKind.OPEN, id, ArrivalOutcome.DIRECT))
            assertNotNull(a.report("p", ArrivalKind.TOUR, id, ArrivalOutcome.DIRECT))
            assertNotNull(a.report("p", ArrivalKind.OPEN, id + 1, ArrivalOutcome.DIRECT))
        }
        assertEquals(Arrivals.Routed.NO_REPLY, routed)
        assertNotNull(a.report("p", ArrivalKind.OPEN, id, ArrivalOutcome.DIRECT), "a finished call takes no report")
    }

    @Test
    fun anOfferOpensOnceOnlyAsTheCurrentOfferOfItsProject() {
        val a = Arrivals<String>()
        val old = a.offer("p", "a.py")
        val cur = a.offer("p", "b.py")
        assertNull(a.take("p", old), "a replaced offer must not open")
        assertNull(a.take("q", cur), "an offer id from another project must not open")
        assertEquals("b.py", a.take("p", cur))
        assertNull(a.take("p", cur), "Show twice must not open twice")
    }

    @Test
    fun notNowDropsTheOffer() {
        val a = Arrivals<String>()
        val id = a.offer("p", "a.py")
        a.report("p", ArrivalKind.OPEN, id, ArrivalOutcome.DECLINED)
        assertNull(a.take("p", id), "Not now must leave nothing to open")
        val keep = a.offer("p", "b.py")
        a.report("p", ArrivalKind.OPEN, keep + 99, ArrivalOutcome.DECLINED)
        a.report("p", ArrivalKind.TOUR, keep, ArrivalOutcome.DECLINED)
        assertEquals("b.py", a.take("p", keep), "declining something else must not drop the current offer")
    }

    @Test
    fun idsFromTwoInstancesDoNotCollide() {
        val before = Arrivals<String>().offer("p", "a.py")
        Thread.sleep(2)
        assertNull(Arrivals<String>().apply { offer("p", "a.py") }.take("p", before))
    }
}
