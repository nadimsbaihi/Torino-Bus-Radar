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

    private fun repository(calls: AtomicInteger, failAfterFirst: Boolean = false): GttRepository {
        val now = System.currentTimeMillis() / 1000
        val feed = GtfsRealtime.FeedMessage.newBuilder().setHeader(
            GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(now)
        )
        for ((id, timestamp) in listOf("fresh" to now, "delayed" to now - 240, "stale" to now - 301, "future" to now + 60, "header" to null)) {
            val vehicle = GtfsRealtime.VehiclePosition.newBuilder().setPosition(
                GtfsRealtime.Position.newBuilder().setLatitude(45f).setLongitude(7f)
            )
            if (timestamp != null) vehicle.setTimestamp(timestamp)
            feed.addEntity(GtfsRealtime.FeedEntity.newBuilder().setId(id).setVehicle(vehicle))
        }
        val bytes = feed.build().toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.incrementAndGet() > 1 && failAfterFirst) throw IOException("Feed unavailable")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        return GttRepository().also {
            GttRepository::class.java.getDeclaredField("client").apply {
                isAccessible = true
                set(it, client)
            }
        }
    }
}
