package it.mato.livebus

import android.location.Location
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
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
        totalSeconds + walkingMetres / 1.2f * extraWalkingWeight
}

/** What to plan: where from and to, with what buses, and how the rider walks. */
class JourneyQuery(
    val latitude: Double,
    val longitude: Double,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
    val vehicles: List<LiveVehicle>,
    val walkFromOrigin: ((Double, Double) -> Float)? = null,
    val walkFromDestination: ((Double, Double) -> Float)? = null,
    val walkBetweenStops: ((Double, Double, Double, Double) -> Float)? = null,
    val walkingPreference: WalkingPreference = WalkingPreference.MORE,
    // Set to include scheduled rail, planned from this moment (Rome time): the metro, and the
    // trains too unless [includeTrains] is off.
    val trainsAt: LocalDateTime? = null,
    val includeTrains: Boolean = true,
    val earliestStartSeconds: Float = 0f,
    val maxDestinationWalkMetres: Float = Float.POSITIVE_INFINITY,
    // Vehicles per journey; the journey panel can show two.
    val maxVehicles: Int = 2,
    val requireTrain: Boolean = false,
    val tracked: List<TrackedVehicle>? = null
)

/**
 * A journey on offer: by buses ([bus], alone or with a change) or a bus and a train ([mixed]).
 * [reason] says why it is listed beside the best one.
 */
class JourneyOption(
    val reason: OptionReason,
    val bus: JourneyChoice?,
    val mixed: MixedJourney?,
    val totalSeconds: Float,
    val walkingMetres: Float,
    val score: Float
) {
    val vehicleCount: Int get() = if (mixed != null) 2 else if (bus?.secondLeg != null) 2 else 1
}

