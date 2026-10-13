package it.mato.livebus

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Buses going to or from the depot still report their line, but they are not on it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DepotBusesTest {
    // North along one street for about 5.5 km; "last" is the final trip of a bus's duty.
    private val stops = listOf(stop("A", 45.0), stop("B", 45.025), stop("C", 45.05))
    private val line = TransitPattern("L", "L", "L", "Terminus", "0", setOf("mid", "last"), stops,
        lastTripIds = setOf("last"))
    private val now = System.currentTimeMillis() / 1000

    private fun stop(id: String, latitude: Double) = TransitStop(id, id, latitude, 7.0)
    private fun bus(trip: String?, latitude: Double, longitude: Double = 7.0, route: String = "L", id: String = "bus") =
        LiveVehicle(id, route, trip, latitude, longitude, null, now)
    private fun index(): TransitIndex =
        TransitIndex::class.java.getDeclaredConstructor(List::class.java).apply { isAccessible = true }
            .newInstance(listOf(line))
    private fun TransitIndex.shows(vararg vehicles: LiveVehicle) = inService(vehicles.toList()).map { it.id }

    @Test fun aBusOnATripIsInService() {
        assertEquals(listOf("bus"), index().shows(bus("mid", 45.01)))
    }

    @Test fun aBusThatFinishedItsLastTripStaysGoneUntilItGetsAnotherTrip() {
        val index = index()
        assertEquals(listOf("bus"), index.shows(bus("last", 45.049)))
        // The trip ends and the feed stops giving one.
        assertEquals(emptyList<String>(), index.shows(bus(null, 45.0505)))
        // Driving away along the street it came by must not bring it back.
        assertEquals(emptyList<String>(), index.shows(bus(null, 45.07)))
        // Next morning.
        assertEquals(listOf("bus"), index.shows(bus("mid", 45.0)))
        // Its next wait at a terminus is a break, not the end: the trip it was on is not a last one.
        assertEquals(listOf("bus"), index.shows(bus(null, 45.0505)))
    }

    @Test fun aBusWaitingBetweenTwoTripsStays() {
        val index = index()
        index.shows(bus("mid", 45.049))
        assertEquals(listOf("bus"), index.shows(bus(null, 45.0505)))
    }

    @Test fun aTripGoingMissingHalfwayAlongTheLastTripIsTheFeedStutteringNotTheEnd() {
        val index = index()
        index.shows(bus("last", 45.025))
        assertEquals(listOf("bus"), index.shows(bus(null, 45.026)))
    }

    @Test fun oneBusFinishingDoesNotHideAnother() {
        val index = index()
        index.shows(bus("last", 45.049, id = "a"), bus("last", 45.049, id = "b"))
        assertEquals(listOf("b"), index.shows(bus("mid", 45.049, id = "b"), bus(null, 45.0505, id = "a")))
    }

    @Test fun aBusWithNoTripFarFromItsLineIsOnItsWayToTheDepot() {
        val index = index()
        // About 1.6 km east of the street.
        assertEquals(emptyList<String>(), index.shows(bus(null, 45.03, 7.02)))
        assertEquals(listOf("bus"), index.shows(bus(null, 45.03, 7.001)))
    }

    @Test fun aBusWithNoTripWhereNothingIsKnownAboutItIsLeftAlone() {
        val index = index()
        // Never seen on a trip, on its line: it may just be a bus the feed gives no trips for.
        assertEquals(listOf("bus"), index.shows(bus(null, 45.0505)))
        // A line the index does not know says nothing about where the bus should be.
        assertEquals(listOf("other"), index.shows(bus(null, 45.03, 7.02, route = "X", id = "other")))
    }

    @Test fun aTripTheIndexDoesNotKnowIsTakenAtItsWord() {
        // An out-of-date index must not hide buses that are plainly running.
        assertEquals(listOf("bus"), index().shows(bus("brand-new-trip", 45.03, 7.02)))
    }
}
