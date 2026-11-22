package it.mato.livebus

import it.mato.livebus.DepartureTiming.Arrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureTimingTest {
    // 360 m at 1.2 m/s: a five-minute walk.
    private val fiveMinuteWalk = 360f

    @Test fun leavingTimeIsTheBusLessTheWalkAndTheSpareTime() {
        // Bus in 20 min, 5 min walk, 10 min spare: leave in 5 min.
        assertEquals(300f, DepartureTiming.leaveInSeconds(1200f, fiveMinuteWalk, 10), 0.5f)
    }

    @Test fun aRiderAtTheStopHasNoWalk() =
        assertEquals(0f, DepartureTiming.walkSeconds(DepartureTiming.AT_STOP_METRES - 1f))

    @Test fun aReminderIsOfferedOnlyWithTimeToWait() {
        assertTrue(DepartureTiming.worthReminding(900f, fiveMinuteWalk, 1))
        // Bus in 7 min, 5 min walk, 1 min spare: one minute to wait is no reminder.
        assertFalse(DepartureTiming.worthReminding(420f, fiveMinuteWalk, 1))
        // Ten minutes early does not fit a bus that is 12 minutes away.
        assertFalse(DepartureTiming.worthReminding(720f, fiveMinuteWalk, 10))
    }

    @Test fun thePlannedBusIsKeptWhileItLeavesTheSpareTime() {
        val arrivals = listOf(Arrival("soon", 600f), Arrival("planned", 900f))
        assertEquals("planned", DepartureTiming.chooseBus(arrivals, "planned", fiveMinuteWalk, 1)?.vehicleId)
    }

    @Test fun aBusThatNoLongerLeavesTheSpareTimeGivesWayToTheNext() {
        val arrivals = listOf(Arrival("planned", 500f), Arrival("next", 1300f), Arrival("later", 2000f))
        assertEquals("next", DepartureTiming.chooseBus(arrivals, "planned", fiveMinuteWalk, 10)?.vehicleId)
    }

    @Test fun withNoBusLeavingTheSpareTimeTheSoonestReachableOneIsChosen() {
        val arrivals = listOf(Arrival("gone", 120f), Arrival("reachable", 400f))
        assertEquals("reachable", DepartureTiming.chooseBus(arrivals, null, fiveMinuteWalk, 10)?.vehicleId)
    }

    @Test fun noBusIsChosenWhenNoneCanBeReached() =
        assertNull(DepartureTiming.chooseBus(listOf(Arrival("gone", 100f)), null, fiveMinuteWalk, 1))
}

class BoardingCheckTest {
    @Test fun twoFixesOnTheBusMeanOnBoard() {
        val check = BoardingCheck()
        assertEquals(BoardingCheck.Verdict.UNSURE, check.record(150f, 20f))
        assertEquals(BoardingCheck.Verdict.ON_BOARD, check.record(300f, 25f))
    }

    @Test fun oneStrayFixDoesNotDecide() {
        val check = BoardingCheck()
        assertEquals(BoardingCheck.Verdict.UNSURE, check.record(150f, 20f))
        assertEquals(BoardingCheck.Verdict.UNSURE, check.record(10f, 400f))
        assertEquals(BoardingCheck.Verdict.MISSED, check.record(12f, 600f))
    }

    @Test fun aRiderStillAtTheStopMissedIt() {
        val check = BoardingCheck()
        check.record(15f, 200f)
        assertEquals(BoardingCheck.Verdict.MISSED, check.record(15f, 350f))
    }

    @Test fun withoutAnyFixTheBusCountsAsMissedInTheEnd() {
        val check = BoardingCheck()
        repeat(BoardingCheck.MAX_CHECKS - 1) {
            assertEquals(BoardingCheck.Verdict.UNSURE, check.record(null, null))
        }
        assertEquals(BoardingCheck.Verdict.MISSED, check.record(null, null))
    }
}
