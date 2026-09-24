package it.mato.livebus

import android.content.Context
import android.location.Location
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class TransitStop(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double
)

data class TransitPattern(
    val id: String,
    val routeId: String,
    val route: String,
    val headsign: String,
    val directionId: String,
    val tripIds: Set<String>,
    val stops: List<TransitStop>
)

data class JourneyChoice(
    val pattern: TransitPattern,
    val vehicle: LiveVehicle,
    val boardAt: TransitStop,
    val boardStopIndex: Int,
    val destination: TransitStop,
    val vehicleDistanceMetres: Float,
    val walkingMetres: Float,
    val stopCount: Int,
    val destinationWalkMetres: Float,
    val finalLatitude: Double,
    val finalLongitude: Double,
    val boardingEtaSeconds: Float = 0f,
    val estimatedTotalSeconds: Float = 0f,
    val secondLeg: JourneyLeg? = null,
    val transferAt: TransitStop? = null,
    val transferWalkMetres: Float = 0f
) {
    val totalWalkingMetres: Float get() = walkingMetres + transferWalkMetres + destinationWalkMetres
    val directionEstimated: Boolean get() = vehicle.tripId !in pattern.tripIds ||
        secondLeg?.let { it.vehicle.tripId !in it.pattern.tripIds } == true
}

data class JourneyLeg(
    val pattern: TransitPattern,
    val vehicle: LiveVehicle,
    val boardAt: TransitStop,
    val exitAt: TransitStop,
    val stopCount: Int
)

class TransitIndex private constructor(val patterns: List<TransitPattern>) {
    private val patternsByTripId: Map<String, TransitPattern> by lazy {
        buildMap {
            patterns.forEach { pattern ->
                pattern.tripIds.forEach { tripId -> put(tripId, pattern) }
            }
        }
    }
    private val patternsByRoute by lazy { patterns.groupBy { it.routeId } }
    // Static geometry is computed once, independently of live vehicle pairs.
    private val cumulativeDistances by lazy {
        patterns.associate { pattern ->
            val distances = FloatArray(pattern.stops.size)
            for (index in 1 until distances.size) {
                distances[index] = distances[index - 1] +
                    distanceBetween(pattern.stops[index - 1], pattern.stops[index])
            }
            pattern.id to distances
        }
    }
    private val transferStops by lazy {
        val stops = patterns.flatMap { it.stops }.distinctBy { it.id }.sortedBy { it.latitude }
        val neighbours = mutableMapOf<String, List<Pair<String, Float>>>()
        var lower = 0
        var upper = 0
        val latitudeRange = MAX_TRANSFER_WALK / 110_000.0
        for (stop in stops) {
            while (lower < stops.size && stops[lower].latitude < stop.latitude - latitudeRange) lower++
            while (upper < stops.size && stops[upper].latitude <= stop.latitude + latitudeRange) upper++
            val longitudeRange = latitudeRange / cos(Math.toRadians(stop.latitude)).coerceAtLeast(0.01)
            neighbours[stop.id] = (lower until upper).mapNotNull { index ->
                val other = stops[index]
                if (abs(other.longitude - stop.longitude) > longitudeRange) null
                else distanceBetween(stop, other).takeIf { it <= MAX_TRANSFER_WALK }
                    ?.let { other.id to it }
            }
        }
        neighbours
    }
    private val vehicleProgress = mutableMapOf<String, ObservedProgress>()

    val destinationNames: List<String> = patterns
        .flatMap { it.stops }
        .map { cleanStopName(it.name) }
        .distinct()
        .sorted()

    fun patternsForRoute(route: String) = patterns.filter { it.route == route }

