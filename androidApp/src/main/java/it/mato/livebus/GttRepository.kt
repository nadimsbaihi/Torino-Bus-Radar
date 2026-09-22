package it.mato.livebus

import com.google.transit.realtime.GtfsRealtime
import android.util.Log
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
)

class GttRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(8))
        .callTimeout(Duration.ofSeconds(8))
        .build()

    suspend fun vehicles(): List<LiveVehicle> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(VEHICLE_POSITIONS_URL)
            .header("User-Agent", "MATO-Live-Bus-Android/0.1")
            .build()

        Log.d(TAG, "Requesting GTT vehicle positions")
        client.newCall(request).execute().use { response ->
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
                    timestampSeconds = update.timestamp
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
