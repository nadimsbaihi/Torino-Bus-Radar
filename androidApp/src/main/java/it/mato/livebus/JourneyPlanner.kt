package it.mato.livebus

import android.location.Location
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class MixedJourney(
    val bus: JourneyChoice,
    val train: ScheduledTrainJourney,
    val busFirst: Boolean,
    val estimatedTotalSeconds: Float
) {
    val totalWalkingMetres: Float get() = bus.totalWalkingMetres + train.totalWalkingMetres
}

enum class WalkingPreference(val extraWalkingWeight: Float, val maxStationWalkMetres: Float) {
    LESS(4f, 1_500f),
    BALANCED(1f, 2_500f),
    MORE(0f, 5_000f);

    fun score(totalSeconds: Float, walkingMetres: Float): Float =
        totalSeconds + walkingMetres / 1.35f * extraWalkingWeight
}

/** Checks bus/train connections against live bus progress and scheduled train times. */
class JourneyPlanner(private val buses: TransitIndex, private val trains: TrainIndex) {
    suspend fun planMixedJourney(
        latitude: Double,
        longitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        vehicles: List<LiveVehicle>,
        now: LocalDateTime,
        walkFromOrigin: (Double, Double) -> Float,
        walkFromDestination: (Double, Double) -> Float,
        walkBetweenStops: (Double, Double, Double, Double) -> Float,
        walkingPreference: WalkingPreference = WalkingPreference.MORE
    ): MixedJourney? {
        if (vehicles.isEmpty()) return null
        val context = currentCoroutineContext()
        val liveStops = vehicles.mapNotNull(buses::matchDirection)
            .distinctBy { it.id }.flatMap { it.stops }.distinctBy { it.id }
        var best: MixedJourney? = null
        for (station in trains.stations) {
            context.ensureActive()
            if (liveStops.none { stop -> nearby(station, stop.latitude, stop.longitude) }) continue
            fun stationWalk(lat: Double, lon: Double): Float =
                if (nearby(station, lat, lon)) {
                    walkBetweenStops(lat, lon, station.latitude, station.longitude)
                } else Float.POSITIVE_INFINITY

            // Reach a station on a live bus, then board a scheduled train.
            if (trains.planScheduledJourney(station.latitude, station.longitude,
                    destinationLatitude, destinationLongitude, now,
                    walkFromOrigin = { _, _ -> 0f },
                    walkFromDestination = walkFromDestination,
                    boardStopId = station.id,
                    walkingPreference = walkingPreference) != null) {
                val bus = buses.planLiveJourney(latitude, longitude,
                    station.latitude, station.longitude, vehicles,
                    walkFromOrigin = walkFromOrigin,
                    walkFromDestination = ::stationWalk,
                    maxDestinationWalkMetres = MAX_CONNECTION_WALK,
                    directOnly = true,
                    walkingPreference = walkingPreference)
                if (bus != null) {
                    val arrivalSeconds = ceil(bus.estimatedTotalSeconds).toLong()
                    val train = trains.planScheduledJourney(station.latitude, station.longitude,
                        destinationLatitude, destinationLongitude, now.plusSeconds(arrivalSeconds),
                        walkFromOrigin = { _, _ -> 0f },
                        walkFromDestination = walkFromDestination,
                        boardStopId = station.id,
                        walkingPreference = walkingPreference)
                    if (train != null) {
                        val choice = MixedJourney(bus, train, true,
                            arrivalSeconds + train.estimatedTotalSeconds)
                        if (best == null || walkingPreference.score(choice.estimatedTotalSeconds,
                                choice.totalWalkingMetres) < walkingPreference.score(
                                best.estimatedTotalSeconds, best.totalWalkingMetres)) best = choice
                    }
                }
            }

            // A train can reach a station before a live bus reaches its boarding stop.
            val train = trains.planScheduledJourney(latitude, longitude,
                station.latitude, station.longitude, now,
                walkFromOrigin = walkFromOrigin,
                walkFromDestination = { _, _ -> 0f },
                exitStopId = station.id,
                walkingPreference = walkingPreference)
            if (train != null) {
                val bus = buses.planLiveJourney(station.latitude, station.longitude,
                    destinationLatitude, destinationLongitude, vehicles,
                    walkFromOrigin = { lat, lon ->
                        if (nearby(station, lat, lon)) {
                            walkBetweenStops(station.latitude, station.longitude, lat, lon)
                        } else Float.POSITIVE_INFINITY
                    },
                    walkFromDestination = walkFromDestination,
                    earliestStartSeconds = train.estimatedTotalSeconds + ALIGHTING_SECONDS,
                    directOnly = true,
                    walkingPreference = walkingPreference)
                if (bus != null) {
                    val choice = MixedJourney(bus, train, false, bus.estimatedTotalSeconds)
                    if (best == null || walkingPreference.score(choice.estimatedTotalSeconds,
                            choice.totalWalkingMetres) < walkingPreference.score(
                            best.estimatedTotalSeconds, best.totalWalkingMetres)) best = choice
                }
            }
        }
        return best
    }

    private fun nearby(station: TrainStop, latitude: Double, longitude: Double): Boolean {
        if (abs(station.latitude - latitude) > 0.006 ||
            abs(station.longitude - longitude) > 0.009) return false
        val metres = FloatArray(1)
        Location.distanceBetween(station.latitude, station.longitude, latitude, longitude, metres)
        return metres[0] <= MAX_CONNECTION_WALK
    }

    private companion object {
        const val MAX_CONNECTION_WALK = 500f
        const val ALIGHTING_SECONDS = 60f
    }
}