    suspend fun planLiveJourney(
        latitude: Double,
        longitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        vehicles: List<LiveVehicle>,
        onDirectJourney: (suspend (JourneyChoice?) -> Unit)? = null,
        walkFromOrigin: ((Double, Double) -> Float)? = null,
        walkFromDestination: ((Double, Double) -> Float)? = null,
        walkBetweenStops: ((Double, Double, Double, Double) -> Float)? = null,
        earliestStartSeconds: Float = 0f,
        maxDestinationWalkMetres: Float = Float.POSITIVE_INFINITY,
        directOnly: Boolean = false,
        walkingPreference: WalkingPreference = WalkingPreference.MORE
    ): JourneyChoice? {
        data class Tracked(
            val vehicle: LiveVehicle,
            val pattern: TransitPattern,
            val progress: Int
        )

        val context = currentCoroutineContext()
        val walkingDistances = mutableMapOf<String, Float>()
        val destinationDistances = mutableMapOf<String, Float>()
        val transferWalkingDistances = mutableMapOf<Pair<String, String>, Float>()
        fun walkingDistance(stop: TransitStop) = walkingDistances.getOrPut(stop.id) {
            walkFromOrigin?.invoke(stop.latitude, stop.longitude)
                ?: distance(latitude, longitude, stop)
        }
        fun destinationDistance(stop: TransitStop) = destinationDistances.getOrPut(stop.id) {
            walkFromDestination?.invoke(stop.latitude, stop.longitude)
                ?: distance(destinationLatitude, destinationLongitude, stop)
        }
        val tracked = vehicles.mapNotNull { vehicle ->
            context.ensureActive()
            val pattern = matchDirection(vehicle) ?: return@mapNotNull null
            val progress = progressIndex(vehicle, pattern) ?: return@mapNotNull null
            Tracked(vehicle, pattern, progress)
        }

        val initialDistance = walkFromOrigin?.invoke(destinationLatitude, destinationLongitude)
            ?: distance(latitude, longitude,
                TransitStop("", "", destinationLatitude, destinationLongitude))
        val direct = tracked.flatMap { current ->
            context.ensureActive()
            val vehicle = current.vehicle
            val pattern = current.pattern
            val progressIndex = current.progress
            // A later, reachable exit can still help even if the closest stop
            // to the destination occurs before we can board this vehicle.
            pattern.stops.indices.drop(progressIndex + 2).mapNotNull { destinationIndex ->
                context.ensureActive()
                val destinationStop = pattern.stops[destinationIndex]
                val remainingWalk = destinationDistance(destinationStop)
                if (remainingWalk >= initialDistance ||
                    remainingWalk > maxDestinationWalkMetres) return@mapNotNull null
                val destinationCandidate = Triple(destinationIndex, destinationStop, remainingWalk)
                val boarding = pattern.stops.indices
                    .drop(progressIndex + 1)
                    .takeWhile { it < destinationIndex }
                    .mapNotNull { index ->
                        val stop = pattern.stops[index]
                        val walkMetres = walkingDistance(stop)
                        val walkSeconds = walkMetres / WALKING_METRES_PER_SECOND
                        val busSeconds = busSecondsToStop(vehicle, pattern, progressIndex, index)
                        if (destinationCandidate.third >= destinationDistance(stop) ||
                            earliestStartSeconds + walkSeconds + BOARDING_MARGIN_SECONDS > busSeconds) null
                        else BoardingCandidate(index, stop, walkMetres, walkSeconds, busSeconds)
                    }
                    .minByOrNull { candidate ->
                        val rideSeconds = routeDistance(pattern, candidate.index, destinationIndex) /
                            BUS_METRES_PER_SECOND
                        walkingPreference.score(
                            maxOf(earliestStartSeconds + candidate.walkSeconds, candidate.busSeconds) +
                                rideSeconds +
                                (destinationIndex - candidate.index) * DWELL_SECONDS_PER_STOP,
                            candidate.walkMetres
                        )
                    } ?: return@mapNotNull null
                val rideSeconds = routeDistance(pattern, boarding.index, destinationIndex) /
                    BUS_METRES_PER_SECOND
                val totalSeconds = maxOf(earliestStartSeconds + boarding.walkSeconds, boarding.busSeconds) +
                    rideSeconds +
                    (destinationIndex - boarding.index) * DWELL_SECONDS_PER_STOP +
                    destinationCandidate.third / WALKING_METRES_PER_SECOND
                JourneyChoice(
                    pattern = pattern,
                    vehicle = vehicle,
                    boardAt = boarding.stop,
                    boardStopIndex = boarding.index,
                    destination = pattern.stops[destinationIndex],
                    vehicleDistanceMetres = distance(
                        latitude,
                        longitude,
                        TransitStop("", "", vehicle.latitude, vehicle.longitude)
                    ),
                    walkingMetres = boarding.walkMetres,
                    stopCount = destinationIndex - boarding.index,
                    destinationWalkMetres = destinationCandidate.third,
                    finalLatitude = destinationLatitude,
                    finalLongitude = destinationLongitude,
                    boardingEtaSeconds = boarding.busSeconds,
                    estimatedTotalSeconds = totalSeconds
                )
            }
        }

        var best = direct.minByOrNull { journeyScore(it, walkingPreference) }
        onDirectJourney?.invoke(best)
        context.ensureActive()
        if (directOnly) return best

        // Index only boarding occurrences that each live vehicle has yet to reach.
        val vehiclesByStop = mutableMapOf<String, MutableList<Pair<Tracked, Int>>>()
        for (second in tracked) {
            for (index in second.progress + 1 until second.pattern.stops.lastIndex) {
                vehiclesByStop.getOrPut(second.pattern.stops[index].id) { mutableListOf() }
                    .add(second to index)
            }
        }
        val destinationsByPattern = tracked.distinctBy { it.pattern.id }.associate { trackedPattern ->
            val stops = trackedPattern.pattern.stops
            val exits = IntArray(stops.size)
            var closest = stops.lastIndex
            for (index in stops.indices.reversed()) {
                if (destinationDistance(stops[index]) <= destinationDistance(stops[closest])) closest = index
                exits[index] = closest
            }
            trackedPattern.pattern.id to exits
        }
        for (first in tracked) {
            context.ensureActive()
            val pattern = first.pattern
            var firstBoarding: BoardingCandidate? = null
            for (exitIndex in first.progress + 2 until pattern.stops.size) {
                context.ensureActive()
                val boardIndex = exitIndex - 1
                val board = pattern.stops[boardIndex]
                val walk = walkingDistance(board)
                val walkSeconds = walk / WALKING_METRES_PER_SECOND
                val busSeconds = busSecondsToStop(first.vehicle, pattern, first.progress, boardIndex)
                if (walkSeconds + BOARDING_MARGIN_SECONDS <= busSeconds) {
                    val candidate = BoardingCandidate(boardIndex, board, walk, walkSeconds, busSeconds)
                    val previous = firstBoarding
                    // Compare arrival at this exit, including the ride after boarding.
                    if (previous == null || busSeconds < previous.busSeconds +
                        routeDistance(pattern, previous.index, boardIndex) / BUS_METRES_PER_SECOND +
                        (boardIndex - previous.index) * DWELL_SECONDS_PER_STOP) {
                        firstBoarding = candidate
                    }
                }
                val boarding = firstBoarding ?: continue
                val firstArrival = boarding.busSeconds +
                    routeDistance(pattern, boarding.index, exitIndex) / BUS_METRES_PER_SECOND +
                    (exitIndex - boarding.index) * DWELL_SECONDS_PER_STOP
                if (walkingPreference == WalkingPreference.MORE && best != null &&
                    firstArrival >= best.estimatedTotalSeconds) continue
                for ((stopId, transferWalk) in transferStops[pattern.stops[exitIndex].id].orEmpty()) {
                    for ((second, secondBoardIndex) in vehiclesByStop[stopId].orEmpty()) {
                        context.ensureActive()
                        if (second.pattern.route == pattern.route) continue
                        val exitStop = pattern.stops[exitIndex]
                        val secondBoard = second.pattern.stops[secondBoardIndex]
                        val streetTransferWalk = transferWalkingDistances.getOrPut(exitStop.id to stopId) {
                            walkBetweenStops?.invoke(exitStop.latitude, exitStop.longitude,
                                secondBoard.latitude, secondBoard.longitude) ?: transferWalk
                        }
                        if (streetTransferWalk > MAX_TRANSFER_WALK) continue
                        val secondArrival = busSecondsToStop(
                            second.vehicle, second.pattern, second.progress, secondBoardIndex
                        )
                        if (firstArrival + streetTransferWalk / WALKING_METRES_PER_SECOND +
                            BOARDING_MARGIN_SECONDS > secondArrival) continue
                        val destinationIndex = destinationsByPattern.getValue(second.pattern.id)[secondBoardIndex + 1]
                        val destination = second.pattern.stops[destinationIndex]
                        val destinationWalk = destinationDistance(destination)
                        if (destinationWalk >= initialDistance ||
                            destinationWalk >= destinationDistance(boarding.stop)) continue
                        val total = secondArrival +
                            routeDistance(second.pattern, secondBoardIndex, destinationIndex) / BUS_METRES_PER_SECOND +
                            (destinationIndex - secondBoardIndex) * DWELL_SECONDS_PER_STOP +
                            destinationWalk / WALKING_METRES_PER_SECOND
                        val candidate = JourneyChoice(
                            pattern = pattern,
                            vehicle = first.vehicle,
                            boardAt = boarding.stop,
                            boardStopIndex = boarding.index,
                            destination = destination,
                            vehicleDistanceMetres = distance(latitude, longitude,
                                TransitStop("", "", first.vehicle.latitude, first.vehicle.longitude)),
                            walkingMetres = boarding.walkMetres,
                            stopCount = exitIndex - boarding.index,
                            destinationWalkMetres = destinationWalk,
                            finalLatitude = destinationLatitude,
                            finalLongitude = destinationLongitude,
                            boardingEtaSeconds = boarding.busSeconds,
                            estimatedTotalSeconds = total,
                            secondLeg = JourneyLeg(second.pattern, second.vehicle,
                                second.pattern.stops[secondBoardIndex], destination,
                                destinationIndex - secondBoardIndex),
                            transferAt = pattern.stops[exitIndex],
                            transferWalkMetres = streetTransferWalk
                        )
                        if (best == null || journeyScore(candidate, walkingPreference) <
                            journeyScore(best, walkingPreference)) best = candidate
                    }
                }
            }
        }
        return best
    }

