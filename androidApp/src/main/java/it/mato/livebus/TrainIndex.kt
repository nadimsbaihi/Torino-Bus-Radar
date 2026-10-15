package it.mato.livebus

import android.content.Context
import android.location.Location
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
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
    val arrivalSeconds: Int,
    // 1 when the train runs tomorrow; the times above are then tomorrow's clock times.
    val dayOffset: Int = 0
) {
    val totalWalkingMetres: Float get() = walkingMetres + destinationWalkMetres
    // GTT's metro rides the same timetable path as the trains; its number is the line, "M1".
    val isMetro: Boolean get() = category == TrainIndex.METRO
}

/** Scheduled rail: Trenitalia's local trains and GTT's metro, both from their timetables. */
class TrainIndex private constructor(private val trips: List<TrainTrip>) {
    val stations: List<TrainStop> = trips.flatMap { it.stops }.distinctBy { it.id }

    /** The metro alone, for a rider who leaves trains out: it runs like a bus line, every few minutes. */
    val metroOnly: TrainIndex by lazy { TrainIndex(trips.filter { it.category == METRO }) }

    private val tripsByDate: Map<String, List<TrainTrip>> by lazy {
        val byDate = HashMap<String, MutableList<TrainTrip>>()
        for (trip in trips) for (date in trip.activeDates) byDate.getOrPut(date) { ArrayList() }.add(trip)
        byDate
    }

    /**
     * The best scheduled train for a journey. Services run on the day they belong to, so a
     * train after midnight is still yesterday's, and once today's trains are gone the first
     * ones tomorrow are the answer.
     */
    suspend fun planScheduledJourney(
        latitude: Double,
        longitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        now: LocalDateTime = now(),
        walkFromOrigin: ((Double, Double) -> Float)? = null,
        walkFromDestination: ((Double, Double) -> Float)? = null,
        boardStopId: String? = null,
        exitStopId: String? = null,
        walkingPreference: WalkingPreference = WalkingPreference.MORE
    ): ScheduledTrainJourney? {
        val context = currentCoroutineContext()
        val today = now.toLocalDate()
        val secondsSinceMidnight = now.hour * 3600 + now.minute * 60 + now.second
        // Walks are per stop, not per trip: ask once.
        val originWalks = HashMap<String, Float>()
        val destinationWalks = HashMap<String, Float>()

        var best: ScheduledTrainJourney? = null
        var bestScore = Float.POSITIVE_INFINITY
        for (dayOffset in -1..1) {
            // Tomorrow only matters when nothing is left today.
            if (dayOffset == 1 && best != null) break
            val trips = tripsByDate[today.plusDays(dayOffset.toLong()).format(DATE)] ?: continue
            // Seconds since the start of the service day this timetable belongs to.
            val nowSeconds = secondsSinceMidnight - dayOffset * SECONDS_PER_DAY
            for (trip in trips) {
                context.ensureActive()
                for (boardIndex in trip.stops.indices) {
                    val board = trip.stops[boardIndex]
                    if (boardStopId != null && board.id != boardStopId) continue
                    val walkMetres = originWalks.getOrPut(board.id) {
                        walkFromOrigin?.invoke(board.latitude, board.longitude)
                            ?: distance(latitude, longitude, board)
                    }
                    val walkSeconds = walkMetres / WALKING_SPEED
                    if (walkMetres > walkingPreference.maxStationWalkMetres ||
                        board.departureSeconds < nowSeconds + walkSeconds + BOARDING_MARGIN) continue
                    for (exitIndex in boardIndex + 1 until trip.stops.size) {
                        val exit = trip.stops[exitIndex]
                        if (exitStopId != null && exit.id != exitStopId) continue
                        val destinationWalk = destinationWalks.getOrPut(exit.id) {
                            walkFromDestination?.invoke(exit.latitude, exit.longitude)
                                ?: distance(destinationLatitude, destinationLongitude, exit)
                        }
                        val total = (exit.arrivalSeconds - nowSeconds) + destinationWalk / WALKING_SPEED
                        val score = walkingPreference.score(total, walkMetres + destinationWalk)
                        if (score >= bestScore) continue
                        bestScore = score
                        best = ScheduledTrainJourney(
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
                            arrivalSeconds = exit.arrivalSeconds,
                            dayOffset = maxOf(dayOffset, 0)
                        )
                    }
                }
            }
        }
        return best
    }

    /** The scheduled runs of a service date, for planners that combine them with other vehicles. */
    internal fun tripsOn(date: java.time.LocalDate): List<TrainTrip> = tripsByDate[date.format(DATE)].orEmpty()

    internal data class TrainTrip(
        val number: String,
        val category: String,
        val headsign: String,
        val activeDates: Set<String>,
        val stops: List<TrainStop>
    )

    companion object {
        private const val WALKING_SPEED = 1.2f
        private const val BOARDING_MARGIN = 120f
        private const val SECONDS_PER_DAY = 86_400
        // Timetables are in local time whatever the phone's clock is set to.
        private val ROME = ZoneId.of("Europe/Rome")

        fun now(): LocalDateTime = LocalDateTime.now(ROME)
        private val DATE = DateTimeFormatter.BASIC_ISO_DATE

        const val METRO = "METRO"

        fun load(context: Context): TrainIndex =
            TrainIndex(ASSETS.flatMap { name ->
                parse(JSONObject(context.assets.open(name).bufferedReader().use { it.readText() }))
            })

        private val ASSETS = listOf("trenitalia_rail_index.json", "gtt_metro_index.json")

        /**
         * Version 1 lists every stop of a run in full; version 2 lists each station once and a run
         * as station numbers ("calls") with an arrival and a departure for each ("times").
         */
        private fun parse(root: JSONObject): List<TrainTrip> {
            val stations = root.optJSONArray("stations")
            val trips = root.getJSONArray("trips")
            return (0 until trips.length()).map { index ->
                val item = trips.getJSONObject(index)
                TrainTrip(
                    number = item.getString("number"),
                    category = item.getString("category"),
                    headsign = item.getString("headsign"),
                    activeDates = item.getJSONArray("dates").let { dates ->
                        (0 until dates.length()).mapTo(mutableSetOf()) { dates.getString(it) }
                    },
                    stops = if (stations == null) {
                        val stops = item.getJSONArray("stops")
                        (0 until stops.length()).map { stopIndex ->
                            val stop = stops.getJSONObject(stopIndex)
                            trainStop(stop, stop.getInt("arrival"), stop.getInt("departure"))
                        }
                    } else {
                        val calls = item.getJSONArray("calls")
                        val times = item.getJSONArray("times")
                        (0 until calls.length()).map { call ->
                            trainStop(stations.getJSONObject(calls.getInt(call)),
                                times.getInt(2 * call), times.getInt(2 * call + 1))
                        }
                    }
                )
            }
        }

        private fun trainStop(station: JSONObject, arrival: Int, departure: Int) = TrainStop(
            id = station.getString("id"),
            name = station.getString("name"),
            latitude = station.getDouble("lat"),
            longitude = station.getDouble("lon"),
            arrivalSeconds = arrival,
            departureSeconds = departure
        )

        fun metres(value: Float) = value.roundToInt()

        private fun distance(latitude: Double, longitude: Double, stop: TrainStop): Float {
            val result = FloatArray(1)
            Location.distanceBetween(latitude, longitude, stop.latitude, stop.longitude, result)
            return result[0]
        }
    }
}
