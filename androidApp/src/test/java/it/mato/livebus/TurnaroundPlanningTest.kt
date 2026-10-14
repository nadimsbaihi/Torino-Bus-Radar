package it.mato.livebus

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A bus about to finish its line is also a bus that will soon be coming back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TurnaroundPlanningTest {
    // North along one street, and back south along it from the same terminus.
    private fun line(top: Double, continuing: Set<String> = setOf("out"), wait: Int = 300): Pair<TransitPattern, TransitPattern> {
        val middle = top / 2 + 45.0 / 2
        val out = TransitPattern("out", "r", "r", "North", "0", setOf("out"), listOf(
            stop("A", 45.0, 7.0), stop("B", middle, 7.0), stop("C", top, 7.0)),
            next = PatternSuccessor("back", wait, continuing))
        val back = TransitPattern("back", "r", "r", "South", "1", setOf("back"), listOf(
            stop("C2", top, 7.0), stop("B2", middle, 7.0), stop("A2", 45.0, 7.0)))
        return out to back
    }

    private fun stop(id: String, lat: Double, lon: Double) = TransitStop(id, id, lat, lon)
    private fun bus(id: String, trip: String, lat: Double) =
        LiveVehicle(id, "r", trip, lat, 7.0, null, System.currentTimeMillis() / 1000)
    private fun index(vararg patterns: TransitPattern): TransitIndex =
        TransitIndex::class.java.getDeclaredConstructor(List::class.java).apply { isAccessible = true }
            .newInstance(patterns.toList())

    @Test fun aBusNearingTheEndOfItsLineIsPlannedAsTheBusComingBack() = runBlocking {
        val (out, back) = line(45.02)
        // Just short of the terminus, heading north; the rider is by the southbound stop B2.
        val choice = index(out, back).planLiveJourney(45.0095, 7.0, 45.001, 7.0,
            listOf(bus("bus", "out", 45.0185)))

        assertEquals("back", choice!!.pattern.id)
        assertEquals("bus", choice.vehicle.id)
        assertEquals("B2", choice.boardAt.id)
        val turnaround = choice.turnaround!!
        assertEquals("out", turnaround.previousPatternId)
        // Gets to the terminus, waits its turnaround and the safety buffer, then drives to B2.
        assertTrue(turnaround.departsInSeconds in 300f..600f)
        assertTrue(choice.boardingEtaSeconds > turnaround.departsInSeconds)
        assertFalse("the bus really is on the timetable's trip", choice.directionEstimated)
    }

    @Test fun aBusStandingAtTheTerminusSaysWhenItSetsOffAgain() {
        val (out, back) = line(45.02)
        val index = index(out, back)
        val arrived = System.currentTimeMillis() / 1000 - 120
        // Seen standing at C two minutes ago, and still there.
        val first = LiveVehicle("bus", "r", "out", 45.0199, 7.0, null, arrived)
        assertEquals(300f, index.layoverOf(first, nowSeconds = arrived)!!.departsInSeconds)
        val later = first.copy(timestampSeconds = arrived + 120)
        val layover = index.layoverOf(later, nowSeconds = arrived + 120)!!

        assertEquals("C", layover.terminus.id)
        assertEquals(180f, layover.departsInSeconds)
        // Once its wait is over it is about to go, not overdue by a negative amount.
        assertEquals(0f, index.layoverOf(later, nowSeconds = arrived + 900)!!.departsInSeconds)
    }

    @Test fun aBusOnItsWayIsNotAtTheTerminus() {
        val (out, back) = line(45.02)
        assertNull(index(out, back).layoverOf(bus("bus", "out", 45.01)))
    }

    @Test fun aBusEndingItsDutyAtTheTerminusIsNotSaidToSetOffAgain() {
        val (out, back) = line(45.02, continuing = setOf("some-other-trip"))
        assertNull(index(out, back).layoverOf(bus("bus", "out", 45.0199)))
    }

    @Test fun aBusWaitingAtTheStartOfItsNextRunUsesTheWaitBeforeIt() {
        val (out, back) = line(45.02, wait = 240)
        val layover = index(out, back).layoverOf(bus("bus", "back", 45.0199))!!

        assertEquals("C2", layover.terminus.id)
        assertEquals(240f, layover.departsInSeconds, 5f)
    }

    @Test fun nothingComesBackWhenTheTimetableHasNoSuccessor() = runBlocking {
        val (out, back) = line(45.02)
        assertNull(index(out.copy(next = null), back).planLiveJourney(45.0095, 7.0, 45.001, 7.0,
            listOf(bus("bus", "out", 45.0185))))
    }

    @Test fun theLastRunOfABusesDutyIsNotCountedOn() = runBlocking {
        // The timetable lists other trips continuing, but not this one.
        val (out, back) = line(45.02, continuing = setOf("some-other-trip"))
        assertNull(index(out, back).planLiveJourney(45.0095, 7.0, 45.001, 7.0,
            listOf(bus("bus", "out", 45.0185))))
    }

    @Test fun aBusStillFarFromTheTerminusIsLeftOut() = runBlocking {
        val (out, back) = line(45.4)
        val fleet = index(out, back).trackVehicles(listOf(bus("bus", "out", 45.0)))
        assertEquals(listOf("out"), fleet.map { it.pattern.id })
        assertNull(fleet.single().turnaround)
    }

    @Test fun trackedFleetGetsTheReturnRunStartingBeforeTheFirstStop() = runBlocking {
        val (out, back) = line(45.02)
        val fleet = index(out, back).trackVehicles(listOf(bus("bus", "out", 45.0185)))

        val coming = fleet.single { it.turnaround != null }
        assertEquals("back", coming.pattern.id)
        assertEquals(-1, coming.progress)
        assertEquals("bus", coming.vehicle.id)
        // Later stops are reached later, from the same start.
        assertTrue(coming.arrivalSeconds.toList().zipWithNext().all { (a, b) -> b > a })
        assertEquals("out", fleet.single { it.turnaround == null }.pattern.id)
    }

    @Test fun aBusAlreadyRunningTheWayBeatsOneThatStillHasToTurnAround() = runBlocking {
        val (out, back) = line(45.02)
        val running = bus("running", "back", 45.0145)
        val choice = index(out, back).planLiveJourney(45.0095, 7.0, 45.001, 7.0,
            listOf(bus("turning", "out", 45.0185), running))

        assertEquals("running", choice!!.vehicle.id)
        assertNull(choice.turnaround)
    }

    @Test fun aTurningBusIsNotReplannedAwayWhileItFinishesItsLastRun() {
        val (out, back) = line(45.02)
        val activity = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredField("transitIndex\$delegate")
            .apply { isAccessible = true }.set(activity, lazyOf(index(out, back)))
        val bus = bus("bus", "out", 45.0185)
        fun needsReplan(turnaround: Turnaround): Boolean {
            val choice = JourneyChoice(back, bus, back.stops[1], 1, back.stops[2], 50f, 55f, 1, 100f,
                45.001, 7.0, turnaround = turnaround)
            MainActivity::class.java.getDeclaredField("journeyChoice")
                .apply { isAccessible = true }.set(activity, choice)
            return MainActivity::class.java.getDeclaredMethod("activeBusNeedsReplan", List::class.java)
                .apply { isAccessible = true }.invoke(activity, listOf(bus)) as Boolean
        }

        assertFalse(needsReplan(Turnaround("out", 400f)))
        // Long past its time with the bus still on the old run: it went to the depot instead.
        assertTrue(needsReplan(Turnaround("out", 400f, System.currentTimeMillis() - 20 * 60_000L)))
    }
}
