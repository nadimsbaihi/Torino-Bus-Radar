package it.mato.livebus

import com.google.transit.realtime.GtfsRealtime
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration

data class LiveVehicle(
    val id: String,
    val routeId: String,
    val tripId: String?,
    val latitude: Double,
    val longitude: Double,
    val bearing: Float?,
    val timestampSeconds: Long
) {
    val ageSeconds: Long get() = (System.currentTimeMillis() / 1000 - timestampSeconds).coerceAtLeast(0)
}

class GttRepository {
    private val refreshLock = Mutex()
    private var cachedVehicles: List<LiveVehicle>? = null
    private var fetchedAtMillis = 0L
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(8))
        .callTimeout(Duration.ofSeconds(8))
        .build()

    suspend fun vehicles(forceRefresh: Boolean = false): List<LiveVehicle> = refreshLock.withLock {
        cachedVehicles?.takeIf { !forceRefresh && SystemClock.elapsedRealtime() - fetchedAtMillis < 15_000 }
            ?.let { return@withLock freshVehicles(it) }
        val vehicles = withContext(Dispatchers.IO) { fetchVehicles() }
        cachedVehicles = vehicles
        fetchedAtMillis = SystemClock.elapsedRealtime()
        freshVehicles(vehicles)
    }

    private fun freshVehicles(vehicles: List<LiveVehicle>): List<LiveVehicle> {
        val now = System.currentTimeMillis() / 1000
        return vehicles.filter { now - it.timestampSeconds in -30..300 }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun fetchVehicles(): List<LiveVehicle> {
        val request = Request.Builder()
            .url(VEHICLE_POSITIONS_URL)
            .header("User-Agent", "Torino-Bus-Radar-Android/0.1")
            .build()

        Log.d(TAG, "Requesting GTT vehicle positions")
        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { response.close() }
                }
            })
        }
        return response.use {
            check(response.isSuccessful) { "GTT feed returned ${response.code}" }
            val feed = GtfsRealtime.FeedMessage.parseFrom(
                requireNotNull(response.body).byteStream()
            )
            val vehicles = feed.entityList.mapNotNull { entity ->
                if (!entity.hasVehicle() || !entity.vehicle.hasPosition()) return@mapNotNull null
                val update = entity.vehicle
                LiveVehicle(
                    id = update.vehicle.id.takeIf(String::isNotBlank) ?: entity.id,
                    routeId = update.trip.routeId.removePrefix("gtt:"),
                    tripId = update.trip.tripId.takeIf(String::isNotBlank),
                    latitude = update.position.latitude.toDouble(),
                    longitude = update.position.longitude.toDouble(),
                    bearing = update.position.bearing.takeIf { update.position.hasBearing() },
                    timestampSeconds = if (update.hasTimestamp()) update.timestamp else feed.header.timestamp
                )
            }
            Log.i(TAG, "Decoded ${vehicles.size} live GTT vehicles")
            vehicles
        }
    }

    private companion object {
        const val TAG = "GttRepository"
        const val VEHICLE_POSITIONS_URL =
            "https://percorsieorari.gtt.to.it/das_gtfsrt/vehicle_position.aspx"
    }
}