/** Plans journeys from live bus positions and scheduled trains with one round-based search. */
class JourneyPlanner(private val buses: TransitIndex, private val trains: TrainIndex?) {
    /** The bus-train connection that suits best, or null if there is none. */
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
        return plan(JourneyQuery(latitude, longitude, destinationLatitude, destinationLongitude, vehicles,
            walkFromOrigin, walkFromDestination, walkBetweenStops, walkingPreference,
            trainsAt = now, requireTrain = true)).firstOrNull()?.mixed
    }

    /**
     * The best journey first, then those worth a look beside it (fewest changes, soonest, least
     * walking) when they differ. [onDirectJourney] hears the best single bus as soon as the first
     * round is done, before the changes are looked into.
     */
    suspend fun plan(
        query: JourneyQuery,
        onDirectJourney: (suspend (JourneyChoice?) -> Unit)? = null
    ): List<JourneyOption> {
        val context = currentCoroutineContext()
        val fleet = query.tracked ?: buses.trackVehicles(query.vehicles)
        val network = Network(query, fleet)
        network.addBusTrips()
        val trainsOn = trains?.takeIf { query.trainsAt != null }
            ?.let { if (query.includeTrains) it else it.metroOnly }
        if (trainsOn != null) network.addTrainTrips(trainsOn, query.trainsAt!!)

        val request = network.request(query, trainsOn != null)
        val planner = RoundPlanner(request) { context.ensureActive() }
        var stepped = planner.step()
        if (onDirectJourney != null) {
            val direct = planner.best { journey -> journey.vehicles == 1 && !journey.last.lastWasTrain }
            onDirectJourney(direct?.let { network.toBusChoice(it, fleet, query) })
        }
        context.ensureActive()
        while (stepped) stepped = planner.step()
        context.ensureActive()
        return planner.choose().mapNotNull { network.toOption(it, fleet, query) }
    }

    /** The stops and runs of one search, and the walks between them. */
    private inner class Network(val query: JourneyQuery, val fleet: List<TrackedVehicle>) {
        private val indexByKey = HashMap<String, Int>()
        private val ids = ArrayList<String>()
        private val latitudes = ArrayList<Double>()
        private val longitudes = ArrayList<Double>()
        private val isStation = ArrayList<Boolean>()
        private val trips = ArrayList<PlanTrip>()
        private val footpathCache = HashMap<Int, List<Footpath>>()
        private var stationLinks: Map<Int, List<Int>> = emptyMap()

        private fun stop(key: String, latitude: Double, longitude: Double, station: Boolean): Int =
            indexByKey.getOrPut(key) {
                ids.add(key)
                latitudes.add(latitude)
                longitudes.add(longitude)
                isStation.add(station)
                ids.lastIndex
            }

        fun addBusTrips() {
            for (tracked in fleet) {
                val stops = tracked.pattern.stops
                val first = tracked.progress + 1
                if (stops.size - first < 2) continue
                val count = stops.size - first
                val arrive = FloatArray(count) { tracked.arrivalSeconds[first + it] }
                trips.add(PlanTrip(
                    IntArray(count) { stop(stops[first + it].id, stops[first + it].latitude, stops[first + it].longitude, false) },
                    arrive, arrive, false, tracked.pattern.route, tracked::arrivalSigma,
                    BusRun(tracked, first)))
            }
        }

        /** Trains that call at a station a bus stop is within a short walk of. */
        fun addTrainTrips(trains: TrainIndex, now: LocalDateTime) {
            val busStops = ids.indices.filter { !isStation[it] }
            val connecting = trains.stations.filter { station ->
                busStops.any { near(station.latitude, station.longitude, latitudes[it], longitudes[it], STATION_LINK_METRES) }
            }.mapTo(HashSet()) { it.id }
            if (connecting.isEmpty()) return
            val today = now.toLocalDate()
            val secondsToday = now.hour * 3600 + now.minute * 60 + now.second
            // A run after midnight still belongs to yesterday's timetable.
            for (dayOffset in -1..0) {
                val nowInServiceDay = secondsToday - dayOffset * SECONDS_PER_DAY
                for (trip in trains.tripsOn(today.plusDays(dayOffset.toLong()))) {
                    if (trip.stops.none { it.id in connecting }) continue
                    val begin = trip.stops.indexOfFirst { it.departureSeconds >= nowInServiceDay }
                    if (begin < 0 || begin >= trip.stops.lastIndex) continue
                    val stops = trip.stops.subList(begin, trip.stops.size)
                    val arrive = FloatArray(stops.size) { (stops[it].arrivalSeconds - nowInServiceDay).toFloat() }
                    val depart = FloatArray(stops.size) { (stops[it].departureSeconds - nowInServiceDay).toFloat() }
                    trips.add(PlanTrip(
                        IntArray(stops.size) { stop("T:" + stops[it].id, stops[it].latitude, stops[it].longitude, true) },
                        // One metro train is as good as the next: never change between two.
                        arrive, depart, true, if (trip.category == TrainIndex.METRO) TrainIndex.METRO else "",
                        { 0f }, TrainRun(trip, stops, dayOffset)))
                }
            }
            val links = HashMap<Int, List<Int>>()
            for (index in ids.indices) {
                if (!isStation[index]) continue
                links[index] = busStops.filter {
                    near(latitudes[index], longitudes[index], latitudes[it], longitudes[it], STATION_LINK_METRES)
                }
            }
            stationLinks = links
        }

        fun request(query: JourneyQuery, withTrains: Boolean): PlanRequest {
            val count = ids.size
            val originWalk = FloatArray(count)
            val destinationWalk = FloatArray(count)
            for (index in 0 until count) {
                val lat = latitudes[index]
                val lon = longitudes[index]
                val fromOrigin = query.walkFromOrigin?.invoke(lat, lon)
                    ?: straight(query.latitude, query.longitude, lat, lon)
                // Nobody is asked to walk past the preset to a station.
                originWalk[index] = if (isStation[index] && fromOrigin > query.walkingPreference.maxStationWalkMetres)
                    Float.POSITIVE_INFINITY else fromOrigin
                destinationWalk[index] = query.walkFromDestination?.invoke(lat, lon)
                    ?: straight(query.destinationLatitude, query.destinationLongitude, lat, lon)
            }
            val initial = query.walkFromOrigin?.invoke(query.destinationLatitude, query.destinationLongitude)
                ?: straight(query.latitude, query.longitude, query.destinationLatitude, query.destinationLongitude)
            return PlanRequest(count, trips, ::footpathsFrom, originWalk, destinationWalk, initial,
                query.walkingPreference.extraWalkingWeight, query.maxVehicles,
                if (withTrains) 1 else 0, query.requireTrain, query.earliestStartSeconds,
                query.maxDestinationWalkMetres)
        }

        private fun footpathsFrom(index: Int): List<Footpath> = footpathCache.getOrPut(index) {
            val paths = ArrayList<Footpath>()
            fun walk(other: Int, straight: Float, limit: Float) {
                val metres = query.walkBetweenStops?.invoke(latitudes[index], longitudes[index],
                    latitudes[other], longitudes[other]) ?: straight
                if (metres <= limit) paths.add(Footpath(other, metres))
            }
            if (isStation[index]) {
                for (other in stationLinks[index].orEmpty()) {
                    walk(other, straight(latitudes[index], longitudes[index], latitudes[other], longitudes[other]),
                        STATION_WALK_METRES)
                }
            } else {
                for ((otherId, metres) in buses.transferNeighbours(ids[index])) {
                    val other = indexByKey[otherId] ?: continue
                    if (other != index) walk(other, metres, TransitIndex.MAX_TRANSFER_WALK)
                }
                for ((station, links) in stationLinks) {
                    if (index in links) walk(station,
                        straight(latitudes[index], longitudes[index], latitudes[station], longitudes[station]),
                        STATION_WALK_METRES)
                }
            }
            paths
        }

        fun toOption(choice: PlannedChoice, fleet: List<TrackedVehicle>, query: JourneyQuery): JourneyOption? {
            val journey = choice.journey
            val legs = journey.legs()
            val trainLegs = legs.count { it.trip.isTrain }
            return when {
                trainLegs == 0 && legs.size in 1..2 -> {
                    val bus = busJourney(journey, legs, fleet, query) ?: return null
                    JourneyOption(choice.reason, bus, null, journey.totalSeconds, journey.walkingMetres, journey.score)
                }
                trainLegs == 1 && legs.size == 2 -> {
                    val mixed = mixedJourney(journey, legs, fleet, query) ?: return null
                    JourneyOption(choice.reason, null, mixed, journey.totalSeconds, journey.walkingMetres, journey.score)
                }
                // More than a bus and a train is more than the journey panel can follow.
                else -> null
            }
        }

        fun toBusChoice(journey: PlannedJourney, fleet: List<TrackedVehicle>, query: JourneyQuery): JourneyChoice? =
            busJourney(journey, journey.legs(), fleet, query)

        private fun busJourney(
            journey: PlannedJourney,
            legs: List<PlannedLeg>,
            fleet: List<TrackedVehicle>,
            query: JourneyQuery
        ): JourneyChoice? {
            val first = legs.first()
            val second = legs.getOrNull(1)
            val firstRun = first.trip.payload as? BusRun ?: return null
            val secondRun = second?.trip?.payload as? BusRun
            val choice = busChoice(firstRun, first, query, journey.destinationWalkMetres, journey.totalSeconds,
                finalLatitude = query.destinationLatitude, finalLongitude = query.destinationLongitude,
                next = if (second != null && secondRun != null) second to secondRun else null)
            return withBackup(choice, fleet, first, query)
        }

        private fun mixedJourney(
            journey: PlannedJourney,
            legs: List<PlannedLeg>,
            fleet: List<TrackedVehicle>,
            query: JourneyQuery
        ): MixedJourney? {
            val busFirst = !legs[0].trip.isTrain
            val busLeg = if (busFirst) legs[0] else legs[1]
            val trainLeg = if (busFirst) legs[1] else legs[0]
            val run = busLeg.trip.payload as? BusRun ?: return null
            val train = trainLeg.trip.payload as? TrainRun ?: return null
            val boardStop = train.stops[trainLeg.boardPos]
            val exitStop = train.stops[trainLeg.alightPos]
            val timetable = train.trip
            return if (busFirst) {
                // The bus ends at the station; the walk into it belongs to the bus, the rest to the train.
                val atStation = busLeg.arrival.time + trainLeg.walkBeforeMetres / RoundPlanner.WALK_SPEED
                val bus = busChoice(run, busLeg, query, trainLeg.walkBeforeMetres, atStation,
                    boardStop.latitude, boardStop.longitude, null)
                val busSeconds = ceil(bus.estimatedTotalSeconds)
                val trainJourney = ScheduledTrainJourney(timetable.number, timetable.category, timetable.headsign,
                    boardStop, exitStop, timetable.stops, train.begin + trainLeg.boardPos,
                    train.begin + trainLeg.alightPos, 0f, journey.destinationWalkMetres,
                    journey.totalSeconds - busSeconds, boardStop.departureSeconds, exitStop.arrivalSeconds,
                    maxOf(train.dayOffset, 0))
                MixedJourney(withBackup(bus, fleet, busLeg, query), trainJourney, true, journey.totalSeconds)
            } else {
                val trainJourney = ScheduledTrainJourney(timetable.number, timetable.category, timetable.headsign,
                    boardStop, exitStop, timetable.stops, train.begin + trainLeg.boardPos,
                    train.begin + trainLeg.alightPos, trainLeg.walkBeforeMetres, 0f,
                    trainLeg.arrival.time, boardStop.departureSeconds, exitStop.arrivalSeconds,
                    maxOf(train.dayOffset, 0))
                val bus = busChoice(run, busLeg, query, journey.destinationWalkMetres, journey.totalSeconds,
                    query.destinationLatitude, query.destinationLongitude, null)
                MixedJourney(bus, trainJourney, false, journey.totalSeconds)
            }
        }

        /** Builds the bus journey that starts with [leg]; [next] is a bus it changes to. */
        private fun busChoice(
            run: BusRun,
            leg: PlannedLeg,
            query: JourneyQuery,
            lastWalkMetres: Float,
            totalSeconds: Float,
            finalLatitude: Double,
            finalLongitude: Double,
            next: Pair<PlannedLeg, BusRun>?
        ): JourneyChoice {
            val tracked = run.tracked
            val pattern = tracked.pattern
            val boardIndex = run.firstIndex + leg.boardPos
            val exitIndex = run.firstIndex + leg.alightPos
            val vehicleDistance = straight(query.latitude, query.longitude,
                tracked.vehicle.latitude, tracked.vehicle.longitude)
            val second = next?.let { (secondLeg, secondRun) ->
                val secondPattern = secondRun.tracked.pattern
                val secondBoard = secondRun.firstIndex + secondLeg.boardPos
                val secondExit = secondRun.firstIndex + secondLeg.alightPos
                val secondEta = secondLeg.trip.depart[secondLeg.boardPos]
                val atChange = leg.trip.arrive[leg.alightPos] + secondLeg.walkBeforeMetres / RoundPlanner.WALK_SPEED
                Triple(secondLeg, secondRun, JourneyLeg(secondPattern, secondRun.tracked.vehicle,
                    secondPattern.stops[secondBoard], secondPattern.stops[secondExit],
                    secondExit - secondBoard, secondRun.tracked.turnaround != null,
                    secondEta, secondEta - atChange))
            }
            val exit = second?.third?.exitAt ?: pattern.stops[exitIndex]
            return JourneyChoice(
                pattern = pattern,
                vehicle = tracked.vehicle,
                boardAt = pattern.stops[boardIndex],
                boardStopIndex = boardIndex,
                destination = exit,
                vehicleDistanceMetres = vehicleDistance,
                walkingMetres = leg.walkBeforeMetres,
                stopCount = exitIndex - boardIndex,
                destinationWalkMetres = lastWalkMetres,
                finalLatitude = finalLatitude,
                finalLongitude = finalLongitude,
                boardingEtaSeconds = leg.trip.depart[leg.boardPos],
                estimatedTotalSeconds = totalSeconds,
                secondLeg = second?.third,
                transferAt = if (second != null) pattern.stops[exitIndex] else null,
                transferWalkMetres = second?.first?.walkBeforeMetres ?: 0f,
                turnaround = tracked.turnaround,
                boardingChance = leg.boardChance,
                transferChance = second?.first?.boardChance ?: 1f
            )
        }

        private fun withBackup(choice: JourneyChoice, fleet: List<TrackedVehicle>, first: PlannedLeg,
            query: JourneyQuery) = buses.withBackup(choice, fleet, query.earliestStartSeconds, first.walkBeforeMetres)
    }

    /** A live bus's run, and the pattern stop its first network stop is. */
    private class BusRun(val tracked: TrackedVehicle, val firstIndex: Int)

    /** A scheduled train's run from its [begin]th stop, on the timetable of [dayOffset] days from today. */
    private class TrainRun(val trip: TrainIndex.TrainTrip, val stops: List<TrainStop>, val dayOffset: Int) {
        val begin: Int get() = trip.stops.size - stops.size
    }

    private fun straight(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(fromLat, fromLon, toLat, toLon, result)
        return result[0]
    }

    private fun near(lat1: Double, lon1: Double, lat2: Double, lon2: Double, metres: Float): Boolean {
        if (abs(lat1 - lat2) > metres / 110_000.0) return false
        if (abs(lon1 - lon2) > metres / (110_000.0 * cos(Math.toRadians(lat1)).coerceAtLeast(0.01))) return false
        return straight(lat1, lon1, lat2, lon2) <= metres
    }

    private companion object {
        // A bus stop this near a station is a place to change between bus and train.
        const val STATION_LINK_METRES = 500f
        // ... if the streets agree it is not much further than that.
        const val STATION_WALK_METRES = 750f
        const val SECONDS_PER_DAY = 86_400
    }
}
