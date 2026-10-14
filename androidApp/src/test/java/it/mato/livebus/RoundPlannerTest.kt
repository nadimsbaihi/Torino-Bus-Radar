package it.mato.livebus

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** The round search on small hand-made networks: stops are numbers, times are seconds from now. */
class RoundPlannerTest {
    private val far = Float.POSITIVE_INFINITY

    private fun trip(
        stops: IntArray,
        times: FloatArray,
        route: String = "r",
        train: Boolean = false,
        depart: FloatArray = times,
        sigma: Float = 20f
    ) = PlanTrip(stops, times, depart, train, if (train) "" else route, { sigma }, route)

    private fun request(
        stopCount: Int,
        trips: List<PlanTrip>,
        walks: Map<Int, List<Footpath>> = emptyMap(),
        origin: Map<Int, Float> = mapOf(0 to 0f),
        destination: Map<Int, Float>,
        maxVehicles: Int = 2,
        maxTrains: Int = 0,
        weight: Float = 0f,
        initial: Float = 10_000f
    ) = PlanRequest(stopCount, trips, { walks[it].orEmpty() },
        FloatArray(stopCount) { origin[it] ?: far }, FloatArray(stopCount) { destination[it] ?: far },
        initial, weight, maxVehicles, maxTrains)

    private fun plan(request: PlanRequest, check: () -> Unit = {}): List<PlannedChoice> {
        val planner = RoundPlanner(request, check)
        while (planner.step()) { /* every round */ }
        return planner.choose()
    }

    private fun floats(vararg values: Float) = values

    @Test fun aDirectBusGetsThereAtTheTimeOfTheBusPlusTheFinalWalk() {
        val bus = trip(intArrayOf(0, 1, 2, 3), floats(100f, 200f, 300f, 400f))
        val choices = plan(request(4, listOf(bus), destination = mapOf(3 to 120f, 2 to 2_000f)))
        val best = choices.first().journey
        assertEquals(OptionReason.BEST, choices.first().reason)
        assertEquals(1, best.vehicles)
        assertEquals(400f + 100f, best.totalSeconds, 0.01f)
        assertEquals(120f, best.walkingMetres, 0.01f)
        val leg = best.legs().single()
        assertEquals(0, leg.boardPos)
        assertEquals(3, leg.alightPos)
    }

    @Test fun aBusAlreadyGoneIsNotCaught() {
        val bus = trip(intArrayOf(0, 1), floats(-10f, 200f))
        assertTrue(plan(request(2, listOf(bus), destination = mapOf(1 to 0f))).isEmpty())
    }

    @Test fun oneChangeJoinsTwoBusesAfterAShortWalk() {
        val first = trip(intArrayOf(0, 1), floats(100f, 200f), route = "a")
        val second = trip(intArrayOf(2, 3), floats(400f, 500f), route = "b")
        val choices = plan(request(4, listOf(first, second),
            walks = mapOf(1 to listOf(Footpath(2, 100f))), destination = mapOf(3 to 0f)))
        val best = choices.first().journey
        assertEquals(2, best.vehicles)
        assertEquals(500f, best.totalSeconds, 0.01f)
        assertEquals(100f, best.legs()[1].walkBeforeMetres, 0.01f)
    }

    @Test fun aChangeNeedsTimeForTheWalkItsSlackAndTheMargin() {
        val first = trip(intArrayOf(0, 1), floats(100f, 200f), route = "a")
        // 100 m is 83 s; with 15% slack 96 s, and 20 s to change: ready at 316.
        val tooSoon = trip(intArrayOf(2, 3), floats(315f, 500f), route = "b")
        val justIn = trip(intArrayOf(2, 3), floats(317f, 500f), route = "b")
        val walks = mapOf(1 to listOf(Footpath(2, 100f)))
        assertTrue(plan(request(4, listOf(first, tooSoon), walks = walks, destination = mapOf(3 to 0f))).isEmpty())
        assertEquals(2, plan(request(4, listOf(first, justIn), walks = walks, destination = mapOf(3 to 0f)))
            .first().journey.vehicles)
    }

    @Test fun anotherBusOfTheSameLineIsNotAChange() {
        val first = trip(intArrayOf(0, 1), floats(100f, 200f), route = "a")
        val second = trip(intArrayOf(1, 3), floats(400f, 500f), route = "a")
        assertTrue(plan(request(4, listOf(first, second), destination = mapOf(3 to 0f))).isEmpty())
    }