    private fun journeyScore(choice: JourneyChoice, walkingPreference: WalkingPreference): Float {
        if (choice.estimatedTotalSeconds > 0f) return walkingPreference.score(
            choice.estimatedTotalSeconds, choice.totalWalkingMetres)
        val transferPenalty = if (choice.secondLeg == null) 0f else 650f
        val secondBusPenalty = choice.secondLeg?.let {
            distance(
                it.boardAt.latitude,
                it.boardAt.longitude,
                TransitStop("", "", it.vehicle.latitude, it.vehicle.longitude)
            ) * 0.2f
        } ?: 0f
        return choice.destinationWalkMetres * 3f +
            choice.vehicleDistanceMetres +
            choice.walkingMetres * 0.15f +
            choice.stopCount * 35f +
            (choice.secondLeg?.stopCount ?: 0) * 35f +
            choice.transferWalkMetres * 2f +
            transferPenalty +
            secondBusPenalty
    }

    private data class BoardingCandidate(
        val index: Int,
        val stop: TransitStop,
        val walkMetres: Float,
        val walkSeconds: Float,
        val busSeconds: Float
    )

    private fun busSecondsToStop(
        vehicle: LiveVehicle,
        pattern: TransitPattern,
        progressIndex: Int,
        boardingIndex: Int
    ): Float {
        val nextIndex = (progressIndex + 1).coerceAtMost(pattern.stops.lastIndex)
        val vehicleToNext = distance(
            vehicle.latitude,
            vehicle.longitude,
            pattern.stops[nextIndex]
        )
        val routeMetres = routeDistance(pattern, nextIndex, boardingIndex)
        val intermediateStops = (boardingIndex - nextIndex).coerceAtLeast(0)
        return (vehicleToNext + routeMetres) / BUS_METRES_PER_SECOND +
            intermediateStops * DWELL_SECONDS_PER_STOP - vehicle.ageSeconds
    }

