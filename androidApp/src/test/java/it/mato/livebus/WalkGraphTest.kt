package it.mato.livebus

import android.location.Location
import android.view.LayoutInflater
import it.mato.livebus.databinding.ActivityMainBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class WalkGraphTest {
    @Test fun cityWalkingDistanceFollowsConnectedStreets() {
        val city = (RuntimeEnvironment.getApplication() as MatoApplication).offlineCity
        val origin = 45.0659 to 7.6946
        val destination = 45.0649 to 7.7046
        val distances = city.walkingDistancesFrom(origin.first, origin.second)
        val route = distances.metresTo(destination.first, destination.second)
        val direct = FloatArray(1)
        Location.distanceBetween(origin.first, origin.second,
            destination.first, destination.second, direct)
        assertNotNull("These two points should connect across the Po", route)
        assertTrue("A street route across the Po must exceed the straight line",
            route!! > direct[0] * 1.05f)
        val streetPath = distances.routeTo(destination.first, destination.second)!!
        assertEquals(route, streetPath.metres, 0.5f)
        assertTrue(streetPath.points.size > 2)
        val shortWalk = city.shortWalkMetres(45.0703, 7.6869, 45.0704, 7.6871)
        assertNotNull("Nearby street points should have a short route", shortWalk)
        assertTrue(shortWalk!! < 500f)
    }

    @Test fun dashedWalkingOverlayUsesTheStreetPathAndSameMetreEstimate() {
        val city = (RuntimeEnvironment.getApplication() as MatoApplication).offlineCity
        val origin = GeoPoint(45.0659, 7.6946)
        val destination = GeoPoint(45.0649, 7.7046)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        fun set(name: String, value: Any?) {
            MainActivity::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(activity, value)
            }
        }
        set("binding", binding)
        set("currentLocation", origin)
        set("directDestination", destination)
        val distances = city.walkingDistancesFrom(origin.latitude, origin.longitude)
        set("walkingFromOrigin", distances)
        set("walkingMode", true)
        val render = MainActivity::class.java.getDeclaredMethod("renderVehicles").apply {
            isAccessible = true
        }
        render.invoke(activity)
        val walkingLines = binding.map.overlays.filterIsInstance<Polyline>()
            .filter { it.outlinePaint.pathEffect != null }
        assertEquals(2, walkingLines.size)
        assertTrue(walkingLines.all { it.actualPoints.size > 2 })
        val displayedMetres = MainActivity::class.java.getDeclaredMethod(
            "directWalkingDistance", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType
        ).apply { isAccessible = true }.invoke(activity, destination.latitude, destination.longitude) as Float
        assertEquals(distances.routeTo(destination.latitude, destination.longitude)!!.metres,
            displayedMetres, 0.5f)
        set("walkingFromOrigin", null)
        render.invoke(activity)
        assertTrue(binding.map.overlays.filterIsInstance<Polyline>().none {
            it.outlinePaint.pathEffect != null
        })
        binding.map.onDetach()
    }

    /** A straight street of [mainNodes] nodes 0.0005 degrees apart, plus a two-node footpath fragment. */
    private fun syntheticGraph(mainNodes: Int = 100): WalkGraph {
        val latitudes = (0 until mainNodes).map { 45.0 + 0.0005 * it } + listOf(45.01, 45.0101)
        val longitudes = List(mainNodes) { 7.0 } + listOf(7.0005, 7.0005)
        val edges = mutableListOf<Pair<Int, Int>>()
        for (node in 0 until mainNodes - 1) edges += node to node + 1
        edges += mainNodes to mainNodes + 1
        val adjacency = List(latitudes.size) { node ->
            edges.filter { it.first == node || it.second == node }.map { if (it.first == node) it.second else it.first }
        }
        val file = java.io.File.createTempFile("walk", ".bin")
        java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write("MATOWLK1".toByteArray(Charsets.US_ASCII))
            out.writeInt(latitudes.size)
            out.writeInt(adjacency.sumOf { it.size })
            latitudes.indices.forEach { out.writeDouble(latitudes[it]); out.writeDouble(longitudes[it]) }
            var offset = 0
            out.writeInt(offset)
            adjacency.forEach { offset += it.size; out.writeInt(offset) }
            adjacency.forEachIndexed { node, neighbours ->
                neighbours.forEach { other ->
                    out.writeInt(other)
                    val metres = Math.hypot((latitudes[other] - latitudes[node]) * 111_195.0,
                        (longitudes[other] - longitudes[node]) * 111_195.0 * Math.cos(Math.toRadians(45.0)))
                    out.writeFloat(metres.toFloat())
                }
            }
        }
        return WalkGraph.load(file).also { file.delete() }
    }

    @Test fun pointsMidwayAlongAStreetAreMeasuredFromThePointNotTheNearestCorner() {
        val graph = syntheticGraph()
        // Midway along the first edge to midway along the eleventh: ten edges of 55.6 m.
        val from = 45.00025 to 7.0
        val to = 45.00025 + 0.0005 * 10 to 7.0
        val measured = graph.distancesFrom(from.first, from.second).metresTo(to.first, to.second)!!
        assertEquals(556f, measured, 2f)
        // A short walk searches within 500 m only: eight edges.
        val near = 45.00025 + 0.0005 * 8 to 7.0
        assertEquals(445f, graph.shortWalkMetres(from.first, from.second, near.first, near.second)!!, 2f)
        assertNull(graph.shortWalkMetres(from.first, from.second, to.first, to.second))
        // Both on one edge: the gap between them plus the few metres off the street.
        assertEquals(56f, graph.shortWalkMetres(45.00002, 7.00003, 45.00048, 7.00003)!!, 3f)
    }

    @Test fun strayFragmentsAreNotMistakenForTheStreetNetwork() {
        val graph = syntheticGraph()
        // 31 m from the isolated fragment, 31 m from the real street.
        val distances = graph.distancesFrom(45.01, 7.0004)
        val metres = distances.metresTo(45.02, 7.0)
        assertNotNull("The fragment is a dead end; the street is the way out", metres)
        assertEquals(1_130f, metres!!, 80f)
        assertNotNull(graph.shortWalkMetres(45.01, 7.0004, 45.0115, 7.0))
    }

    @Test fun pointsAFewMetresApartAreJustWalkedBetween() {
        val city = (RuntimeEnvironment.getApplication() as MatoApplication).offlineCity
        val metres = city.shortWalkMetres(45.0711, 7.6857, 45.07111, 7.68575)
        assertNotNull(metres)
        assertTrue("$metres", metres!! < 10f)
        assertEquals(metres, city.walkingDistancesFrom(45.0711, 7.6857)
            .metresTo(45.07111, 7.68575)!!, 0.01f)
    }
}
