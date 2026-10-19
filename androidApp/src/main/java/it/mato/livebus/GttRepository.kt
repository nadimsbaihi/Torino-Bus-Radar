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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val timestampSeconds: Long,
    /** The fleet number painted on the bus, when the feed gives one. */
    val label: String? = null,
    /**
     * For a bus whose position comes without a trip, the trips GTT's trip updates name for it,
     * soonest first: the one it is on, or the next one when it is about to finish. A shortlist
     * for working out its direction, never its trip.
     */
    val tripHints: List<String> = emptyList()
) {
    /** What the rider sees on the bus itself; the feed id is that number with a company suffix. */
    val fleetNumber: String get() = label ?: id.removeSuffix("U")

    val ageSeconds: Long get() = ageSecondsAt(System.currentTimeMillis() / 1000)

    /** Age against a fixed clock reading, so one planning run sees one consistent "now". */
    fun ageSecondsAt(nowSeconds: Long): Long = (nowSeconds - timestampSeconds).coerceAtLeast(0)
}

/** What GTT's alerts say about the metro now; only short-lived ones, such as a closure, count. */
object MetroAlerts {
    // GTT's metro in its feeds; the live positions never carry it, but the alerts do.
    const val ROUTE_ID = "METROU"
    // A bulletin that stands for months ("lift status", posted for ten years) is not news.
    private const val MAX_SPAN_SECONDS = 60L * 24 * 3600

    fun from(feed: GtfsRealtime.FeedMessage, nowSeconds: Long): List<String> =
        feed.entityList.mapNotNull { entity ->
            val alert = entity.alert.takeIf { entity.hasAlert() } ?: return@mapNotNull null
            if (alert.informedEntityList.none { it.routeId.removePrefix("gtt:") == ROUTE_ID }) return@mapNotNull null
            val periods = alert.activePeriodList
            val current = periods.isEmpty() || periods.any { period ->
                (!period.hasStart() || period.start <= nowSeconds) && (!period.hasEnd() || nowSeconds < period.end)
            }
            val standing = periods.isEmpty() || periods.any { period ->
                !period.hasStart() || !period.hasEnd() || period.end - period.start > MAX_SPAN_SECONDS
            }
            if (!current || standing) return@mapNotNull null
            alert.headerText.translationList.firstOrNull()?.text?.trim()?.takeIf(String::isNotEmpty)
        }.distinct()
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
        return vehicles.filter { now - it.timestampSeconds in -30..MAX_POSITION_AGE_SECONDS }
    }

    private val alertsLock = Mutex()
    private var cachedMetroAlerts: List<String>? = null
    private var alertsFetchedAtMillis = 0L

    /** GTT's current notices about the metro, refreshed every few minutes. */
    suspend fun metroAlerts(): List<String> = alertsLock.withLock {
        cachedMetroAlerts?.takeIf { SystemClock.elapsedRealtime() - alertsFetchedAtMillis < 300_000 }
            ?.let { return@withLock it }
        val alerts = withContext(Dispatchers.IO) {
            fetch(ALERTS_URL).use { response ->
                check(response.isSuccessful) { "GTT alerts returned ${response.code}" }
                MetroAlerts.from(GtfsRealtime.FeedMessage.parseFrom(requireNotNull(response.body).byteStream()),
                    System.currentTimeMillis() / 1000)
            }
        }
        cachedMetroAlerts = alerts
        alertsFetchedAtMillis = SystemClock.elapsedRealtime()
        alerts
    }

    private suspend fun fetchVehicles(): List<LiveVehicle> = coroutineScope {
        // Positions often come without their trip (most buses, some evenings); the trip updates
        // still name the trips each vehicle is on or about to start.
        val hintsTask = async { fetchTripHints() }
        Log.d(TAG, "Requesting GTT vehicle positions")
        val response = fetch(VEHICLE_POSITIONS_URL)
        val vehicles = response.use {
            check(response.isSuccessful) { "GTT feed returned ${response.code}" }
            val feed = GtfsRealtime.FeedMessage.parseFrom(
                requireNotNull(response.body).byteStream()
            )
            decodeVehicles(feed)
        }
        val hints = hintsTask.await()
        vehicles.map { vehicle ->
            if (vehicle.tripId != null) vehicle
            else hints[vehicle.id]?.let { vehicle.copy(tripHints = it) } ?: vehicle
        }
    }

    /** The trips GTT's trip updates name for each vehicle, soonest first; empty if that feed fails. */
    private suspend fun fetchTripHints(): Map<String, List<String>> = try {
        fetch(TRIP_UPDATES_URL).use { response ->
            check(response.isSuccessful) { "GTT trip updates returned ${response.code}" }
            tripHints(GtfsRealtime.FeedMessage.parseFrom(requireNotNull(response.body).byteStream()))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "GTT trip updates unavailable", error)
        emptyMap()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun fetch(url: String): Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Torino-Bus-Radar-Android/0.1")
            .build()
        return suspendCancellableCoroutine { continuation ->
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
    }

    private fun decodeVehicles(feed: GtfsRealtime.FeedMessage): List<LiveVehicle> {
        val vehicles = feed.entityList.mapNotNull { entity ->
            if (!entity.hasVehicle() || !entity.vehicle.hasPosition()) return@mapNotNull null
            val update = entity.vehicle
            // A vehicle with no GPS fix reports 0, 0: open sea off Africa, not a bus.
            if (update.position.latitude == 0f && update.position.longitude == 0f) return@mapNotNull null
            LiveVehicle(
                id = update.vehicle.id.takeIf(String::isNotBlank) ?: entity.id,
                routeId = update.trip.routeId.removePrefix("gtt:"),
                tripId = update.trip.tripId.takeIf(String::isNotBlank),
                latitude = update.position.latitude.toDouble(),
                longitude = update.position.longitude.toDouble(),
                bearing = update.position.bearing.takeIf { update.position.hasBearing() },
                timestampSeconds = if (update.hasTimestamp()) update.timestamp else feed.header.timestamp,
                label = update.vehicle.label.takeIf(String::isNotBlank)
            )
        }
        Log.i(TAG, "Decoded ${vehicles.size} live GTT vehicles")
        return vehicles
    }

    companion object {
        private const val TAG = "GttRepository"

        /** Each vehicle's trips in the trip updates, by when they start ("20261015" "09:56:00"). */
        internal fun tripHints(feed: GtfsRealtime.FeedMessage): Map<String, List<String>> =
            feed.entityList.asSequence()
                .filter { it.hasTripUpdate() && it.tripUpdate.vehicle.id.isNotBlank() }
                .map { it.tripUpdate }
                .filter { it.trip.tripId.isNotBlank() }
                .groupBy { it.vehicle.id }
                .mapValues { (_, updates) ->
                    updates.sortedBy { it.trip.startDate + it.trip.startTime.padStart(8, '0') }
                        .map { it.trip.tripId }.distinct()
                }

        /** Older than this, a bus position says more about where it was than where it is. */
        const val MAX_POSITION_AGE_SECONDS = 300L

        private const val VEHICLE_POSITIONS_URL =
            "https://percorsieorari.gtt.to.it/das_gtfsrt/vehicle_position.aspx"
        private const val TRIP_UPDATES_URL = "https://percorsieorari.gtt.to.it/das_gtfsrt/trip_update.aspx"
        private const val ALERTS_URL = "https://percorsieorari.gtt.to.it/das_gtfsrt/alerts.aspx"
    }
}