    private fun routeDistance(pattern: TransitPattern, fromIndex: Int, toIndex: Int): Float {
        if (toIndex <= fromIndex) return 0f
        val distances = cumulativeDistances.getValue(pattern.id)
        return distances[toIndex] - distances[fromIndex]
    }

    fun matchDirection(vehicle: LiveVehicle): TransitPattern? {
        exactTripPattern(vehicle)?.let { return it }

        // GTT often supplies a route and position without a usable scheduled trip ID.
        // Limit inference to nearby segments of that route, using heading when available.
        val candidates = patternsByRoute[vehicle.routeId].orEmpty().mapNotNull { pattern ->
            val score = pattern.stops.zipWithNext().mapNotNull { (from, to) ->
                segmentScore(vehicle, from, to, inferred = true).takeIf { it.isFinite() }
            }.minOrNull() ?: return@mapNotNull null
            pattern to score
        }.sortedBy { it.second }
        val best = candidates.firstOrNull() ?: return null
        // Overlapping opposite directions without enough evidence are not a match.
        if (candidates.any { it.first.directionId != best.first.directionId &&
                it.second - best.second < 75.0 }) return null
        return best.first
    }

    fun isBoardingStopAhead(
        vehicle: LiveVehicle,
        pattern: TransitPattern,
        stopId: String
    ): Boolean {
        val progress = progressIndex(vehicle, pattern) ?: return false
        // The stop is catchable only if the matched pattern still has an occurrence
        // of it after the segment the vehicle is currently travelling on.
        return pattern.stops.drop(progress + 1).any { it.id == stopId }
    }

