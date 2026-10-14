package it.mato.livebus

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class ReliabilityMathTest {
    @Test fun noMarginIsAToss() {
        assertEquals(0.5f, Reliability.chance(0f, 30f), 0.001f)
    }

    @Test fun roomToSpareIsNearlyCertainAndTooLateIsNearlyHopeless() {
        assertTrue(Reliability.chance(120f, 30f) > 0.99f)
        assertTrue(Reliability.chance(-120f, 30f) < 0.01f)
        assertEquals(0.841f, Reliability.chance(30f, 30f), 0.002f)
    }

    @Test fun lessUncertaintyMeansMoreCertainty() {
        assertTrue(Reliability.chance(20f, 10f) > Reliability.chance(20f, 40f))
    }

    @Test fun uncertaintyGrowsWithTheHorizonAndTheAgeOfTheFix() {
        val near = Reliability.arrivalSigma(60f, 0f)
        assertTrue(Reliability.arrivalSigma(900f, 0f) > near * 2)
        assertTrue(Reliability.arrivalSigma(60f, 120f) > near * 2)
        assertTrue(Reliability.arrivalSigma(60f, 0f, Reliability.TURNAROUND_EXTRA_SECONDS) > near)
        // Never zero: even a bus at the stop is not exact.
        assertTrue(Reliability.arrivalSigma(0f, 0f) >= 15f)
    }

    @Test fun penaltyIsProportionalToTheChanceOfMissing() {
        assertEquals(0f, Reliability.penaltySeconds(1f), 0.001f)
        assertEquals(Reliability.penaltySeconds(0f) / 2, Reliability.penaltySeconds(0.5f), 0.001f)
        assertTrue(Reliability.penaltySeconds(0f) in 240f..600f)
    }

    @Test fun combinedSpreadIsThePythagoreanSum() {
        assertEquals(5f, Reliability.combine(3f, 4f), 0.001f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReliabilityPlanningTest {
    private val now = System.currentTimeMillis() / 1000
    private val stops = listOf(
        TransitStop("s0", "s0", 45.000, 7.0), TransitStop("board", "board", 45.010, 7.0),
        TransitStop("end", "end", 45.030, 7.0))

    private fun line(id: String) = TransitPattern(id, id, id, id, "0", setOf(id), stops)

    private fun bus(pattern: TransitPattern, id: String, lat: Double) =
        LiveVehicle(id, pattern.routeId, pattern.id, lat, 7.0, null, now)

    private fun index(vararg patterns: TransitPattern) = TransitIndex::class.java
        .getDeclaredConstructor(List::class.java).apply { isAccessible = true }.newInstance(patterns.toList())

    private fun plan(index: TransitIndex, vehicles: List<LiveVehicle>, walkMetres: Float) = runBlocking {
        index.planLiveJourney(45.0101, 7.0, 45.03, 7.0, vehicles,
            walkFromOrigin = { _, _ -> walkMetres },
            walkFromDestination = { lat, lon -> kotlin.math.abs(lat - 45.03).toFloat() * 111_195f })
    }

    @Test fun aSlimlyCaughtBusLosesToASafeOneThatIsAHairLater() {
        val a = line("a")
        val b = line("b")
        // A is ~151 s from the stop and B ~186 s; the walk takes 129 s and the walker is given 15% slack.
        val vehicles = listOf(bus(a, "A", 45.0035), bus(b, "B", 45.0020))
        val choice = plan(index(a, b), vehicles, 155f)!!
        assertEquals("b", choice.pattern.id)
        assertTrue(choice.boardingChance > 0.9f)
        // With a longer cushion the faster bus is safe enough to win.
        val relaxed = plan(index(a, b), vehicles, 90f)!!
        assertEquals("a", relaxed.pattern.id)
    }

    @Test fun staleFixesAreTrustedLess() {
        val a = line("a")
        val fresh = plan(index(a), listOf(bus(a, "A", 45.0001)), 120f)!!
        val old = plan(index(a), listOf(bus(a, "A", 45.0001).copy(timestampSeconds = now - 100)), 120f)!!
        assertTrue("fresh ${fresh.boardingChance} old ${old.boardingChance}",
            old.boardingChance < fresh.boardingChance)
    }

    @Test fun theNextBusIsOfferedAsABackup() {
        val a = line("a")
        val b = line("b")
        val vehicles = listOf(bus(a, "A", 45.0035), bus(b, "B", 45.0020), bus(a, "C", 45.000).copy(tripId = "a"))
        val choice = plan(index(a, b), vehicles, 90f)!!
        assertEquals("a", choice.pattern.id)
        val backup = choice.backup!!
        assertEquals("B", backup.vehicle.id)
        assertEquals("b", backup.route)
        assertTrue(backup.etaSeconds > choice.boardingEtaSeconds + 30f)
    }

    @Test fun noBackupWhenNothingElseIsComing() {
        val a = line("a")
        assertNull(plan(index(a), listOf(bus(a, "A", 45.0035)), 90f)!!.backup)
    }

    @Test fun aBusThatCannotBeWalkedToInTimeIsNotABackup() {
        val a = line("a")
        val b = line("b")
        // B is long gone past the stop.
        val choice = plan(index(a, b), listOf(bus(a, "A", 45.0035), bus(b, "B", 45.0105)), 90f)!!
        assertNull(choice.backup)
    }

    @Test fun theBackupIsToldInTheJourneyPanel() {
        val a = line("a")
        val b = line("b")
        val choice = plan(index(a, b), listOf(bus(a, "A", 45.0035), bus(b, "B", 45.0020)), 90f)!!
        val notes = JourneySteps.forBus(RuntimeEnvironment.getApplication(), choice).notes
        assertTrue(notes.toString(), notes.any { it.startsWith("Missed it? Next bus b in ~") })
    }
}