    @Test fun twoChangesNeedThreeRounds() {
        val a = trip(intArrayOf(0, 1), floats(100f, 200f), route = "a")
        val b = trip(intArrayOf(2, 3), floats(400f, 500f), route = "b")
        val c = trip(intArrayOf(4, 5), floats(700f, 800f), route = "c")
        val walks = mapOf(1 to listOf(Footpath(2, 50f)), 3 to listOf(Footpath(4, 50f)))
        val destination = mapOf(5 to 0f)
        assertTrue("two vehicles cannot do it", plan(request(6, listOf(a, b, c), walks, destination = destination)).isEmpty())
        val best = plan(request(6, listOf(a, b, c), walks, destination = destination, maxVehicles = 3)).first().journey
        assertEquals(3, best.vehicles)
        assertEquals(800f, best.totalSeconds, 0.01f)
        assertEquals(listOf(0, 0, 0), best.legs().map { it.boardPos })
    }

    @Test fun aBusCanFeedATrainAndATrainABus() {
        val bus = trip(intArrayOf(0, 1), floats(100f, 300f), route = "a")
        // The train leaves the station (stop 2) at 600; arriving at 300 + 100 m walk + 2 min boarding fits.
        val train = trip(intArrayOf(2, 3), floats(500f, 1_500f), train = true, depart = floats(600f, 1_500f))
        val walks = mapOf(1 to listOf(Footpath(2, 100f)))
        val busThenTrain = plan(request(4, listOf(bus, train), walks, destination = mapOf(3 to 0f), maxTrains = 1))
        val journey = busThenTrain.first().journey
        assertEquals(1_500f, journey.totalSeconds, 0.01f)
        assertEquals(listOf(false, true), journey.legs().map { it.trip.isTrain })
        assertEquals(1, journey.last.trains)

        // The other way round: the train from the start, then a bus from the station.
        val early = trip(intArrayOf(0, 1), floats(500f, 900f), train = true, depart = floats(200f, 900f))
        val connecting = trip(intArrayOf(2, 3), floats(1_100f, 1_300f), route = "b")
        val trainThenBus = plan(request(4, listOf(early, connecting), mapOf(1 to listOf(Footpath(2, 80f))),
            destination = mapOf(3 to 0f), maxTrains = 1))
        assertEquals(listOf(true, false), trainThenBus.first().journey.legs().map { it.trip.isTrain })
    }

    @Test fun aTrainNeedsTwoMinutesAfterTheBusAndWalk() {
        val bus = trip(intArrayOf(0, 1), floats(100f, 300f), route = "a")
        val walks = mapOf(1 to listOf(Footpath(2, 100f)))
        // Arrive 300, walk 83 s, board no sooner than 300 + 83 + 120 = 503.
        val tight = trip(intArrayOf(2, 3), floats(500f, 900f), train = true, depart = floats(502f, 900f))
        val ok = trip(intArrayOf(2, 3), floats(500f, 900f), train = true, depart = floats(504f, 900f))
        val destination = mapOf(3 to 0f)
        assertTrue(plan(request(4, listOf(bus, tight), walks, destination = destination, maxTrains = 1)).isEmpty())
        assertFalse(plan(request(4, listOf(bus, ok), walks, destination = destination, maxTrains = 1)).isEmpty())
    }

    @Test fun trainsAreIgnoredUnlessAllowed() {
        val train = trip(intArrayOf(0, 1), floats(300f, 900f), train = true, depart = floats(300f, 900f))
        assertTrue("a journey of trains alone belongs to the train planner",
            plan(request(2, listOf(train), destination = mapOf(1 to 0f), maxTrains = 1)).isEmpty())
        val bus = trip(intArrayOf(0, 1), floats(100f, 900f))
        val withTrain = plan(request(2, listOf(train, bus), destination = mapOf(1 to 0f), maxTrains = 0))
        assertEquals(false, withTrain.first().journey.legs().single().trip.isTrain)
    }

    @Test fun noRouteMeansNoJourney() {
        val bus = trip(intArrayOf(0, 1), floats(100f, 200f))
        assertTrue(plan(request(3, listOf(bus), destination = mapOf(2 to 0f))).isEmpty())
        assertTrue(plan(request(3, emptyList(), destination = mapOf(2 to 0f))).isEmpty())
    }

    @Test fun aRideThatDoesNotGetCloserToTheDestinationIsRefused() {
        val bus = trip(intArrayOf(0, 1), floats(100f, 200f))
        // Getting off 3 km from the destination when the whole walk is 2 km is no help.
        assertTrue(plan(request(2, listOf(bus), destination = mapOf(0 to 2_500f, 1 to 3_000f), initial = 2_000f)).isEmpty())
        // Nor is it one that leaves the rider further than the stop they boarded at.
        assertTrue(plan(request(2, listOf(bus), destination = mapOf(0 to 500f, 1 to 600f), initial = 2_000f)).isEmpty())
        assertFalse(plan(request(2, listOf(bus), destination = mapOf(0 to 800f, 1 to 600f), initial = 2_000f)).isEmpty())
    }