    fun isStopIndexAhead(
        vehicle: LiveVehicle,
        pattern: TransitPattern,
        stopIndex: Int
    ): Boolean {
        val progress = progressIndex(vehicle, pattern) ?: return false
        return stopIndex > progress
    }

    fun exactTripPattern(vehicle: LiveVehicle): TransitPattern? =
        vehicle.tripId?.let { patternsByTripId[it] }
            ?.takeIf { it.routeId == vehicle.routeId }

    @Synchronized
    private fun progressIndex(vehicle: LiveVehicle, pattern: TransitPattern): Int? {
        val inferred = exactTripPattern(vehicle) == null
        val rawProgress = pattern.stops.zipWithNext()
            .withIndex()
            .minByOrNull { (_, segment) ->
                segmentScore(vehicle, segment.first, segment.second, inferred)
            }
            ?.takeIf { (_, segment) ->
                segmentScore(vehicle, segment.first, segment.second, inferred).isFinite()
            }
            ?.index
            ?: return null
        val prior = vehicleProgress[vehicle.id]
        val sameTrip = !inferred && prior != null && prior.tripId == vehicle.tripId &&
            prior.patternId == pattern.id
        val progress = if (sameTrip) maxOf(prior!!.progress, rawProgress) else rawProgress
        vehicleProgress[vehicle.id] = ObservedProgress(vehicle.tripId, pattern.id, progress)
        return progress
    }

    private data class ObservedProgress(
        val tripId: String?,
        val patternId: String,
        val progress: Int
    )

    private fun segmentScore(
        vehicle: LiveVehicle,
        from: TransitStop,
        to: TransitStop,
        inferred: Boolean = false
    ): Double {
        val segmentDistance = distanceToSegment(
            vehicle.latitude, vehicle.longitude, from, to
        )
        val heading = vehicle.bearing?.let { angleDifference(it.toDouble(), bearing(from, to)) }
        if (inferred && (segmentDistance > 500.0 ||
                heading != null && heading > MAX_FORWARD_BEARING_DIFFERENCE)) return Double.POSITIVE_INFINITY
        return segmentDistance + (heading ?: 0.0) * 5.0
    }

