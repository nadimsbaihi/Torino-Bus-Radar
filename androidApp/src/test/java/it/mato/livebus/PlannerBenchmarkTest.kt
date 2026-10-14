package it.mato.livebus

import android.location.Location
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Old against new on the bundled timetable with about 400 buses spread over it. The timings go to
 * the log (look for BENCHMARK); the assertions are loose so a slow machine does not fail the build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class PlannerBenchmarkTest {
    private fun metres(a: Double, b: Double, c: Double, d: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(a, b, c, d, result)
        return result[0]
    }

    @Test fun roundPlannerIsNoSlowerThanTheNestedLoopsOnARealisticFleet() = runBlocking {
        val index = TransitIndex.load(RuntimeEnvironment.getApplication())
        val random = Random(42)
        val now = System.currentTimeMillis() / 1000
        val vehicles = index.patterns.filter { it.tripIds.isNotEmpty() && it.stops.size > 3 }
            .shuffled(random).take(400).mapIndexed { number, pattern ->
                val segment = random.nextInt(pattern.stops.size - 1)
                val a = pattern.stops[segment]
                val b = pattern.stops[segment + 1]
                val share = random.nextDouble()
                LiveVehicle("bench$number", pattern.routeId, pattern.tripIds.first(),
                    a.latitude + (b.latitude - a.latitude) * share, a.longitude + (b.longitude - a.longitude) * share,
                    null, now - random.nextInt(30))
            }
        val trackStart = System.nanoTime()
        val tracked = index.trackVehicles(vehicles, now)
        val trackMillis = (System.nanoTime() - trackStart) / 1_000_000
        assertTrue("Most vehicles should match a pattern", tracked.size > 300)

        val streets = 1.25f
        var oldNanos = 0L
        var newNanos = 0L
        var runs = 0
        var oldFound = 0
        var newFound = 0
        var missed = 0
        // Street-distance lookups are what costs on a phone, so count them too.
        var oldBetween = 0L; var newBetween = 0L; var oldWalks = 0L; var newWalks = 0L
        var counter = 0L
        var betweenCounter = 0L
        repeat(12) { round ->
            val fromLat = 45.03 + random.nextDouble() * 0.08
            val fromLon = 7.62 + random.nextDouble() * 0.10
            val toLat = fromLat + (random.nextDouble() - 0.5) * 0.05
            val toLon = fromLon + (random.nextDouble() - 0.5) * 0.07
            val fromOrigin = { lat: Double, lon: Double -> counter++; metres(fromLat, fromLon, lat, lon) * streets }
            val fromDestination = { lat: Double, lon: Double -> counter++; metres(toLat, toLon, lat, lon) * streets }
            val between = { a: Double, b: Double, c: Double, d: Double -> betweenCounter++; metres(a, b, c, d) * streets }
            counter = 0; betweenCounter = 0
            val started = System.nanoTime()
            val old = index.planLiveJourneyNestedLoops(fromLat, fromLon, toLat, toLon, emptyList(),
                walkFromOrigin = fromOrigin, walkFromDestination = fromDestination,
                walkBetweenStops = between, tracked = tracked)
            val middle = System.nanoTime()
            oldWalks += counter; oldBetween += betweenCounter
            counter = 0; betweenCounter = 0
            val new = index.planLiveJourney(fromLat, fromLon, toLat, toLon, emptyList(),
                walkFromOrigin = fromOrigin, walkFromDestination = fromDestination,
                walkBetweenStops = between, tracked = tracked)
            val finished = System.nanoTime()
            newWalks += counter; newBetween += betweenCounter
            // The first run warms the JIT and the lazy tables for both; leave it out.
            if (round > 0) {
                oldNanos += middle - started
                newNanos += finished - middle
                runs++
            }
            if (old != null) oldFound++
            if (new != null) newFound++
            if (old != null && new == null) missed++
        }
        val oldMillis = oldNanos / 1_000_000.0 / runs
        val newMillis = newNanos / 1_000_000.0 / runs
        println("BENCHMARK 400 vehicles (${tracked.size} tracked, tracking ${trackMillis} ms): " +
            "nested loops %.1f ms per plan, round planner %.1f ms per plan; found old=$oldFound new=$newFound missed=$missed; walk lookups old=$oldWalks new=$newWalks, stop-to-stop old=$oldBetween new=$newBetween"
                .format(oldMillis, newMillis))
        assertEquals("The round planner must not lose journeys", 0, missed)
        assertTrue("Round planner took $newMillis ms against $oldMillis ms",
            newMillis < oldMillis * 2 + 500)
    }
}
