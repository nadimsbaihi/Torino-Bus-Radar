package it.mato.livebus

import android.app.Application
import com.google.transit.realtime.GtfsRealtime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GttRepositoryTest {
    @Test fun concurrentSearchAndRefreshShareOneDownload() = runBlocking {
        val calls = AtomicInteger()
        val repository = repository(calls)
        val results = List(10) { async { repository.vehicles() } }.awaitAll()
        assertEquals(1, calls.get())
        results.forEach { assertEquals(listOf("fresh", "delayed", "header"), it.map { vehicle -> vehicle.id }) }
    }

    @Test fun aVehicleWithoutAGpsFixIsNotABus() = runBlocking {
        val ids = repository(AtomicInteger()).vehicles().map { it.id }
        assertFalse("nofix" in ids)
        assertTrue("fresh" in ids)
    }

    @Test fun failedRefreshDoesNotReturnExpiredCache() = runBlocking {
        val calls = AtomicInteger()
        val repository = repository(calls, failAfterFirst = true)
        repository.vehicles()
        GttRepository::class.java.getDeclaredField("fetchedAtMillis").apply {
            isAccessible = true
            setLong(repository, -30_000)
        }
        try {
            repository.vehicles()
            fail("An expired cache must not hide a failed refresh")
        } catch (_: IOException) { }
        assertEquals(2, calls.get())
    }

    @Test fun manualRetryBypassesRecentCache() = runBlocking {
        val calls = AtomicInteger()
        val repository = repository(calls)
        repository.vehicles()
        repository.vehicles()
        assertEquals(1, calls.get())

        repository.vehicles(forceRefresh = true)
        assertEquals(2, calls.get())
    }

    @Test fun aBusWithNoTripGetsTheTripsTheUpdatesNameForIt() = runBlocking {
        val repository = repository(AtomicInteger(), tripUpdates = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("u").setTripUpdate(
                GtfsRealtime.TripUpdate.newBuilder()
                    .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("28375713U"))
                    .setVehicle(GtfsRealtime.VehicleDescriptor.newBuilder().setId("fresh"))))
            .build())
        assertEquals(listOf("28375713U"), repository.vehicles().single { it.id == "fresh" }.tripHints)
    }

    @Test fun positionsStillComeWhenTheTripUpdatesFail() = runBlocking {
        val repository = repository(AtomicInteger(), failTripUpdates = true)
        assertTrue(repository.vehicles().any { it.id == "fresh" })
    }

    private fun repository(
        calls: AtomicInteger,
        failAfterFirst: Boolean = false,
        tripUpdates: GtfsRealtime.FeedMessage? = null,
        failTripUpdates: Boolean = false
    ): GttRepository {
        val now = System.currentTimeMillis() / 1000
        val feed = GtfsRealtime.FeedMessage.newBuilder().setHeader(
            GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(now)
        )
        for ((id, timestamp) in listOf("fresh" to now, "delayed" to now - 240, "stale" to now - 301, "future" to now + 60,
            "header" to null, "nofix" to now)) {
            val fix = if (id == "nofix") 0f else 45f
            val vehicle = GtfsRealtime.VehiclePosition.newBuilder().setPosition(
                GtfsRealtime.Position.newBuilder().setLatitude(fix).setLongitude(if (id == "nofix") 0f else 7f)
            )
            if (timestamp != null) vehicle.setTimestamp(timestamp)
            feed.addEntity(GtfsRealtime.FeedEntity.newBuilder().setId(id).setVehicle(vehicle))
        }
        val bytes = feed.build().toByteArray()
        // Trip updates come with every refresh; only the positions are counted here.
        val updateBytes = (tripUpdates ?: GtfsRealtime.FeedMessage.newBuilder().setHeader(
            GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0")).build()).toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val forUpdates = "trip_update" in chain.request().url.encodedPath
            if (forUpdates && failTripUpdates) throw IOException("Trip updates unavailable")
            if (!forUpdates && calls.incrementAndGet() > 1 && failAfterFirst) throw IOException("Feed unavailable")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body((if (forUpdates) updateBytes else bytes).toResponseBody()).build()
        }.build()
        return GttRepository().also {
            GttRepository::class.java.getDeclaredField("client").apply {
                isAccessible = true
                set(it, client)
            }
        }
    }
}
