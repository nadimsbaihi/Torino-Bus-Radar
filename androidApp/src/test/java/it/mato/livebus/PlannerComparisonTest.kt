package it.mato.livebus

import android.app.Application
import android.location.Location
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The round planner against the nested-loop search it replaced, on the same snapshots. Both are fed
 * one set of tracked vehicles; the question is whether the new one ever misses a journey the old
 * one finds, or finds a worse one, and how long each takes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class PlannerComparisonTest {
    private val now = System.currentTimeMillis() / 1000

    private fun index(patterns: List<TransitPattern>): TransitIndex = TransitIndex::class.java
        .getDeclaredConstructor(List::class.java).apply { isAccessible = true }.newInstance(patterns)

    /** The score both planners minimise, rebuilt from what a finished journey says about itself. */
    private fun score(choice: JourneyChoice, preference: WalkingPreference) =
        preference.score(choice.estimatedTotalSeconds, choice.totalWalkingMetres) +
            Reliability.penaltySeconds(choice.boardingChance) + Reliability.penaltySeconds(choice.transferChance)

    private fun straight(a: LiveVehicle, b: LiveVehicle) = 0f

    /** A town of random lines, each a wandering row of stops about 400 m apart, with a few buses on each. */
    private fun town(random: Random, lines: Int, busesPerLine: Int): Pair<List<TransitPattern>, List<LiveVehicle>> {
        val patterns = ArrayList<TransitPattern>()
        val vehicles = ArrayList<LiveVehicle>()
        for (line in 0 until lines) {
            var lat = 45.03 + random.nextDouble() * 0.06
            var lon = 7.62 + random.nextDouble() * 0.08
            var heading = random.nextDouble() * 2 * Math.PI
            val stops = (0 until 14 + random.nextInt(12)).map { number ->
                heading += (random.nextDouble() - 0.5) * 0.5
                lat += cos(heading) * 0.0036
                lon += sin(heading) * 0.0050
                TransitStop("L$line-$number", "L$line-$number", lat, lon)
            }
            val pattern = TransitPattern("p$line", "r$line", "$line", "end$line", "0", setOf("t$line"), stops)
            patterns.add(pattern)
            repeat(busesPerLine) { bus ->
                val segment = random.nextInt(stops.size - 1)
                val share = random.nextDouble()
                vehicles.add(LiveVehicle("v$line-$bus", pattern.routeId, "t$line",
                    stops[segment].latitude + (stops[segment + 1].latitude - stops[segment].latitude) * share,
                    stops[segment].longitude + (stops[segment + 1].longitude - stops[segment].longitude) * share,
                    null, now - random.nextInt(40)))
            }
        }
        return patterns to vehicles
    }

    private fun metres(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(fromLat, fromLon, toLat, toLon, result)
        return result[0]
    }

    private class Outcome(val missed: Int, val worse: Int, val better: Int, val equal: Int, val bothNone: Int,
        val oldMillis: Long, val newMillis: Long, val worstGap: Float, val newOnly: Int)

    private fun compare(
        index: TransitIndex,
        vehicles: List<LiveVehicle>,
        random: Random,
        queries: Int,
        preference: WalkingPreference,
        walkBetween: Boolean
    ): Outcome = runBlocking {
        val tracked = index.trackVehicles(vehicles, now)
        val streets = 1.25f
        var missed = 0; var worse = 0; var better = 0; var equal = 0; var bothNone = 0; var newOnly = 0
        var oldNanos = 0L; var newNanos = 0L; var worstGap = 0f
        repeat(queries) {
            val fromLat = 45.03 + random.nextDouble() * 0.08
            val fromLon = 7.62 + random.nextDouble() * 0.10
            val toLat = fromLat + (random.nextDouble() - 0.5) * 0.03
            val toLon = fromLon + (random.nextDouble() - 0.5) * 0.04
            val fromOrigin = { lat: Double, lon: Double -> metres(fromLat, fromLon, lat, lon) * streets }
            val fromDestination = { lat: Double, lon: Double -> metres(toLat, toLon, lat, lon) * streets }
            val between = if (walkBetween) { a: Double, b: Double, c: Double, d: Double -> metres(a, b, c, d) * streets }
                else null
            val started = System.nanoTime()
            val old = index.planLiveJourneyNestedLoops(fromLat, fromLon, toLat, toLon, emptyList(),
                walkFromOrigin = fromOrigin, walkFromDestination = fromDestination,
                walkBetweenStops = between, walkingPreference = preference, tracked = tracked)
            val middle = System.nanoTime()
            val new = index.planLiveJourney(fromLat, fromLon, toLat, toLon, emptyList(),
                walkFromOrigin = fromOrigin, walkFromDestination = fromDestination,
                walkBetweenStops = between, walkingPreference = preference, tracked = tracked)
            val finished = System.nanoTime()
            oldNanos += middle - started
            newNanos += finished - middle
            when {
                old == null && new == null -> bothNone++
                old == null -> newOnly++
                new == null -> {
                    missed++
                    println("MISSED old: route ${old.pattern.route} board ${old.boardAt.id} idx ${old.boardStopIndex} -> ${old.destination.id} " +
                        "second=${old.secondLeg?.pattern?.route} transfer=${old.transferAt?.id} walk=${old.walkingMetres} " +
                        "eta=${old.boardingEtaSeconds} total=${old.estimatedTotalSeconds} destWalk=${old.destinationWalkMetres} " +
                        "progress=${tracked.first { it.vehicle.id == old.vehicle.id }.progress}")
                    val dbg = JourneyPlanner(index, null).plan(JourneyQuery(fromLat, fromLon, toLat, toLon, emptyList(),
                        fromOrigin, fromDestination, between, preference, tracked = tracked, maxVehicles = 1))
                    println("MISSED new direct options=${dbg.size} initial=${fromOrigin(toLat, toLon)} " +
                        "oldDestWalk=${old.destinationWalkMetres} boardDest=${fromDestination(old.boardAt.latitude, old.boardAt.longitude)}")
                }
                else -> {
                    val gap = score(new, preference) - score(old, preference)
                    when {
                        gap > 1f -> { worse++; worstGap = maxOf(worstGap, gap) }
                        gap < -1f -> better++
                        else -> equal++
                    }
                }
            }
        }
        Outcome(missed, worse, better, equal, bothNone, oldNanos / 1_000_000, newNanos / 1_000_000, worstGap, newOnly)
    }

    @Test fun theRoundPlannerNeverMissesAJourneyTheNestedLoopsFind() {
        var missed = 0; var worse = 0; var better = 0; var equal = 0; var none = 0; var newOnly = 0
        var oldMillis = 0L; var newMillis = 0L; var worstGap = 0f
        for ((seed, preference) in listOf(
            1 to WalkingPreference.MORE, 2 to WalkingPreference.BALANCED, 3 to WalkingPreference.LESS,
            4 to WalkingPreference.MORE, 5 to WalkingPreference.BALANCED, 6 to WalkingPreference.LESS)) {
            val random = Random(seed)
            val (patterns, vehicles) = town(random, lines = 45, busesPerLine = 3)
            val outcome = compare(index(patterns), vehicles, random, 80, preference, walkBetween = seed % 2 == 0)
            missed += outcome.missed; worse += outcome.worse; better += outcome.better; equal += outcome.equal
            none += outcome.bothNone; newOnly += outcome.newOnly
            oldMillis += outcome.oldMillis; newMillis += outcome.newMillis
            worstGap = maxOf(worstGap, outcome.worstGap)
        }
        println("COMPARISON synthetic: missed=$missed worse=$worse better=$better equal=$equal " +
            "neither=$none onlyNew=$newOnly worstGap=${worstGap}s old=${oldMillis}ms new=${newMillis}ms")
        assertEquals("The new planner must find whatever the old one finds", 0, missed)
        assertEquals("No journey may score worse than the old planner's by more than a second", 0, worse)
    }
}
