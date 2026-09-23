package it.mato.livebus

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TransitIndexTest {
    private val first = pattern("first", listOf(
        stop("start", 45.0, 7.0), stop("board", 45.01, 7.0), stop("transfer", 45.02, 7.0)
    ))
    private val second = pattern("second", listOf(
        stop("start2", 45.02, 6.95), stop("transfer", 45.02, 7.0), stop("end", 45.02, 7.03)
    ))

    @Test fun rejectsSecondBusThatLeavesBeforeFirstBusArrives() = runBlocking {
        val choice = index(first, second).planLiveJourney(45.01, 7.0, 45.02, 7.03,
            listOf(vehicle(first, 45.0, 7.0), vehicle(second, 45.02, 6.999)))
        assertNotNull(choice)
        assertNull(choice!!.secondLeg)
    }

    @Test fun findsCatchableTransferAndPublishesDirectJourneyFirst() = runBlocking {
        var direct: JourneyChoice? = null
        val choice = index(first, second).planLiveJourney(45.01, 7.0, 45.02, 7.03,
            // The second bus arrives after the first leg, but before a direct brisk walk.
            listOf(vehicle(first, 45.0, 7.0), vehicle(second, 45.02, 6.96)),
            onDirectJourney = { direct = it })
        assertNotNull(direct)
        assertNull(direct!!.secondLeg)
        assertEquals("second", choice!!.secondLeg!!.pattern.id)
        assertEquals("transfer", choice.transferAt!!.id)
        assertTrue(choice.estimatedTotalSeconds < direct!!.estimatedTotalSeconds)
    }

    @Test fun streetBarrierRejectsAFirstLegTransferThatLooksNearby() = runBlocking {
        val nearbySecond = second.copy(stops = second.stops.map { stop ->
            if (stop.id == "transfer") stop.copy(id = "other-side", longitude = 7.0005)
            else stop
        })
        val buses = listOf(vehicle(first, 45.0, 7.0), vehicle(nearbySecond, 45.02, 6.96))
        val index = index(first, nearbySecond)
        val directDistanceChoice = index.planLiveJourney(45.01, 7.0, 45.02, 7.03, buses)
        val streetDistanceChoice = index.planLiveJourney(45.01, 7.0, 45.02, 7.03, buses,
            walkBetweenStops = { _, _, _, _ -> 450f })
        assertNotNull(directDistanceChoice?.secondLeg)
        assertNull(streetDistanceChoice?.secondLeg)
    }

    @Test fun acceptsLaterUsefulExitWhenClosestExitCannotBeReached() = runBlocking {
        val route = pattern("partial", listOf(
            stop("start", 45.0, 7.0),
            stop("early-board", 45.0099, 7.0),
            stop("closest", 45.01, 7.0),
            stop("detour", 45.03, 7.05),
            stop("reachable-board", 45.02, 7.0),
            stop("closer-exit", 45.015, 7.0)
        ))
        val choice = index(route).planLiveJourney(45.02, 7.0, 45.01, 7.0,
            listOf(vehicle(route, 45.0, 7.0)))
        assertNotNull(choice)
        assertEquals("reachable-board", choice!!.boardAt.id)
        assertEquals("closer-exit", choice.destination.id)
        assertTrue(choice.destinationWalkMetres > 500f)
        assertTrue(choice.destinationWalkMetres < 600f)
    }

    @Test fun rejectsRideThatOnlyMovesAwayFromDestination() = runBlocking {
        val route = pattern("away", listOf(
            stop("start", 45.0, 7.0), stop("board", 45.01, 7.0), stop("away", 45.02, 7.0)
        ))
        val choice = index(route).planLiveJourney(45.01, 7.0, 45.005, 7.0,
            listOf(vehicle(route, 45.0, 7.0)))
        assertNull(choice)
    }

    @Test fun gettingCloserDoesNotMakeAnUncatchableBusValid() = runBlocking {
        val choice = index(first).planLiveJourney(45.01, 7.004, 45.03, 7.0,
            listOf(vehicle(first, 45.0099, 7.0)))
        assertNull(choice)
    }

    @Test fun acceptsTightConnectionWithBriskWalk() = runBlocking {
        // About 197 m to walk, with the bus about 111 seconds away.
        val choice = index(first).planLiveJourney(45.01, 7.0025, 45.03, 7.0,
            listOf(vehicle(first, 45.004, 7.0)))
        assertNotNull(choice)
        assertEquals("board", choice!!.boardAt.id)
    }

    @Test fun streetDetourCanMakeAPreviouslyCatchableBusTooFarToWalk() = runBlocking {
        val bus = vehicle(first, 45.004, 7.0)
        val direct = index(first).planLiveJourney(45.01, 7.0025, 45.03, 7.0,
            listOf(bus))
        val viaStreets = index(first).planLiveJourney(45.01, 7.0025, 45.03, 7.0,
            listOf(bus), walkFromOrigin = { lat, lon ->
                if (lat == first.stops[1].latitude && lon == first.stops[1].longitude) 500f
                else 1000f
            })
        assertNotNull(direct)
        assertNull(viaStreets)
    }

    @Test fun acceptsBusArrivingNowWhenAlreadyAtStop() = runBlocking {
        val choice = index(first).planLiveJourney(45.01, 7.0, 45.03, 7.0,
            listOf(vehicle(first, 45.0099, 7.0)))
        assertNotNull(choice)
    }

    @Test fun optimismDoesNotAllowBoardingAfterBusHasPassed() = runBlocking {
        val choice = index(first).planLiveJourney(45.01, 7.0, 45.03, 7.0,
            listOf(vehicle(first, 45.015, 7.0)))
        assertNull(choice)
    }

    @Test fun cancellationStopsBeforeTransferSearch() = runBlocking {
        val work = async(Dispatchers.Default) {
            index(first, second).planLiveJourney(45.01, 7.0, 45.02, 7.03,
                listOf(vehicle(first, 45.0, 7.0), vehicle(second, 45.02, 6.9501)),
                onDirectJourney = { cancel() })
        }
        try {
            work.await()
            fail("Cancelled planning must not return a result")
        } catch (_: CancellationException) { }
    }

    @Test fun bundledNetworkPlansWithHundredsOfVehicles() = runBlocking {
        val index = TransitIndex.load(RuntimeEnvironment.getApplication())
        val vehicles = index.patterns.take(500).mapNotNull { pattern ->
            pattern.tripIds.firstOrNull()?.let { trip ->
                vehicle(pattern, pattern.stops[0].latitude, pattern.stops[0].longitude).copy(tripId = trip)
            }
        }
        repeat(2) { iteration ->
            val started = System.nanoTime()
            val choice = index.planLiveJourney(45.0703, 7.6869, 45.061, 7.678, vehicles)
            println("Bundled network run $iteration: ${(System.nanoTime() - started) / 1_000_000} ms, ${vehicles.size} vehicles")
            assertNotNull(choice)
        }
    }

    @Test fun missingAndUnknownTripsCanPlanUsingRouteAndHeading() = runBlocking {
        val reverse = first.copy(id = "reverse", directionId = "1", tripIds = setOf("reverse"),
            stops = first.stops.reversed())
        for (trip in listOf(null, "new-unindexed-trip")) {
            val choice = index(first, reverse).planLiveJourney(45.01, 7.0, 45.03, 7.0,
                listOf(vehicle(first, 45.0, 7.0).copy(tripId = trip, bearing = 0f)))
            assertNotNull(choice)
            assertEquals("first", choice!!.pattern.id)
            assertTrue(choice.directionEstimated)
        }
    }

    @Test fun fallbackRejectsOppositeAmbiguousAndDistantVehicles() {
        val reverse = first.copy(id = "reverse", directionId = "1", tripIds = setOf("reverse"),
            stops = first.stops.reversed())
        val index = index(first, reverse)
        val bus = vehicle(first, 45.005, 7.0).copy(tripId = null)
        assertNull(index.matchDirection(bus))
        assertEquals("reverse", index.matchDirection(bus.copy(bearing = 180f))!!.id)
        assertNull(index.matchDirection(bus.copy(longitude = 7.1, bearing = 0f)))
        assertFalse(index.isStopIndexAhead(bus.copy(longitude = 7.1), first, 1))
        assertNull(index.matchDirection(bus.copy(routeId = "unknown", bearing = 0f)))
        assertNull(index(first).matchDirection(bus.copy(bearing = 180f)))
    }

    @Test fun exactTripStillTakesPrecedenceOverHeading() = runBlocking {
        val bus = vehicle(first, 45.0, 7.0).copy(bearing = 180f)
        assertEquals(first, index(first).matchDirection(bus))
        val choice = index(first).planLiveJourney(45.01, 7.0, 45.03, 7.0, listOf(bus))
        assertNotNull(choice)
        assertFalse(choice!!.directionEstimated)
    }

    @Test fun missingTripOnSecondLegAlsoLabelsJourneyAsEstimated() = runBlocking {
        val choice = index(first, second).planLiveJourney(45.01, 7.0, 45.02, 7.03,
            listOf(vehicle(first, 45.0, 7.0),
                vehicle(second, 45.02, 6.96).copy(tripId = null, bearing = 90f)))
        assertNotNull(choice?.secondLeg)
        assertTrue(choice!!.directionEstimated)
    }

    @Test fun inferredProgressResetsWhenVehicleStartsAnotherRun() {
        val index = index(first)
        val bus = vehicle(first, 45.015, 7.0).copy(tripId = null, bearing = 0f)
        assertFalse(index.isStopIndexAhead(bus, first, 1))
        assertTrue(index.isStopIndexAhead(bus.copy(latitude = 45.0), first, 1))
    }

    @Test fun delayedPositionReducesArrivalTimeAndCannotResurrectPassedBus() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val bus = vehicle(first, 45.0, 7.0).copy(timestampSeconds = now)
        val fresh = index(first).planLiveJourney(45.01, 7.0, 45.03, 7.0, listOf(bus))!!
        val delayed = index(first).planLiveJourney(45.01, 7.0, 45.03, 7.0,
            listOf(bus.copy(timestampSeconds = now - 150)))!!
        assertEquals(150f, fresh.boardingEtaSeconds - delayed.boardingEtaSeconds, 2f)
        assertNull(index(first).planLiveJourney(45.01, 7.0, 45.03, 7.0,
            listOf(bus.copy(timestampSeconds = now - 240))))
    }

    private fun stop(id: String, lat: Double, lon: Double) = TransitStop(id, id, lat, lon)
    private fun pattern(id: String, stops: List<TransitStop>) =
        TransitPattern(id, id, id, id, "0", setOf(id), stops)
    private fun vehicle(pattern: TransitPattern, lat: Double, lon: Double) =
        LiveVehicle(pattern.id, pattern.routeId, pattern.id, lat, lon, null, System.currentTimeMillis() / 1000)
    private fun index(vararg patterns: TransitPattern): TransitIndex =
        TransitIndex::class.java.getDeclaredConstructor(List::class.java).apply { isAccessible = true }
            .newInstance(patterns.toList())
}
