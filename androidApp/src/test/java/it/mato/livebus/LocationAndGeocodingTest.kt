package it.mato.livebus

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class LocationAndGeocodingTest {
    @Before fun enableDeviceLocation() {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(LocationManager::class.java))
            .setLocationEnabled(true)
    }

    @Test fun deviceLocationOffDoesNotUseACachedPosition() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(LocationManager::class.java))
            .setLocationEnabled(false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        set(activity, "originLocation", Location("test").apply {
            latitude = 45.12
            longitude = 7.72
            accuracy = 10f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        assertNull(withTimeout(1_000) { invokeSuspend<Location?>(activity, "obtainLocation") })
    }

    @Test fun approximatePermissionCanUseRecentLocation() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val location = Location("test").apply {
            latitude = 45.12
            longitude = 7.72
            accuracy = 1_000f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        set(activity, "originLocation", location)
        val result = invokeSuspend<Location?>(activity, "obtainLocation")
        assertSame(location, result)
    }

    @Test fun staleLocationWaitsForProviderAndDoesNotFallBackToTorino() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        set(activity, "originLocation", Location("test").apply {
            latitude = 45.12
            longitude = 7.72
            accuracy = 10f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 60_000_000_000L
        })
        val provider = CompletableDeferred<Location?>()
        set(activity, "locationRequest", provider)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            invokeSuspend<Location?>(activity, "obtainLocation")
        }
        assertFalse(result.isCompleted)
        provider.complete(null)
        assertNull(result.await())
    }

    @Test fun destinationQueryResolvesFromBundledCityWithoutGeocoder() = runBlocking {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val result = invokeSuspend<Pair<Double, Double>?>(activity, "geocode", "Comala")
        assertNotNull(result)
        assertEquals(45.0692, result!!.first, 0.0002)
        assertEquals(7.6560, result.second, 0.0002)
    }

    private fun set(activity: MainActivity, name: String, value: Any) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(activity, value)
        }
    }

    private suspend fun <T> invokeSuspend(activity: MainActivity, name: String, vararg args: Any): T =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = MainActivity::class.java.declaredMethods.single {
                it.name == name && it.parameterTypes.lastOrNull() == Continuation::class.java
            }
            method.isAccessible = true
            method.invoke(activity, *args, continuation)
        }
}
