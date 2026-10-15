package it.mato.livebus

import android.app.Application
import android.location.Location
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class JourneyPlannerTest {
    private val trains by lazy { TrainIndex.load(RuntimeEnvironment.getApplication()) }
    private val portaNuova get() = trains.stations.first { it.name == "Torino Porta Nuova" }
    private val lingotto get() = trains.stations.first { it.name == "Torino Lingotto" }

    @Test fun moreWalkingAllowsAStationOutsideTheShortWalkingPreset() = runBlocking {
        val station = portaNuova
        val destination = lingotto
        val originLatitude = 45.09
        val originWalk = { lat: Double, lon: Double ->
            distance(originLatitude, station.longitude, lat, lon)
        }
        val destinationWalk = { lat: Double, lon: Double ->
            distance(destination.latitude, destination.longitude, lat, lon)
        }
        val now = LocalDateTime.of(2026, 10, 15, 9, 15)
        assertNull(trains.planScheduledJourney(originLatitude, station.longitude,
            destination.latitude, destination.longitude, now, originWalk, destinationWalk,
            boardStopId = station.id, exitStopId = destination.id,
            walkingPreference = WalkingPreference.LESS))
        assertNotNull(trains.planScheduledJourney(originLatitude, station.longitude,
            destination.latitude, destination.longitude, now, originWalk, destinationWalk,
            boardStopId = station.id, exitStopId = destination.id,
            walkingPreference = WalkingPreference.MORE))
    }

    @Test fun trainNeedsTwoMinutesForBoardingAfterTheWalk() = runBlocking {
        val station = portaNuova
        val destination = lingotto
        val now = LocalDateTime.of(2026, 10, 15, 9, 24)
        suspend fun journeyAfterWalking(metres: Float) = trains.planScheduledJourney(
            station.latitude, station.longitude, destination.latitude, destination.longitude,
            now, walkFromOrigin = { _, _ -> metres },
            walkFromDestination = { _, _ -> 0f },
            boardStopId = station.id, exitStopId = destination.id
        )

        val catchable = journeyAfterWalking(220f)!!
        assertEquals(9 * 3600 + 30 * 60, catchable.departureSeconds)
        assertEquals((catchable.arrivalSeconds - 9 * 3600 - 24 * 60).toFloat(),
            catchable.estimatedTotalSeconds, 0.1f)
        assertTrue(journeyAfterWalking(300f)!!.departureSeconds > catchable.departureSeconds)
    }

    @Test fun afterTheLastTrainThePlannerOffersTomorrowsFirstOne() = runBlocking {
        val station = portaNuova
        val destination = lingotto
        val late = trains.planScheduledJourney(
            station.latitude, station.longitude, destination.latitude, destination.longitude,
            LocalDateTime.of(2026, 10, 15, 23, 58, 0),
            walkFromOrigin = { _, _ -> 0f }, walkFromDestination = { _, _ -> 0f },
            boardStopId = station.id, exitStopId = destination.id)
        assertNotNull("Tomorrow's timetable is in the bundled data", late)
        assertEquals(1, late!!.dayOffset)
        // Waiting overnight is part of the journey: more than the hours until the first train.
        assertTrue(late.estimatedTotalSeconds > late.departureSeconds + 120)
    }

    @Test fun anAfterMidnightRunBelongsToYesterdaysTimetable() = runBlocking {
        fun stop(id: String, lat: Double, arrival: Int, departure: Int) =
            TrainStop(id, id, lat, 7.0, arrival, departure)
        val stops = listOf(stop("a", 45.0, 24 * 3600 + 600, 24 * 3600 + 660),
            stop("b", 45.1, 25 * 3600, 25 * 3600 + 60))
        val tripClass = Class.forName("it.mato.livebus.TrainIndex\$TrainTrip")
        val trip = tripClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance("1", "REGIONALE", "B", setOf("20260924"), stops)
        val index = TrainIndex::class.java.getDeclaredConstructor(List::class.java)
            .apply { isAccessible = true }.newInstance(listOf(trip)) as TrainIndex
        // 00:05 on the 25th: the 24th's timetable is still running.
        val journey = index.planScheduledJourney(45.0, 7.0, 45.1, 7.0,
            LocalDateTime.of(2026, 9, 25, 0, 5, 0),
            walkFromOrigin = { _, _ -> 0f }, walkFromDestination = { _, _ -> 0f })
        assertNotNull(journey)
        assertEquals(0, journey!!.dayOffset)
        // 00:10 departure is five minutes after 00:05, the arrival fifty-five.
        assertEquals(55 * 60f, journey.estimatedTotalSeconds, 1f)
    }

    @Test fun liveBusCanConnectToScheduledTrain() = runBlocking {
        val station = portaNuova
        val destination = lingotto
        val route = pattern(listOf(
            stop("start", 45.09, station.longitude),
            stop("board", 45.08, station.longitude),
            stop("station", station.latitude, station.longitude)
        ))
        val choice = planner(route).planMixedJourney(
            45.08, station.longitude, destination.latitude, destination.longitude,
            listOf(vehicle(route)), LocalDateTime.of(2026, 10, 15, 9, 15),
            { lat, lon -> distance(45.08, station.longitude, lat, lon) },
            { lat, lon -> distance(destination.latitude, destination.longitude, lat, lon) },
            ::distance)

        assertNotNull(choice)
        assertTrue(choice!!.busFirst)
        assertEquals(station.id, choice.train.boardAt.id)
        assertEquals(destination.id, choice.train.destination.id)
        assertTrue(choice.train.departureSeconds >= 9 * 3600 + 30 * 60)
        assertTrue(choice.estimatedTotalSeconds > choice.bus.estimatedTotalSeconds)
    }

    @Test fun trainDepartureMustAllowTimeForBusAndBoarding() = runBlocking {
        val station = portaNuova
        val destination = lingotto
        val route = pattern(listOf(
            stop("start", 45.10, station.longitude),
            stop("board", 45.09, station.longitude),
            stop("station", station.latitude, station.longitude)
        ))
        val choice = planner(route).planMixedJourney(
            45.09, station.longitude, destination.latitude, destination.longitude,
            listOf(vehicle(route)), LocalDateTime.of(2026, 10, 15, 9, 20),
            { lat, lon -> distance(45.09, station.longitude, lat, lon) },
            { lat, lon -> distance(destination.latitude, destination.longitude, lat, lon) },
            ::distance)

        assertNotNull(choice)
        assertTrue(choice!!.busFirst)
        assertTrue(choice.train.departureSeconds > 9 * 3600 + 30 * 60)
    }

    @Test fun scheduledTrainCanConnectToLiveBusOnlyIfItHasNotPassed() = runBlocking {
        val station = lingotto
        val destination = stop("destination", 45.005, station.longitude)
        val route = pattern(listOf(
            stop("start", 45.07, station.longitude),
            stop("board", station.latitude, station.longitude),
            destination
        ))
        val time = LocalDateTime.of(2026, 10, 15, 9, 26)
        val catchable = planner(route).planMixedJourney(
            portaNuova.latitude, portaNuova.longitude,
            destination.latitude, destination.longitude,
            listOf(vehicle(route)), time,
            { lat, lon -> distance(portaNuova.latitude, portaNuova.longitude, lat, lon) },
            { lat, lon -> distance(destination.latitude, destination.longitude, lat, lon) },
            ::distance)
        assertNotNull(catchable)
        assertFalse(catchable!!.busFirst)
        assertEquals(station.id, catchable.train.destination.id)
        assertEquals("board", catchable.bus.boardAt.id)

        val earlyRoute = route.copy(stops = listOf(
            stop("start", 45.045, station.longitude), route.stops[1], destination))
        val missed = planner(earlyRoute).planMixedJourney(
            portaNuova.latitude, portaNuova.longitude,
            destination.latitude, destination.longitude,
            listOf(vehicle(earlyRoute)), time,
            { lat, lon -> distance(portaNuova.latitude, portaNuova.longitude, lat, lon) },
            { lat, lon -> distance(destination.latitude, destination.longitude, lat, lon) },
            ::distance)
        assertNull(missed)
    }

    private fun metroStation(name: String) = trains.stations.first { it.name == "Metro $name" }

    @Test fun theMetroIsPlannedFromItsTimetable() = runBlocking {
        val fermi = metroStation("Fermi")
        val portaSusa = metroStation("Porta Susa")
        val journey = trains.metroOnly.planScheduledJourney(
            fermi.latitude, fermi.longitude, portaSusa.latitude, portaSusa.longitude,
            LocalDateTime.of(2026, 10, 15, 8, 0),
            walkFromOrigin = { _, _ -> 0f }, walkFromDestination = { _, _ -> 0f },
            boardStopId = fermi.id, exitStopId = portaSusa.id)

        assertNotNull(journey)
        assertTrue(journey!!.isMetro)
        assertEquals("M1", journey.number)
        assertEquals("Bengasi", journey.headsign)
        // At rush hour a train comes every few minutes, and Fermi to Porta Susa is under twenty.
        assertTrue(journey.departureSeconds - 8 * 3600 in 120..8 * 60)
        assertTrue(journey.arrivalSeconds - journey.departureSeconds < 20 * 60)
    }

    @Test fun leavingTrainsOutKeepsTheMetro() {
        assertTrue(trains.stations.any { it.name == "Torino Porta Nuova" })
        assertTrue(trains.stations.any { it.name == "Metro Porta Nuova" })
        val metro = trains.metroOnly.stations.map { it.name }
        assertEquals(23, metro.size)
        assertTrue(metro.all { it.startsWith("Metro ") })
    }

    @Test fun aBusCanConnectToTheMetroWithTrainsLeftOut() = runBlocking {
        val fermi = metroStation("Fermi")
        val portaNuova = metroStation("Porta Nuova")
        val route = pattern(listOf(
            stop("start", fermi.latitude + 0.02, fermi.longitude),
            stop("board", fermi.latitude + 0.01, fermi.longitude),
            stop("station", fermi.latitude, fermi.longitude)
        ))
        val options = planner(route).plan(JourneyQuery(
            fermi.latitude + 0.01, fermi.longitude, portaNuova.latitude, portaNuova.longitude,
            listOf(vehicle(route)),
            walkFromOrigin = { lat, lon -> distance(fermi.latitude + 0.01, fermi.longitude, lat, lon) },
            walkFromDestination = { lat, lon -> distance(portaNuova.latitude, portaNuova.longitude, lat, lon) },
            walkBetweenStops = ::distance,
            trainsAt = LocalDateTime.of(2026, 10, 15, 8, 0),
            includeTrains = false))

        val mixed = options.firstNotNullOfOrNull { it.mixed }
        assertNotNull(mixed)
        assertTrue(mixed!!.busFirst)
        assertTrue(mixed.train.isMetro)
        assertEquals(fermi.id, mixed.train.boardAt.id)
        assertEquals(portaNuova.id, mixed.train.destination.id)
        assertTrue(options.mapNotNull { it.mixed }.all { it.train.isMetro })
    }

    private fun pattern(stops: List<TransitStop>) =
        TransitPattern("test", "test", "T", "Destination", "0", setOf("trip"), stops)

    private fun stop(id: String, latitude: Double, longitude: Double) =
        TransitStop(id, id, latitude, longitude)

    private fun vehicle(pattern: TransitPattern) = LiveVehicle("bus", pattern.routeId, "trip",
        pattern.stops.first().latitude, pattern.stops.first().longitude,
        null, System.currentTimeMillis() / 1000)

    private fun planner(pattern: TransitPattern): JourneyPlanner {
        val buses = TransitIndex::class.java.getDeclaredConstructor(List::class.java)
            .apply { isAccessible = true }.newInstance(listOf(pattern))
        return JourneyPlanner(buses, trains)
    }

    private fun distance(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(fromLat, fromLon, toLat, toLon, result)
        return result[0]
    }
}
