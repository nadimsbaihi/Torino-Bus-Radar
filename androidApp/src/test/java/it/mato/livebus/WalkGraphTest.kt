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
}
