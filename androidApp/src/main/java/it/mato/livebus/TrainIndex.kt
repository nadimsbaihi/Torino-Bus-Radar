package it.mato.livebus

import android.content.Context
import android.location.Location
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class TrainStop(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val arrivalSeconds: Int,
    val departureSeconds: Int
)

data class ScheduledTrainJourney(
    val number: String,
    val category: String,
    val headsign: String,
    val boardAt: TrainStop,
    val destination: TrainStop,
    val stops: List<TrainStop>,
    val boardIndex: Int,
    val destinationIndex: Int,
    val walkingMetres: Float,
    val destinationWalkMetres: Float,
    val estimatedTotalSeconds: Float,
    val departureSeconds: Int,
    val arrivalSeconds: Int
) {
    val totalWalkingMetres: Float get() = walkingMetres + destinationWalkMetres
}

class TrainIndex private constructor(private val trips: List<TrainTrip>) {
    val stations: List<TrainStop> = trips.flatMap { it.stops }.distinctBy { it.id }

    suspend fun planScheduledJourney(
        latitude: Double,
        longitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        now: LocalDateTime = LocalDateTime.now(),
        walkFromOrigin: ((Double, Double) -> Float)? = null,
        walkFromDestination: ((Double, Double) -> Float)? = null,
        boardStopId: String? = null,
        exitStopId: String? = null,
        walkingPreference: WalkingPreference = WalkingPreference.MORE
    ): ScheduledTrainJourney? {
        val context = currentCoroutineContext()
        val serviceDate = now.format(DATE)
        val nowSeconds = now.hour * 3600 + now.minute * 60 + now.second
        return trips.asSequence()
            .filter { serviceDate in it.activeDates }
            .mapNotNull { trip ->
                context.ensureActive()
                trip.stops.indices.asSequence().flatMap { boardIndex ->
                    val board = trip.stops[boardIndex]
                    if (boardStopId != null && board.id != boardStopId) return@flatMap emptySequence()
                    val walkMetres = walkFromOrigin?.invoke(board.latitude, board.longitude)
                        ?: distance(latitude, longitude, board)
                    val walkSeconds = walkMetres / WALKING_SPEED
                    if (walkMetres > walkingPreference.maxStationWalkMetres ||
                        board.departureSeconds < nowSeconds + walkSeconds + BOARDING_MARGIN) {
                        emptySequence()
                    } else {
                        (boardIndex + 1 until trip.stops.size).asSequence()
                            .filter { exitStopId == null || trip.stops[it].id == exitStopId }
                            .map { exitIndex ->
                                val exit = trip.stops[exitIndex]
                                val destinationWalk = walkFromDestination?.invoke(exit.latitude, exit.longitude)
                                    ?: distance(destinationLatitude, destinationLongitude, exit)
                                val total = (exit.arrivalSeconds - nowSeconds) +
                                    destinationWalk / WALKING_SPEED
                                ScheduledTrainJourney(
                                    number = trip.number,
                                    category = trip.category,
                                    headsign = trip.headsign,
                                    boardAt = board,
                                    destination = exit,
                                    stops = trip.stops,
                                    boardIndex = boardIndex,
                                    destinationIndex = exitIndex,
                                    walkingMetres = walkMetres,
                                    destinationWalkMetres = destinationWalk,
                                    estimatedTotalSeconds = total,
                                    departureSeconds = board.departureSeconds,
                                    arrivalSeconds = exit.arrivalSeconds
                                )
                            }
                    }
                }.minByOrNull {
                    walkingPreference.score(it.estimatedTotalSeconds, it.totalWalkingMetres)
                }
            }
            .minByOrNull {
                walkingPreference.score(it.estimatedTotalSeconds, it.totalWalkingMetres)
            }
    }

    private data class TrainTrip(
        val number: String,
        val category: String,
        val headsign: String,
        val activeDates: Set<String>,
        val stops: List<TrainStop>
    )

    companion object {
        private const val WALKING_SPEED = 1.2f
        private const val BOARDING_MARGIN = 180f
        private val DATE = DateTimeFormatter.BASIC_ISO_DATE

        fun load(context: Context): TrainIndex {
            val root = JSONObject(
                context.assets.open("trenitalia_rail_index.json").bufferedReader().use { it.readText() }
            )
            val trips = root.getJSONArray("trips")
            return TrainIndex((0 until trips.length()).map { index ->
                val item = trips.getJSONObject(index)
                val stops = item.getJSONArray("stops")
                TrainTrip(
                    number = item.getString("number"),
                    category = item.getString("category"),
                    headsign = item.getString("headsign"),
                    activeDates = item.getJSONArray("dates").let { dates ->
                        (0 until dates.length()).mapTo(mutableSetOf()) { dates.getString(it) }
                    },
                    stops = (0 until stops.length()).map { stopIndex ->
                        val stop = stops.getJSONObject(stopIndex)
                        TrainStop(
                            id = stop.getString("id"),
                            name = stop.getString("name"),
                            latitude = stop.getDouble("lat"),
                            longitude = stop.getDouble("lon"),
                            arrivalSeconds = stop.getInt("arrival"),
                            departureSeconds = stop.getInt("departure")
                        )
                    }
                )
            })
        }

        fun metres(value: Float) = value.roundToInt()

        private fun distance(latitude: Double, longitude: Double, stop: TrainStop): Float {
            val result = FloatArray(1)
            Location.distanceBetween(latitude, longitude, stop.latitude, stop.longitude, result)
            return result[0]
        }
    }
}
