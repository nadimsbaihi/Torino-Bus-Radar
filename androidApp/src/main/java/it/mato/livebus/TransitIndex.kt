package it.mato.livebus

import android.content.Context
import android.location.Location
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
)

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
    private val vehicleProgress = mutableMapOf<String, ObservedProgress>()

    val destinationNames: List<String> = patterns
        .flatMap { it.stops }
        .map { cleanStopName(it.name) }
        .distinct()
        .sorted()

    fun patternsForRoute(route: String) = patterns.filter { it.route == route }

    fun planLiveJourney(
        latitude: Double,
        longitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        vehicles: List<LiveVehicle>
    ): JourneyChoice? {
        data class Tracked(
            val vehicle: LiveVehicle,
            val pattern: TransitPattern,
            val progress: Int
        )

        val tracked = vehicles.mapNotNull { vehicle ->
            // Journey planning must use the exact scheduled trip reported by GTT.
            // A geometrically similar route in the opposite direction is not safe
            // enough to decide whether a vehicle has passed a stop.
            val pattern = exactTripPattern(vehicle) ?: return@mapNotNull null
            val progress = progressIndex(vehicle, pattern) ?: return@mapNotNull null
            Tracked(vehicle, pattern, progress)
        }

        val direct = tracked.mapNotNull { current ->
            val vehicle = current.vehicle
            val pattern = current.pattern
            val progressIndex = current.progress
            val destinationCandidate = pattern.stops
                .drop(progressIndex + 2)
                .mapIndexed { index, stop ->
                    Triple(
                        progressIndex + 2 + index,
                        stop,
                        distance(destinationLatitude, destinationLongitude, stop)
                    )
                }
                .minByOrNull { it.third } ?: return@mapNotNull null
            val destinationIndex = destinationCandidate.first
            val boarding = pattern.stops.indices
                .drop(progressIndex + 1)
                .takeWhile { it < destinationIndex }
                .mapNotNull { index ->
                    val stop = pattern.stops[index]
                    val walkMetres = distance(latitude, longitude, stop)
                    val walkSeconds = walkMetres / WALKING_METRES_PER_SECOND
                    val busSeconds = busSecondsToStop(vehicle, pattern, progressIndex, index)
                    if (walkSeconds + BOARDING_MARGIN_SECONDS > busSeconds) null
                    else BoardingCandidate(index, stop, walkMetres, walkSeconds, busSeconds)
                }
                .minByOrNull { candidate ->
                    val rideSeconds = routeDistance(pattern, candidate.index, destinationIndex) /
                        BUS_METRES_PER_SECOND
                    maxOf(candidate.walkSeconds, candidate.busSeconds) +
                        rideSeconds +
                        (destinationIndex - candidate.index) * DWELL_SECONDS_PER_STOP
                } ?: return@mapNotNull null
            val rideSeconds = routeDistance(pattern, boarding.index, destinationIndex) /
                BUS_METRES_PER_SECOND
            val totalSeconds = maxOf(boarding.walkSeconds, boarding.busSeconds) +
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

        val nearbyFirstLegs = tracked.sortedBy {
            distance(
                latitude,
                longitude,
                TransitStop("", "", it.vehicle.latitude, it.vehicle.longitude)
            )
        }.take(50)
        val transfers = nearbyFirstLegs.flatMap { first ->
            tracked.asSequence()
                .filter { second ->
                    second.pattern.id != first.pattern.id &&
                        second.pattern.route != first.pattern.route
                }
                .mapNotNull { second ->
                    transferJourney(
                        latitude,
                        longitude,
                        destinationLatitude,
                        destinationLongitude,
                        first.vehicle,
                        first.pattern,
                        first.progress,
                        second.vehicle,
                        second.pattern,
                        second.progress
                    )
                }
                .sortedBy(::journeyScore)
                .take(3)
                .toList()
        }

        return (direct + transfers).minWithOrNull(
            compareBy<JourneyChoice>(::journeyScore)
                .thenBy { it.walkingMetres }
        )
    }

    private fun transferJourney(
        userLatitude: Double,
        userLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        firstVehicle: LiveVehicle,
        firstPattern: TransitPattern,
        firstProgress: Int,
        secondVehicle: LiveVehicle,
        secondPattern: TransitPattern,
        secondProgress: Int
    ): JourneyChoice? {
        var bestTransfer: TransferCandidate? = null
        firstPattern.stops.indices.drop(firstProgress + 2).forEach { firstExitIndex ->
            secondPattern.stops.indices.drop(secondProgress + 1).forEach { secondBoardIndex ->
                val walk = distanceBetween(
                    firstPattern.stops[firstExitIndex],
                    secondPattern.stops[secondBoardIndex]
                )
                if (walk <= MAX_TRANSFER_WALK &&
                    (bestTransfer == null || walk < bestTransfer!!.walkMetres)
                ) {
                    bestTransfer = TransferCandidate(firstExitIndex, secondBoardIndex, walk)
                }
            }
        }
        val transfer = bestTransfer ?: return null
        if (transfer.secondBoardIndex >= secondPattern.stops.lastIndex) return null

        val destination = secondPattern.stops.indices
            .drop(transfer.secondBoardIndex + 1)
            .map { index ->
                Triple(
                    index,
                    secondPattern.stops[index],
                    distance(destinationLatitude, destinationLongitude, secondPattern.stops[index])
                )
            }
            .minByOrNull { it.third } ?: return null

        val firstBoarding = firstPattern.stops.indices
            .drop(firstProgress + 1)
            .takeWhile { it < transfer.firstExitIndex }
            .mapNotNull { index ->
                val stop = firstPattern.stops[index]
                val walkMetres = distance(userLatitude, userLongitude, stop)
                val walkSeconds = walkMetres / WALKING_METRES_PER_SECOND
                val busSeconds = busSecondsToStop(
                    firstVehicle, firstPattern, firstProgress, index
                )
                if (walkSeconds + BOARDING_MARGIN_SECONDS > busSeconds) null
                else BoardingCandidate(index, stop, walkMetres, walkSeconds, busSeconds)
            }
            .minByOrNull { maxOf(it.walkSeconds, it.busSeconds) } ?: return null
        val firstBoardIndex = firstBoarding.index
        val secondBoard = secondPattern.stops[transfer.secondBoardIndex]
        val firstRideSeconds = routeDistance(
            firstPattern, firstBoardIndex, transfer.firstExitIndex
        ) / BUS_METRES_PER_SECOND
        val secondRideSeconds = routeDistance(
            secondPattern, transfer.secondBoardIndex, destination.first
        ) / BUS_METRES_PER_SECOND
        val totalSeconds = maxOf(firstBoarding.walkSeconds, firstBoarding.busSeconds) +
            firstRideSeconds +
            (transfer.firstExitIndex - firstBoardIndex) * DWELL_SECONDS_PER_STOP +
            transfer.walkMetres / WALKING_METRES_PER_SECOND +
            ESTIMATED_TRANSFER_WAIT_SECONDS +
            secondRideSeconds +
            (destination.first - transfer.secondBoardIndex) * DWELL_SECONDS_PER_STOP +
            destination.third / WALKING_METRES_PER_SECOND

        return JourneyChoice(
            pattern = firstPattern,
            vehicle = firstVehicle,
            boardAt = firstBoarding.stop,
            boardStopIndex = firstBoardIndex,
            destination = destination.second,
            vehicleDistanceMetres = distance(
                userLatitude,
                userLongitude,
                TransitStop("", "", firstVehicle.latitude, firstVehicle.longitude)
            ),
            walkingMetres = firstBoarding.walkMetres,
            stopCount = transfer.firstExitIndex - firstBoardIndex,
            destinationWalkMetres = destination.third,
            finalLatitude = destinationLatitude,
            finalLongitude = destinationLongitude,
            boardingEtaSeconds = firstBoarding.busSeconds,
            estimatedTotalSeconds = totalSeconds,
            secondLeg = JourneyLeg(
                pattern = secondPattern,
                vehicle = secondVehicle,
                boardAt = secondBoard,
                exitAt = destination.second,
                stopCount = destination.first - transfer.secondBoardIndex
            ),
            transferAt = firstPattern.stops[transfer.firstExitIndex],
            transferWalkMetres = transfer.walkMetres
        )
    }

    private fun journeyScore(choice: JourneyChoice): Float {
        if (choice.estimatedTotalSeconds > 0f) return choice.estimatedTotalSeconds
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

    private data class TransferCandidate(
        val firstExitIndex: Int,
        val secondBoardIndex: Int,
        val walkMetres: Float
    )

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
            intermediateStops * DWELL_SECONDS_PER_STOP
    }

    private fun routeDistance(pattern: TransitPattern, fromIndex: Int, toIndex: Int): Float {
        if (toIndex <= fromIndex) return 0f
        return (fromIndex until toIndex).sumOf { index ->
            distanceBetween(pattern.stops[index], pattern.stops[index + 1]).toDouble()
        }.toFloat()
    }

    fun matchDirection(vehicle: LiveVehicle): TransitPattern? {
        exactTripPattern(vehicle)?.let { return it }

        val candidates = patterns.filter { it.routeId == vehicle.routeId }
        return candidates.minByOrNull { pattern ->
            pattern.stops.zipWithNext().minOfOrNull { (from, to) ->
                segmentScore(vehicle, from, to)
            } ?: Double.MAX_VALUE
        }
    }

    fun isBoardingStopAhead(
        vehicle: LiveVehicle,
        pattern: TransitPattern,
        stopId: String
    ): Boolean {
        val progress = progressIndex(vehicle, pattern) ?: return false
        // The stop is catchable only if this exact trip still has an occurrence
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

    private fun progressIndex(vehicle: LiveVehicle, pattern: TransitPattern): Int? {
        val rawProgress = pattern.stops.zipWithNext()
            .withIndex()
            .minByOrNull { (_, segment) ->
                segmentScore(vehicle, segment.first, segment.second)
            }
            ?.index
            ?: return null
        val prior = vehicleProgress[vehicle.id]
        val sameTrip = prior != null && prior.tripId == vehicle.tripId &&
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
        to: TransitStop
    ): Double {
        val segmentDistance = distanceToSegment(
            vehicle.latitude, vehicle.longitude, from, to
        )
        val bearingPenalty = vehicle.bearing?.let {
            angleDifference(it.toDouble(), bearing(from, to)) * 5.0
        } ?: 0.0
        return segmentDistance + bearingPenalty
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
        private const val WALKING_METRES_PER_SECOND = 1.35f
        private const val BUS_METRES_PER_SECOND = 6.0f
        private const val DWELL_SECONDS_PER_STOP = 18f
        private const val BOARDING_MARGIN_SECONDS = 30f
        private const val ESTIMATED_TRANSFER_WAIT_SECONDS = 300f
        private const val MAX_BOARDING_HEADING_CHECK_METRES = 2_000f
        private const val MAX_FORWARD_BEARING_DIFFERENCE = 100.0
    }
}
