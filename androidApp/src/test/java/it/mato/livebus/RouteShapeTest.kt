package it.mato.livebus

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class RouteShapeTest {
    // Two parallel streets about 111 m apart, joined at the east end: out along one, back along the other.
    private val outAndBack = RouteShape(
        doubleArrayOf(45.000, 45.000, 45.001, 45.001),
        doubleArrayOf(7.000, 7.020, 7.020, 7.000)
    )
    private val stops = listOf(
        TransitStop("out-1", "Out 1", 45.000, 7.002), TransitStop("out-2", "Out 2", 45.000, 7.018),
        TransitStop("back-1", "Back 1", 45.001, 7.018), TransitStop("back-2", "Back 2", 45.001, 7.002)
    )

    @Test fun decodesTheReferencePolyline() {
        val shape = RouteShape.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")!!
        assertArrayEquals(doubleArrayOf(38.5, 40.7, 43.252), shape.latitudes, 1e-9)
        assertArrayEquals(doubleArrayOf(-120.2, -120.95, -126.453), shape.longitudes, 1e-9)
        assertNull(RouteShape.decode(""))
        assertNull(RouteShape.decode("_p~iF"))
    }

    @Test fun alignmentNeverRunsBackwardsOnARouteThatReturnsAlongANearbyStreet() {
        val alignment = outAndBack.align(stops)!!
        // The last stop lies 111 m from the first street but on the second one; it must go on the way back.
        assertTrue(alignment.chainages.toList().zipWithNext().all { (a, b) -> b >= a })
        assertTrue(alignment.chainages[3] > outAndBack.lengthMetres - 400)
        assertTrue(alignment.chainages[0] < 300)
        assertTrue(alignment.worstMetres < 1.0)
    }

    @Test fun nearestStaysInsideTheRequestedStretch() {
        val alignment = outAndBack.align(stops)!!
        // Closer to the outbound street, yet the bus is on the way back between the last two stops.
        val fix = outAndBack.nearest(45.0004, 7.010, alignment.chainages[2], alignment.chainages[3])!!
        assertTrue(fix.chainage > alignment.chainages[2])
        val slice = outAndBack.slice(0.0, fix.chainage)
        assertEquals(4, slice.size)
        assertEquals(45.001, slice.last().latitude, 1e-6)
        assertEquals(7.010, slice.last().longitude, 1e-4)
    }

    @Test fun sliceInterpolatesBothEndsAndKeepsTheBendsBetween() {
        // 1 km along the outbound street: a thousand metres is 7.0127 degrees of longitude at 45 N.
        val slice = outAndBack.slice(1_000.0, outAndBack.lengthMetres)
        assertEquals(7.0127, slice.first().longitude, 0.0002)
        assertEquals(listOf(45.000, 45.000, 45.001, 45.001), slice.map { it.latitude })
        assertEquals(7.000, slice.last().longitude, 1e-9)
    }

    @Test fun courseFollowsTheShapeAndFallsBackToStopsWithoutOne() {
        fun indexOf(pattern: TransitPattern) = TransitIndex::class.java.getDeclaredConstructor(List::class.java)
            .apply { isAccessible = true }.newInstance(listOf(pattern)) as TransitIndex
        val bus = LiveVehicle("bus", "r", "trip", 45.001, 7.010, 270f, System.currentTimeMillis() / 1000)

        val shaped = TransitPattern("p", "r", "r", "End", "0", setOf("trip"), stops, outAndBack)
        val course = indexOf(shaped).courseOf(bus, shaped)!!
        // The corner at the east end is on the road, not between stops.
        assertTrue(course.passed.any { it.latitude == 45.000 && it.longitude == 7.020 })
        assertEquals(7.010, course.passed.last().longitude, 1e-4)
        assertEquals(7.000, course.ahead.last().longitude, 1e-9)
        assertEquals(listOf("out-1", "out-2", "back-1"), course.passedStops.map { it.id })
        assertEquals(listOf("back-2"), course.aheadStops.map { it.id })

        val bare = shaped.copy(shape = null)
        val straight = indexOf(bare).courseOf(bus, bare)!!
        assertFalse(straight.passed.any { it.longitude == 7.020 })
        assertEquals(bus.longitude, straight.ahead.first().longitude, 1e-9)
    }
}
