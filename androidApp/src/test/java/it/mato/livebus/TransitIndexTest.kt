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
        // The second bus comes after the first one, and the rider is at the change before it.
        val leg = choice.secondLeg!!
        assertTrue(leg.boardingEtaSeconds > choice.boardingEtaSeconds)
        assertTrue(leg.waitSeconds >= 0f && leg.waitSeconds < leg.boardingEtaSeconds)
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

    @Test fun acceptsNearbyWalkToBoardingStop() = runBlocking {
        // About 110 m to walk, with the bus about 111 seconds away.
        val choice = index(first).planLiveJourney(45.01, 7.0014, 45.03, 7.0,
            listOf(vehicle(first, 45.004, 7.0)))
        assertNotNull(choice)
        assertEquals("board", choice!!.boardAt.id)
    }

    @Test fun rejectsBoardingWalkThatNeedsAnUnrealisticallyFastPace() = runBlocking {
        // About 197 m to walk, with the bus about 111 seconds away.
        val choice = index(first).planLiveJourney(45.01, 7.0025, 45.03, 7.0,
            listOf(vehicle(first, 45.004, 7.0)))
        assertNull(choice)
    }

    @Test fun streetDetourCanMakeAPreviouslyCatchableBusTooFarToWalk() = runBlocking {
        val bus = vehicle(first, 45.004, 7.0)
        val direct = index(first).planLiveJourney(45.01, 7.0014, 45.03, 7.0,
            listOf(bus))
        val viaStreets = index(first).planLiveJourney(45.01, 7.0014, 45.03, 7.0,
            listOf(bus), walkFromOrigin = { lat, lon ->
                if (lat == first.stops[1].latitude && lon == first.stops[1].longitude) 500f
                else 1000f
            })
        assertNotNull(direct)
        assertNull(viaStreets)
    }

    @Test fun lastWalkingLegAddsRealisticTimeToJourneyEta() = runBlocking {
        val bus = vehicle(first, 45.0, 7.0)
        suspend fun withLastWalk(metres: Float) = index(first).planLiveJourney(
            45.01, 7.0, 45.03, 7.0, listOf(bus),
            walkFromDestination = { lat, lon ->
                if (lat == first.stops.last().latitude && lon == first.stops.last().longitude)
                    metres else 2000f
            })!!

        val atStop = withLastWalk(0f)
        val afterWalk = withLastWalk(240f)
        assertEquals(200f, afterWalk.estimatedTotalSeconds - atStop.estimatedTotalSeconds, 1f)
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
            val preference = if (iteration == 0) WalkingPreference.MORE else WalkingPreference.BALANCED
            val started = System.nanoTime()
            val choice = index.planLiveJourney(45.0703, 7.6869, 45.061, 7.678, vehicles,
                walkingPreference = preference)
            println("Bundled network $preference: ${(System.nanoTime() - started) / 1_000_000} ms, ${vehicles.size} vehicles")
            assertNotNull(choice)
        }
    }

    @Test fun missingAndUnknownTripsCanPlanUsingRouteAndHeading() = runBlocking {
        val reverse = first.copy(id = "reverse", directionId = "1", tripIds = setOf("reverse"),
            stops = first.stops.reversed())
        for (trip in listOf(null, "new-unindexed-trip")) {
            val choice = index(first, reverse).planLiveJourney(45.01, 7.0, 45.03, 7.0,
                listOf(vehicle(first, 45.0, 7.0).copy(tripId = trip, bearing = 5f)))
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
        assertNull(index.matchDirection(bus.copy(longitude = 7.1, bearing = 5f)))
        assertFalse(index.isStopIndexAhead(bus.copy(longitude = 7.1), first, 1))
        assertNull(index.matchDirection(bus.copy(routeId = "unknown", bearing = 5f)))
        assertNull(index(first).matchDirection(bus.copy(bearing = 180f)))
    }

    @Test fun movementResolvesDirectionWhenTheFeedGivesNoBearing() {
        val reverse = first.copy(id = "reverse", directionId = "1", tripIds = setOf("reverse"),
            stops = first.stops.reversed())
        val index = index(first, reverse)
        val now = System.currentTimeMillis() / 1000
        val earlier = vehicle(first, 45.003, 7.0).copy(tripId = null, timestampSeconds = now - 30)
        assertNull(index.matchDirection(earlier))
        val later = earlier.copy(latitude = 45.005, timestampSeconds = now)
        assertEquals("first", index.matchDirection(later)!!.id)
        // The same fix again, and a jittery one, keep the heading from real movement.
        assertEquals("first", index.matchDirection(later)!!.id)
        assertEquals("first", index.matchDirection(
            later.copy(latitude = 45.00501, timestampSeconds = now + 15))!!.id)
    }

    @Test fun clearMovementOverrulesAContradictoryFeedBearing() {
        val reverse = first.copy(id = "reverse", directionId = "1", tripIds = setOf("reverse"),
            stops = first.stops.reversed())
        val index = index(first, reverse)
        val now = System.currentTimeMillis() / 1000
        val staleBearing = vehicle(first, 45.002, 7.0).copy(
            tripId = null, bearing = 180f, timestampSeconds = now - 30)
        assertEquals("reverse", index.matchDirection(staleBearing)!!.id)
        assertEquals("first", index.matchDirection(
            staleBearing.copy(latitude = 45.005, timestampSeconds = now))!!.id)
    }

    @Test fun recentMatchBreaksTiesButDoesNotOutliveTheRun() {
        val reverse = first.copy(id = "reverse", directionId = "1", tripIds = setOf("reverse"),
            stops = first.stops.reversed())
        val index = index(first, reverse)
        val now = System.currentTimeMillis() / 1000
        val known = vehicle(first, 45.005, 7.0).copy(tripId = null, bearing = 5f, timestampSeconds = now - 60)
        assertEquals("first", index.matchDirection(known)!!.id)
        val unknown = known.copy(bearing = null, timestampSeconds = now - 45)
        assertEquals("first", index.matchDirection(unknown)!!.id)
        // Much later the earlier run is no evidence of where the bus is heading now.
        assertNull(index.matchDirection(unknown.copy(timestampSeconds = now + 600)))
    }

    @Test fun reusedVehicleIdDoesNotInheritMovementFromAFarAwayRun() {
        val reverse = first.copy(id = "reverse", directionId = "1", tripIds = setOf("reverse"),
            stops = first.stops.reversed())
        val index = index(first, reverse)
        val now = System.currentTimeMillis() / 1000
        val farAway = vehicle(first, 45.2, 7.0).copy(tripId = null, bearing = 5f, timestampSeconds = now - 20)
        index.matchDirection(farAway)
        val nearby = farAway.copy(latitude = 45.005, bearing = null, timestampSeconds = now)
        assertNull(index.matchDirection(nearby))
    }

    @Test fun bearingZeroIsTreatedAsUnknownUntilMovementBacksItUp() {
        val reverse = first.copy(id = "reverse", directionId = "1", tripIds = setOf("reverse"),
            stops = first.stops.reversed())
        val now = System.currentTimeMillis() / 1000
        val bus = vehicle(first, 45.003, 7.0).copy(tripId = null, bearing = 0f, timestampSeconds = now - 30)
        // Alone, a 0 could be a bus heading south with no real bearing.
        assertNull(index(first, reverse).matchDirection(bus))
        val northbound = index(first, reverse)
        northbound.matchDirection(bus)
        assertEquals("first", northbound.matchDirection(
            bus.copy(latitude = 45.005, timestampSeconds = now))!!.id)
        val southbound = index(first, reverse)
        southbound.matchDirection(bus.copy(latitude = 45.007))
        assertEquals("reverse", southbound.matchDirection(
            bus.copy(latitude = 45.005, timestampSeconds = now))!!.id)
    }

    @Test fun displayHeadingFollowsTheRouteWhenTheFeedBearingIsMissingOrWrong() {
        val index = index(first)
        val onRoute = vehicle(first, 45.005, 7.0)
        assertEquals(10f, index.displayHeading(onRoute.copy(bearing = 10f))!!, 0.01f)
        assertEquals(0f, index.displayHeading(onRoute.copy(bearing = null))!!, 1f)
        assertEquals(0f, index.displayHeading(onRoute.copy(bearing = 0f))!!, 1f)
        assertEquals(0f, index.displayHeading(onRoute.copy(bearing = 180f))!!, 1f)
        // Away from the line the route says nothing about where the bus points.
        val offRoute = onRoute.copy(longitude = 7.01, bearing = 180f)
        assertEquals(180f, index.displayHeading(offRoute)!!, 0.01f)
        assertNull(index.displayHeading(offRoute.copy(bearing = null)))
    }

    @Test fun aGuessedBusPointsOnlyOnceItIsSeenMovingThatWay() {
        val index = index(first)
        val now = System.currentTimeMillis() / 1000
        // No trip: GTT's bearing alone is wrong too often to draw.
        val guessed = vehicle(first, 45.002, 7.0).copy(tripId = null, bearing = 5f, timestampSeconds = now - 30)
        assertNull(index.displayHeading(guessed))
        // Seen 330 m further north half a minute later: now it points, along the line.
        assertEquals(0f, index.displayHeading(guessed.copy(latitude = 45.005, timestampSeconds = now))!!, 1f)
        // A bus on a known trip points straight away.
        assertEquals(0f, index.displayHeading(vehicle(first, 45.005, 7.0).copy(bearing = 0f))!!, 1f)
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
        val bus = vehicle(first, 45.015, 7.0).copy(tripId = null, bearing = 5f)
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

    @Test fun walkingPresetCanChooseALongerTripWithLessWalking() = runBlocking {
        val fast = pattern("fast", listOf(
            stop("fast-start", 44.999, 7.0), stop("fast-board", 45.0, 7.0),
            stop("fast-exit", 45.025, 7.0)
        ))
        val shorterWalk = pattern("shorter-walk", listOf(
            stop("slow-start", 44.968, 7.0), stop("slow-board", 45.0, 7.0),
            stop("slow-exit", 45.03, 7.0)
        ))
        val routes = index(fast, shorterWalk)
        val vehicles = listOf(vehicle(fast, 44.999, 7.0), vehicle(shorterWalk, 44.968, 7.0))
        val fastest = routes.planLiveJourney(45.0, 7.0, 45.03, 7.0, vehicles,
            walkingPreference = WalkingPreference.MORE)!!
        val lessWalking = routes.planLiveJourney(45.0, 7.0, 45.03, 7.0, vehicles,
            walkingPreference = WalkingPreference.LESS)!!

        assertEquals("fast", fastest.pattern.id)
        assertEquals("shorter-walk", lessWalking.pattern.id)
        assertTrue(fastest.estimatedTotalSeconds < lessWalking.estimatedTotalSeconds)
        assertTrue(fastest.totalWalkingMetres > lessWalking.totalWalkingMetres)
    }

    @Test fun aSpikeOntoADistantOverlappingSegmentDoesNotDragProgressForward() {
        // Out along one line, back along (almost) the same one: a fix near the way back must not
        // make the bus look as if it had skipped the whole route.
        val out = (0..14).map { stop("out$it", 45.0 + 0.002 * it, 7.0) }
        val back = (15..29).map { stop("back$it", 45.028 - 0.002 * (it - 15), 7.0001) }
        val loop = pattern("loop", out + back)
        val index = index(loop)
        val onTheWayOut = vehicle(loop, 45.005, 7.0)
        assertTrue(index.isStopIndexAhead(onTheWayOut, loop, 3))
        // Closer to the return leg's line (segment 26) than to the outbound one (segment 2).
        val spike = onTheWayOut.copy(longitude = 7.00009)
        assertTrue("Progress must stay near segment 2", index.isStopIndexAhead(spike, loop, 3))
        // Genuinely somewhere else entirely: the old position is no evidence any more.
        val farOnTheWayBack = onTheWayOut.copy(latitude = 45.026, longitude = 7.0001)
        assertFalse(index.isStopIndexAhead(farOnTheWayBack, loop, 3))
    }

    @Test fun tyingVariantsOfOneDirectionDoNotFlipBetweenFixes() {
        val trunk = pattern("trunk", listOf(
            stop("a", 45.000, 7.0), stop("b", 45.010, 7.0), stop("c", 45.020, 7.0)))
        val branch = trunk.copy(id = "branch", headsign = "other", tripIds = setOf("branch"),
            stops = listOf(stop("a", 45.000, 7.0), stop("b2", 45.010, 7.0002), stop("c", 45.020, 7.0)))
        val now = System.currentTimeMillis() / 1000
        for (patterns in listOf(arrayOf(trunk, branch), arrayOf(branch, trunk))) {
            val index = index(*patterns)
            val onBranch = vehicle(trunk, 45.010, 7.0002).copy(
                tripId = null, bearing = 5f, timestampSeconds = now - 10)
            assertEquals("branch", index.matchDirection(onBranch)!!.id)
            // 16 m away and nearer the other variant, but not by enough to be evidence.
            val sameBusLater = onBranch.copy(longitude = 7.0, timestampSeconds = now)
            assertEquals("branch", index.matchDirection(sameBusLater)!!.id)
        }
    }

    @Test fun secondLegRidesToTheExitThatIsQuickestNotMerelyClosest() = runBlocking {
        val first = pattern("first", listOf(
            stop("start", 45.0, 7.0), stop("board", 45.01, 7.0), stop("transfer", 45.02, 7.0)))
        // Reaching "far" saves a few metres of walking, but only after a long detour.
        val detour = (1..10).map { stop("detour$it", 45.02, 7.0295 + 0.01 * it) }
        val second = pattern("second", listOf(
            stop("start2", 45.02, 6.95), stop("transfer", 45.02, 7.0),
            stop("near", 45.02, 7.0295)) + detour + stop("far", 45.0205, 7.0299))
        val choice = index(first, second).planLiveJourney(45.01, 7.0, 45.0205, 7.03,
            listOf(vehicle(first, 45.0, 7.0), vehicle(second, 45.02, 6.96)))
        assertNotNull(choice?.secondLeg)
        assertEquals("near", choice!!.secondLeg!!.exitAt.id)
    }

    @Test fun routeMetresToStopFollowsTheRouteAndIgnoresPassedStops() {
        val index = index(first)
        val bus = vehicle(first, 45.005, 7.0)
        val toTransfer = index.routeMetresToStop(bus, first, "transfer")!!
        // Half a leg to "board", then a full leg to "transfer".
        assertEquals(555f + 1_112f, toTransfer, 25f)
        assertEquals(555f, index.routeMetresToStop(bus, first, "board")!!, 15f)
        assertNull(index.routeMetresToStop(bus, first, "start"))
        // Off the line altogether (a diversion): all that is known is how far the stop is.
        assertEquals(7_900f, index.routeMetresToStop(bus.copy(longitude = 7.1), first, "board")!!, 150f)
    }

    @Test fun trackedVehiclesCanBeSharedAcrossPlans() = runBlocking {
        val index = index(first, second)
        val fleet = listOf(vehicle(first, 45.0, 7.0), vehicle(second, 45.02, 6.96))
        val tracked = index.trackVehicles(fleet)
        assertEquals(2, tracked.size)
        val own = index.planLiveJourney(45.01, 7.0, 45.02, 7.03, fleet)
        val shared = index.planLiveJourney(45.01, 7.0, 45.02, 7.03, emptyList(), tracked = tracked)
        assertEquals(own?.transferAt?.id, shared?.transferAt?.id)
        assertEquals(own!!.estimatedTotalSeconds, shared!!.estimatedTotalSeconds, 1f)
    }

    @Test fun aBusTheWalkerOnlyJustReachesIsNotOffered() = runBlocking {
        // 111 m (92 s at the walking pace) against a bus about 128 s away leaves slack;
        // 150 m (125 s) leaves none once real walkers' pace is allowed for.
        val bus = vehicle(first, 45.004, 7.0)
        assertNotNull(index(first).planLiveJourney(45.01, 7.0014, 45.03, 7.0, listOf(bus)))
        assertNull(index(first).planLiveJourney(45.01, 7.0019, 45.03, 7.0, listOf(bus)))
    }

    @Test fun aBusWithNoTripRunsTheVariantOfItsDay() {
        // Like the 67: the Sunday runs go on past the weekday terminus, along the same road until then.
        val trunk = listOf(stop("a", 45.0, 7.0), stop("b", 45.01, 7.0), stop("c", 45.02, 7.0))
        val sunday = TransitPattern("sunday", "67", "67", "Borgo Vittoria", "0", setOf("s"),
            trunk + stop("d", 45.03, 7.0), serviceIds = setOf("sun"))
        val weekday = TransitPattern("weekday", "67", "67", "Arbarello", "0", setOf("w"),
            trunk, serviceIds = setOf("wk"))
        val index = index(sunday, weekday)
        index.serviceDates = mapOf("wk" to setOf("20261014", "20261015"), "sun" to setOf("20261018"))
        fun busAt(time: String) = LiveVehicle("bus-$time", "67", null, 45.005, 7.0, 0f,
            java.time.LocalDateTime.parse(time).atZone(java.time.ZoneId.of("Europe/Rome")).toEpochSecond())

        assertEquals("weekday", index.matchDirection(busAt("2026-10-14T10:00:00"))!!.id)
        assertEquals("sunday", index.matchDirection(busAt("2026-10-18T10:00:00"))!!.id)
        // At 01:00 on Monday, Sunday's night runs are still out.
        assertEquals("sunday", index.matchDirection(busAt("2026-10-19T01:00:00"))!!.id)
        // Past the timetable's end, the day says nothing and the bus still gets a direction.
        assertNotNull(index.matchDirection(busAt("2027-03-01T10:00:00")))
    }

    @Test fun theTripUpdatesSayWhichVariantABusWithNoTripIsOn() {
        // Two runs of one line on the same day, alike until the short one ends.
        val trunk = listOf(stop("a", 45.0, 7.0), stop("b", 45.01, 7.0), stop("c", 45.02, 7.0))
        val long = TransitPattern("long", "67", "67", "Borgo Vittoria", "0", setOf("L"), trunk + stop("d", 45.03, 7.0))
        val short = TransitPattern("short", "67", "67", "Zara", "0", setOf("S"), trunk)
        val back = TransitPattern("back", "67", "67", "Moncalieri", "1", setOf("B"), trunk.reversed())
        val index = index(short, long, back)
        // Heading north (a bearing of 0 is how the feed says it has none).
        val bus = LiveVehicle("bus", "67", null, 45.005, 7.0, 5f, System.currentTimeMillis() / 1000)

        assertEquals("long", index.matchDirection(bus.copy(tripHints = listOf("L")))!!.id)
        assertEquals("short", index.matchDirection(bus.copy(id = "bus2", tripHints = listOf("S")))!!.id)
        // Finishing its run northwards, with the next one back already in the updates.
        assertEquals("long", index.matchDirection(bus.copy(id = "bus3", tripHints = listOf("L", "B")))!!.id)
        // A trip the index does not know leaves the usual guess.
        assertNotNull(index.matchDirection(bus.copy(id = "bus4", tripHints = listOf("unknown"))))
    }

    @Test fun aRideFollowsItsRoadsBetweenTheStopsItUses() {
        // The road bends east between each pair of stops.
        val road = RouteShape(doubleArrayOf(45.0, 45.005, 45.01, 45.015, 45.02),
            doubleArrayOf(7.0, 7.003, 7.0, 7.003, 7.0))
        val stops = listOf(stop("a", 45.0, 7.0), stop("b", 45.01, 7.0), stop("c", 45.02, 7.0))
        val shaped = TransitPattern("shaped", "M", "M1", "Bengasi", "0", emptySet(), stops, shape = road)
        val index = index(shaped)

        val ride = index.pathBetween(shaped, 1, 2)
        assertEquals(listOf(45.01, 45.015, 45.02), ride.map { it.latitude })
        assertEquals(7.003, ride[1].longitude, 1e-9)
        // The metro, planned from its timetable, borrows the same line by its stations.
        assertEquals(ride, index.pathAlongRoute("M1", "b", "c"))
        assertNull(index.pathAlongRoute("M1", "c", "a"))
        // Without a shape, straight from stop to stop.
        val plain = shaped.copy(id = "plain", shape = null)
        assertEquals(listOf(45.0, 45.01, 45.02), index(plain).pathBetween(plain, 0, 2).map { it.latitude })
    }

    @Test fun aBusKnownOnlyFromItsTripIsPlacedHalfwayToItsNextStop() {
        val line = pattern("line", listOf(stop("a", 45.00, 7.6), stop("b", 45.01, 7.6), stop("c", 45.03, 7.6)))
        val index = index(line)
        fun heading(next: Int) = LiveVehicle("ghost", "", "line", 0.0, 0.0, null,
            System.currentTimeMillis() / 1000, nextStopSequence = next)

        // Stop 3 is "c": the bus is between "b" and "c", going north.
        val placed = index.inService(listOf(heading(3))).single()
        assertEquals(45.02, placed.latitude, 1e-9)
        assertEquals(7.6, placed.longitude, 1e-9)
        assertEquals(0f, placed.bearing!!, 1f)
        assertEquals("line", placed.routeId)
        assertTrue(placed.estimated)
        // Before its first stop, past its last, or on a trip the index does not know: not placed.
        assertTrue(index.inService(listOf(heading(1), heading(4))).isEmpty())
        assertTrue(index.inService(listOf(heading(2).copy(tripId = "unknown"))).isEmpty())
        // A bus with a real position is left where it is.
        val real = vehicle(line, 45.005, 7.6)
        assertEquals(real, index.inService(listOf(real)).single())
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