    private fun isStopAheadOfVehicle(vehicle: LiveVehicle, stop: TransitStop): Boolean {
        val vehicleBearing = vehicle.bearing ?: return true
        val metres = distance(vehicle.latitude, vehicle.longitude, stop)
        if (metres > MAX_BOARDING_HEADING_CHECK_METRES) return true
        val vehiclePoint = TransitStop(
            id = "",
            name = "",
            latitude = vehicle.latitude,
            longitude = vehicle.longitude
        )
        return angleDifference(
            vehicleBearing.toDouble(),
            bearing(vehiclePoint, stop)
        ) <= MAX_FORWARD_BEARING_DIFFERENCE
    }

    companion object {
        fun load(context: Context): TransitIndex {
            val root = JSONObject(
                context.assets.open("transit_index.json").bufferedReader().use { it.readText() }
            )
            val array = root.getJSONArray("patterns")
            val patterns = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val stopsArray = item.getJSONArray("stops")
                TransitPattern(
                    id = item.getString("id"),
                    routeId = item.getString("routeId"),
                    route = item.getString("route"),
                    headsign = item.getString("headsign"),
                    directionId = item.getString("directionId"),
                    tripIds = item.optJSONArray("tripIds")?.let { tripIds ->
                        (0 until tripIds.length()).mapTo(mutableSetOf()) {
                            tripIds.getString(it)
                        }
                    } ?: emptySet(),
                    stops = (0 until stopsArray.length()).map { stopIndex ->
                        val stop = stopsArray.getJSONObject(stopIndex)
                        TransitStop(
                            id = stop.getString("id"),
                            name = stop.getString("name"),
                            latitude = stop.getDouble("lat"),
                            longitude = stop.getDouble("lon")
                        )
                    }
                )
            }
            return TransitIndex(patterns)
        }

        fun cleanStopName(value: String) =
            value.replace(Regex("""^Fermata\s+\d+\s*-\s*""", RegexOption.IGNORE_CASE), "")
                .trim()

        private fun distance(lat: Double, lon: Double, stop: TransitStop): Float {
            val result = FloatArray(1)
            Location.distanceBetween(lat, lon, stop.latitude, stop.longitude, result)
            return result[0]
        }

        private fun distanceBetween(first: TransitStop, second: TransitStop): Float =
            distance(first.latitude, first.longitude, second)

        private fun bearing(from: TransitStop, to: TransitStop): Double {
            val lat1 = Math.toRadians(from.latitude)
            val lat2 = Math.toRadians(to.latitude)
            val deltaLon = Math.toRadians(to.longitude - from.longitude)
            return (Math.toDegrees(atan2(
                sin(deltaLon) * cos(lat2),
                cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
            )) + 360) % 360
        }

        private fun angleDifference(first: Double, second: Double): Double {
            val difference = abs(first - second) % 360
            return if (difference > 180) 360 - difference else difference
        }

        private fun distanceToSegment(
            latitude: Double,
            longitude: Double,
            from: TransitStop,
            to: TransitStop
        ): Double {
            val scale = cos(Math.toRadians(latitude))
            val px = longitude * scale
            val py = latitude
            val ax = from.longitude * scale
            val ay = from.latitude
            val bx = to.longitude * scale
            val by = to.latitude
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0 else
                (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val nearestLon = (ax + t * dx) / scale
            val nearestLat = ay + t * dy
            val result = FloatArray(1)
            Location.distanceBetween(latitude, longitude, nearestLat, nearestLon, result)
            return result[0].toDouble()
        }

        private const val MAX_TRANSFER_WALK = 350f
        private const val WALKING_METRES_PER_SECOND = 1.2f
        private const val BUS_METRES_PER_SECOND = 6.0f
        private const val DWELL_SECONDS_PER_STOP = 18f
        // Reaching a stop uses the same walking pace as the journey estimate.
        private const val BOARDING_MARGIN_SECONDS = 0f
        private const val MAX_BOARDING_HEADING_CHECK_METRES = 2_000f
        private const val MAX_FORWARD_BEARING_DIFFERENCE = 100.0
    }
}