    @Test fun aSlowerJourneyWithLessWalkingIsOfferedBesideTheFastest() {
        val fast = trip(intArrayOf(1, 3), floats(900f, 1_000f), route = "f")
        val slow = trip(intArrayOf(0, 2), floats(100f, 1_300f), route = "s")
        val origin = mapOf(0 to 0f, 1 to 600f)
        val destination = mapOf(2 to 0f, 3 to 0f)
        val choices = plan(request(4, listOf(fast, slow), origin = origin, destination = destination, initial = 5_000f))
        assertEquals(1_000f, choices.first().journey.totalSeconds, 0.01f)
        val byReason = choices.associateBy { it.reason }
        assertEquals(1_300f, byReason.getValue(OptionReason.LEAST_WALKING).journey.totalSeconds, 0.01f)
        assertEquals(0f, byReason.getValue(OptionReason.LEAST_WALKING).journey.walkingMetres, 0.01f)
    }

    @Test fun walkingWeightMakesTheWalkingJourneyLoseToTheSlowerOne() {
        val fast = trip(intArrayOf(1, 3), floats(900f, 1_000f), route = "f")
        val slow = trip(intArrayOf(0, 2), floats(100f, 1_300f), route = "s")
        val origin = mapOf(0 to 0f, 1 to 600f)
        val destination = mapOf(2 to 0f, 3 to 0f)
        val choices = plan(request(4, listOf(fast, slow), origin = origin, destination = destination,
            initial = 5_000f, weight = 4f))
        assertEquals(1_300f, choices.first().journey.totalSeconds, 0.01f)
        assertEquals(1_000f, choices.first { it.reason == OptionReason.FASTEST }.journey.totalSeconds, 0.01f)
    }

    @Test fun aJourneyWithFewerChangesIsOfferedWhenTheBestChanges() {
        // The direct bus is slow; changing is quicker.
        val direct = trip(intArrayOf(0, 3), floats(100f, 1_400f), route = "d")
        val first = trip(intArrayOf(0, 1), floats(100f, 200f), route = "a")
        val second = trip(intArrayOf(2, 3), floats(400f, 600f), route = "b")
        val choices = plan(request(4, listOf(direct, first, second),
            walks = mapOf(1 to listOf(Footpath(2, 50f))), destination = mapOf(3 to 0f)))
        assertEquals(2, choices.first().journey.vehicles)
        assertEquals(1, choices.first { it.reason == OptionReason.FEWEST_CHANGES }.journey.vehicles)
    }

    @Test fun aTightConnectionLosesToASafeOne() {
        // Both buses arrive at the same time overall, but one connection has seconds to spare.
        val first = trip(intArrayOf(0, 1), floats(100f, 200f), route = "a", sigma = 30f)
        val tight = trip(intArrayOf(2, 3), floats(320f, 500f), route = "tight", sigma = 30f)
        val safe = trip(intArrayOf(2, 3), floats(420f, 520f), route = "safe", sigma = 30f)
        val walks = mapOf(1 to listOf(Footpath(2, 100f)))
        val best = plan(request(4, listOf(first, tight, safe), walks, destination = mapOf(3 to 0f))).first().journey
        assertEquals("safe", best.legs()[1].trip.payload)
        assertTrue(best.legs()[1].boardChance > 0.9f)
    }

    @Test fun stepsAndEarlyResultsCanBeTakenRoundByRound() {
        val a = trip(intArrayOf(0, 1), floats(100f, 200f), route = "a")
        val b = trip(intArrayOf(2, 3), floats(400f, 500f), route = "b")
        val planner = RoundPlanner(request(4, listOf(a, b), mapOf(1 to listOf(Footpath(2, 100f))),
            destination = mapOf(1 to 5_000f, 3 to 0f), initial = 10_000f))
        assertTrue(planner.step())
        assertNull(planner.best { it.vehicles == 2 })
        assertEquals(1, planner.best()?.vehicles)
        assertTrue(planner.step())
        assertEquals(2, planner.best { it.vehicles == 2 }?.vehicles)
        assertFalse(planner.step())
    }

    @Test fun cancellationStopsTheSearch() {
        val trips = (0 until 50).map { trip(intArrayOf(0, 1), floats(100f + it, 200f + it), route = "r$it") }
        var checks = 0
        try {
            plan(request(2, trips, destination = mapOf(1 to 0f))) {
                if (++checks == 10) throw CancellationException("cancelled")
            }
            fail("the search must stop when cancelled")
        } catch (expected: CancellationException) {
            assertEquals(10, checks)
        }
    }
}
