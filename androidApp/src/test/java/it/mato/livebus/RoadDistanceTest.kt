package it.mato.livebus

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RoadDistanceTest {
    private val now = System.currentTimeMillis() / 1000
    private val speed = SpeedEstimator.DEFAULT_METRES_PER_SECOND
    private val stops = listOf(
        TransitStop("a", "a", 45.000, 7.000),
        TransitStop("b", "b", 45.000, 7.020),
        TransitStop("c", "c", 45.000, 7.040))

    // The road from a to b climbs 1.1 km north, runs east and comes back down: 3.8 km for 1.6 km.
    private val detour = RouteShape(
        doubleArrayOf(45.000, 45.010, 45.010, 45.000, 45.000),
        doubleArrayOf(7.000, 7.000, 7.020, 7.020, 7.040))

    private fun index(pattern: TransitPattern) = TransitIndex::class.java
        .getDeclaredConstructor(List::class.java).apply { isAccessible = true }.newInstance(listOf(pattern))

    private fun bus(lat: Double, lon: Double) = LiveVehicle("bus", "r", "r", lat, lon, null, now)

    private fun arrivals(pattern: TransitPattern, vehicle: LiveVehicle) = runBlocking {
        index(pattern).trackVehicles(listOf(vehicle), now).single().arrivalSeconds
    }

    @Test fun stopToStopDistanceFollowsTheShapeWhenThereIsOne() {
        val withShape = TransitPattern("r", "r", "R", "R", "0", setOf("r"), stops, shape = detour)
        val straightOnly = withShape.copy(shape = null)
        val start = bus(45.0, 7.0)
        val onRoad = arrivals(withShape, start)
        val crowFlies = arrivals(straightOnly, start)
        // Stop b: ~3.8 km of road against 1.6 km * 1.15 by the straight lines.
        assertEquals(3_800f / speed, onRoad[1], 60f)
        assertEquals(1_575f * 1.15f / speed, crowFlies[1], 20f)
        // From b to c the road is straight, so no circuity is added on top of it.
        assertEquals(1_572f / speed + 18f, onRoad[2] - onRoad[1], 10f)
    }

    @Test fun theVehicleIsMeasuredFromWhereItIsOnTheRoadNotThroughTheBlocks() {
        val withShape = TransitPattern("r", "r", "R", "R", "0", setOf("r"), stops, shape = detour)
        // Three quarters of the way round the detour, nearly at b by the road: ~1 km to go on it.
        val nearlyThere = bus(45.0045, 7.020)
        val onRoad = arrivals(withShape, nearlyThere)
        assertTrue("was ${onRoad[1]}", onRoad[1] < 1_200f / speed)
        assertTrue("was ${onRoad[1]}", onRoad[1] > 300f / speed)
    }

    @Test fun withoutAShapeThePlannerKeepsTheStraightLineAndCircuity() {
        val plain = TransitPattern("r", "r", "R", "R", "0", setOf("r"), stops)
        val onRoad = arrivals(plain, bus(45.0, 7.0))
        assertEquals((1_575f + 1_575f) * 1.15f / speed + 18f, onRoad[2], 25f)
    }

    @Test fun aShapeThatDoesNotFitTheStopsIsIgnored() {
        // 300 m from every stop: the wrong line for this pattern.
        val elsewhere = RouteShape(doubleArrayOf(45.003, 45.003), doubleArrayOf(7.0, 7.04))
        val wrong = TransitPattern("r", "r", "R", "R", "0", setOf("r"), stops, shape = elsewhere)
        val plain = wrong.copy(shape = null)
        val start = bus(45.0, 7.0)
        assertArrayEquals(arrivals(plain, start), arrivals(wrong, start), 0.5f)
    }

    @Test fun aWildShapeDetourIsCapped() {
        // A shape that pins stop b to a point 50 km round a loop must not make b look hours away.
        val loop = RouteShape(
            doubleArrayOf(45.000, 45.4, 45.4, 45.000, 45.000),
            doubleArrayOf(7.000, 7.000, 7.020, 7.020, 7.040))
        val loopy = TransitPattern("r", "r", "R", "R", "0", setOf("r"), stops, shape = loop)
        val capped = arrivals(loopy, bus(45.0, 7.0))
        // At most the capped stretch (2.5x the crow flies and 200 m) plus a little slack for the bus.
        assertTrue("was ${capped[1]}", capped[1] <= (1_575f * 2.5f + 200f + 150f) / speed + 5f)
    }
}
