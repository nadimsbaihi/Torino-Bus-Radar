package it.mato.livebus

import android.content.Context
import android.location.Location
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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
    val stops: List<TransitStop>,
    // The road line the buses follow; null for an index without geometry.
    val shape: RouteShape? = null,
    // What the same bus usually does after this pattern; null when the timetable does not say.
    val next: PatternSuccessor? = null,
    // Trips of this pattern that are the last of a bus's duty: the depot comes after them.
    val lastTripIds: Set<String> = emptySet()
)

/**
 * The pattern a bus runs after finishing another one (typically the line the other way, from the
 * same terminus), the usual wait before it, and the trips of the first pattern that do continue.
 */
class PatternSuccessor(
    val patternId: String,
    val waitSeconds: Int,
    val tripIds: Set<String>
)

/** A bus still on its previous run, planned as the next one: it reaches the start in [departsInSeconds]. */
data class Turnaround(
    val previousPatternId: String,
    val departsInSeconds: Float,
    val plannedAtMillis: Long = System.currentTimeMillis()
) {
    /** Whether the bus has had its time (plus [graceMillis]) to start the run it was planned for. */
    fun overdue(nowMillis: Long, graceMillis: Long) =
        nowMillis > plannedAtMillis + (departsInSeconds * 1000).toLong() + graceMillis
}

/** What a highlighted bus has driven and has yet to drive, split at the bus. */
class BusCourse(
    val passed: List<GeoPoint>,
    val ahead: List<GeoPoint>,
    val passedStops: List<TransitStop>,
    val aheadStops: List<TransitStop>
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
    val transferWalkMetres: Float = 0f,
    val turnaround: Turnaround? = null,
    // Chance (0 to 1) of getting to the boarding stop in time, and of making the change.
    val boardingChance: Float = 1f,
    val transferChance: Float = 1f,
    val backup: BackupBus? = null
) {
    val totalWalkingMetres: Float get() = walkingMetres + transferWalkMetres + destinationWalkMetres
    // A bus about to turn around still carries the trip it is finishing, not this one.
    val directionEstimated: Boolean get() =
        (turnaround == null && vehicle.tripId !in pattern.tripIds) ||
            secondLeg?.let { !it.turnaround && it.vehicle.tripId !in it.pattern.tripIds } == true
}

data class JourneyLeg(
    val pattern: TransitPattern,
    val vehicle: LiveVehicle,
    val boardAt: TransitStop,
    val exitAt: TransitStop,
    val stopCount: Int,
    val turnaround: Boolean = false
)

/** A live vehicle matched to a pattern, with its arrival time at every stop still ahead of it. */
class TrackedVehicle internal constructor(
    val vehicle: LiveVehicle,
    val pattern: TransitPattern,
    val progress: Int,
    // Seconds from now until the vehicle reaches stop i; only meaningful for i > progress.
    internal val arrivalSeconds: FloatArray,
    // Set when this is the run the bus will start after the one it is on (progress is then -1).
    val turnaround: Turnaround? = null,
    // Metres of road per second this vehicle's line is expected to run at, between stops.
    val speed: Float = SpeedEstimator.DEFAULT_METRES_PER_SECOND,
    // How old the position the estimates start from was, in seconds.
    val positionAgeSeconds: Float = 0f
) {
    /** How uncertain an arrival [etaSeconds] from now is, as a standard deviation in seconds. */
    fun arrivalSigma(etaSeconds: Float) = Reliability.arrivalSigma(etaSeconds, positionAgeSeconds,
        if (turnaround != null) Reliability.TURNAROUND_EXTRA_SECONDS else 0f)
}

/** The next bus that would do the same job, for a rider who misses the one planned. */
data class BackupBus(val route: String, val vehicle: LiveVehicle, val etaSeconds: Float)

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
    private val segmentBearings by lazy {
        patterns.associate { pattern ->
            pattern.id to DoubleArray((pattern.stops.size - 1).coerceAtLeast(0)) { index ->
                bearing(pattern.stops[index], pattern.stops[index + 1])
            }
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
    /** What buses have been seen doing; the planner's speed for a line comes from here. */
    var speeds = SpeedEstimator()
    // The last fix of each vehicle on its trip, to measure how far it got between two feeds.
    private val speedFixes = HashMap<String, SpeedSampler.Fix>()
    /** Stops within [MAX_TRANSFER_WALK] (straight line) of a stop, with the distance, the stop itself included. */
    internal fun transferNeighbours(stopId: String): List<Pair<String, Float>> =
        transferStops[stopId].orEmpty()

    private val vehicleProgress = mutableMapOf<String, ObservedProgress>()
    private val vehicleMotion = mutableMapOf<String, VehicleMotion>()
    // One fix is matched many times per refresh (map, planner, alerts); remember the answer.
    private val matchCache = HashMap<String, CachedMatch>()
    private val progressCache = HashMap<String, CachedProgress>()

    val destinationNames: List<String> = patterns
        .flatMap { it.stops }
        .map { cleanStopName(it.name) }
        .distinct()
        .sorted()

    fun patternsForRoute(route: String) = patterns.filter { it.route == route }

    private val routeNames by lazy { patterns.associate { it.routeId to it.route } }

    private val dutyEndTrips by lazy { patterns.flatMapTo(HashSet()) { it.lastTripIds } }
    // The last scheduled trip each bus was seen on, and the buses that finished their duty on it.
    private val lastTripOf = HashMap<String, String>()
    private val finishedVehicles = HashSet<String>()

    /**
     * The vehicles that carry passengers or are about to. A bus heading for the depot, or out of
     * it, still reports its line, so left in it would be drawn and planned as a bus on that line.
     *
     * Only a vehicle the feed gives no trip at all can be dropped, and for one of two reasons: it
     * finished the last trip of its duty (the index says which they are) and has not been given
     * another since, or it is nowhere near the line it names. A bus that is merely between two
     * trips, waiting at its terminus, stays. Call it with every feed: it remembers what each bus
     * was last seen doing.
     */
    @Synchronized
    fun inService(vehicles: List<LiveVehicle>): List<LiveVehicle> = vehicles.filter { vehicle ->
        val tripId = vehicle.tripId
        if (tripId != null) {
            finishedVehicles.remove(vehicle.id)
            if (tripId in patternsByTripId) lastTripOf[vehicle.id] = tripId
            return@filter true
        }
        if (vehicle.id in finishedVehicles) return@filter false
        if (hasFinishedItsDuty(vehicle)) {
            finishedVehicles.add(vehicle.id)
            return@filter false
        }
        !isOffItsLine(vehicle)
    }

    /**
     * Whether the bundled timetable looks out of date: GTT publishes new trip IDs with each GTFS
     * release, so a feed full of trips the index has never heard of means the app needs updating.
     * A handful of strangers (extra runs) is normal; too few buses says nothing either way.
     */
    fun looksOutOfDate(vehicles: List<LiveVehicle>): Boolean {
        val withTrips = vehicles.filter { it.tripId != null }
        if (withTrips.size < STALE_MIN_VEHICLES) return false
        val known = withTrips.count { it.tripId in patternsByTripId }
        return known < withTrips.size * STALE_MAX_KNOWN_SHARE
    }

    private fun hasFinishedItsDuty(vehicle: LiveVehicle): Boolean {
        val lastTrip = lastTripOf[vehicle.id]?.takeIf { it in dutyEndTrips } ?: return false
        // A trip ID that goes missing halfway along the last trip is the feed stuttering, not the
        // end: only a bus near where that trip finishes has finished.
        val terminus = patternsByTripId[lastTrip]?.stops?.lastOrNull() ?: return false
        return distance(vehicle.latitude, vehicle.longitude, terminus) <= DUTY_END_RADIUS_METRES
    }

    private fun isOffItsLine(vehicle: LiveVehicle): Boolean {
        // A line the index does not know says nothing about where the bus should be.
        val candidates = patternsByRoute[vehicle.routeId] ?: return false
        return candidates.none { metresFromLine(vehicle, it) <= OFF_LINE_METRES }
    }

    private fun metresFromLine(vehicle: LiveVehicle, pattern: TransitPattern): Double {
        val shape = pattern.shape
        if (shape != null) {
            return shape.nearest(vehicle.latitude, vehicle.longitude, 0.0, shape.lengthMetres)?.distance
                ?: Double.MAX_VALUE
        }
        return (0 until pattern.stops.size - 1).minOfOrNull {
            distanceToSegment(vehicle.latitude, vehicle.longitude, pattern.stops[it], pattern.stops[it + 1])
        } ?: Double.MAX_VALUE
    }

    /**
     * The name riders see on a line, straight from the feed: "13" and "13+", "59" and "59/", "10"
     * and "10N" are different lines. Null for a route the index does not know.
     */
    fun routeName(routeId: String): String? = routeNames[routeId.removePrefix("gtt:")]

    /**
     * Matches every vehicle to a pattern and precomputes when it reaches each stop ahead.
     * Planning several journeys on one feed snapshot can share the result.
     */
    suspend fun trackVehicles(
        vehicles: List<LiveVehicle>,
        nowSeconds: Long = System.currentTimeMillis() / 1000
    ): List<TrackedVehicle> {
        val context = currentCoroutineContext()
        val running = vehicles.mapNotNull { vehicle ->
            context.ensureActive()
            val pattern = matchDirection(vehicle) ?: return@mapNotNull null
            val progress = progressIndex(vehicle, pattern) ?: return@mapNotNull null
            val position = routePosition(vehicle, pattern, progress)
            learnSpeed(vehicle, pattern, progress, position)
            val speed = speedOf(pattern, nowSeconds)
            TrackedVehicle(vehicle, pattern, progress,
                arrivalSeconds(vehicle, pattern, progress, nowSeconds, speed, position), speed = speed,
                positionAgeSeconds = vehicle.ageSecondsAt(nowSeconds).toFloat())
        }
        return running + running.mapNotNull { turnaroundOf(it, nowSeconds) }
    }

    /** The speed to plan [pattern]'s buses with at [nowSeconds]. */
    fun speedOf(pattern: TransitPattern, nowSeconds: Long): Float =
        speeds.speed(pattern.routeId, SpeedBucket.at(nowSeconds), Math.floorDiv(nowSeconds, SECONDS_PER_DAY))

    /**
     * Compares where the vehicle is now with where it was at the previous feed, and teaches the
     * speed model what that says about the pace of its line. Only buses on a trip the timetable
     * knows count: for a guessed line the position along it is too uncertain to measure.
     */
    private fun learnSpeed(
        vehicle: LiveVehicle,
        pattern: TransitPattern,
        progress: Int,
        position: RoutePosition
    ) {
        if (exactTripPattern(vehicle)?.id != pattern.id) {
            synchronized(speedFixes) { speedFixes.remove(vehicle.id) }
            return
        }
        val fix = SpeedSampler.Fix(vehicle.timestampSeconds, pattern.id,
            position.alongMetres, progress + 1, position.offLineMetres)
        val previous = synchronized(speedFixes) {
            val old = speedFixes[vehicle.id]
            // The same feed is tracked several times (map, planner, alerts); count each fix once.
            if (old != null && vehicle.timestampSeconds <= old.timestampSeconds) return
            if (speedFixes.size > MAX_SPEED_FIXES) speedFixes.clear()
            speedFixes[vehicle.id] = fix
            old
        } ?: return
        val sample = SpeedSampler.sample(previous, fix, DWELL_SECONDS_PER_STOP) ?: return
        speeds.observe(pattern.routeId, SpeedBucket.at(vehicle.timestampSeconds),
            Math.floorDiv(vehicle.timestampSeconds, SECONDS_PER_DAY), sample)
    }

    /**
     * Where a vehicle is on its line: metres of road from the first stop, how far the vehicle is
     * from the line, and the metres of road still to drive to the next stop. Along the shape when
     * the pattern has a usable one; otherwise along the straight lines between stops, scaled by the
     * usual circuity.
     */
    private class RoutePosition(
        val alongMetres: Double,
        val offLineMetres: Double,
        val toNextStopMetres: Float
    )

    private fun routePosition(vehicle: LiveVehicle, pattern: TransitPattern, progress: Int): RoutePosition {
        val stops = pattern.stops
        val nextIndex = (progress + 1).coerceAtMost(stops.lastIndex)
        val straightToNext = distance(vehicle.latitude, vehicle.longitude, stops[nextIndex])
        val shape = pattern.shape
        val alignment = alignmentOf(pattern)
        if (shape != null && alignment != null) {
            val fix = shape.nearest(vehicle.latitude, vehicle.longitude,
                alignment.chainages[(progress - 1).coerceAtLeast(0)],
                alignment.chainages[(progress + 2).coerceAtMost(stops.lastIndex)])
            if (fix != null && fix.distance <= OFF_ROUTE_METRES) {
                // Never shorter than the crow flies, whatever a shape or its alignment says.
                var toNext = (alignment.chainages[nextIndex] - fix.chainage).toFloat()
                // The bus is on the stretch into the next stop, so it cannot have more to drive than
                // that stretch (as capped for stop-to-stop distances), nor less than the crow flies.
                if (nextIndex > 0) {
                    val road = roadDistances(pattern)
                    toNext = minOf(toNext, road[nextIndex] - road[nextIndex - 1] + SEGMENT_SLACK_METRES)
                }
                toNext = maxOf(toNext, straightToNext)
                return RoutePosition(fix.chainage, fix.distance, toNext)
            }
        }
        // No usable shape, or off it: straight lines between stops.
        val from = stops[progress]
        val to = stops[nextIndex]
        val into = distance(vehicle.latitude, vehicle.longitude, from).coerceAtMost(distanceBetween(from, to))
        val straight = cumulativeDistances.getValue(pattern.id)[progress] + into
        return RoutePosition((straight * ROUTE_CIRCUITY).toDouble(),
            distanceToSegment(vehicle.latitude, vehicle.longitude, from, to), straightToNext * ROUTE_CIRCUITY)
    }

    private val patternsById by lazy { patterns.associateBy { it.id } }

    /**
     * A bus nearing the end of its line will, on most trips, set off again the other way after a
     * short wait. Until then it is not on that run, but it is a bus that is coming, and when
     * nothing else is running that way it may be the only one.
     */
    private fun turnaroundOf(current: TrackedVehicle, nowSeconds: Long): TrackedVehicle? {
        if (current.turnaround != null) return null
        val successor = current.pattern.next ?: return null
        // Only a trip the timetable says continues; the last run of a bus's day goes to the depot.
        if (current.vehicle.tripId !in successor.tripIds) return null
        val next = patternsById[successor.patternId] ?: return null
        val toTerminus = current.arrivalSeconds[current.pattern.stops.lastIndex].coerceAtLeast(0f)
        if (toTerminus > MAX_TURNAROUND_LOOKAHEAD_SECONDS) return null
        val departs = toTerminus + maxOf(successor.waitSeconds, MIN_TURNAROUND_SECONDS) + TURNAROUND_BUFFER_SECONDS
        val speed = speedOf(next, nowSeconds)
        val arrivals = FloatArray(next.stops.size) { index ->
            departs + routeDistance(next, 0, index) / speed + index * DWELL_SECONDS_PER_STOP
        }
        return TrackedVehicle(current.vehicle, next, -1, arrivals,
            Turnaround(current.pattern.id, departs), speed, current.positionAgeSeconds)
    }

    /**
     * The best journey by live buses alone: one bus, or two with a change. This is the
     * [JourneyPlanner] round search restricted to buses, so the rider and the alert service see
     * what the full planner would find without trains.
     */
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
        walkingPreference: WalkingPreference = WalkingPreference.MORE,
        tracked: List<TrackedVehicle>? = null
    ): JourneyChoice? {
        val options = JourneyPlanner(this, null).plan(JourneyQuery(
            latitude, longitude, destinationLatitude, destinationLongitude, vehicles,
            walkFromOrigin, walkFromDestination, walkBetweenStops, walkingPreference,
            earliestStartSeconds = earliestStartSeconds,
            maxDestinationWalkMetres = maxDestinationWalkMetres,
            maxVehicles = if (directOnly) 1 else 2,
            tracked = tracked), onDirectJourney)
        return options.firstOrNull()?.bus
    }

    /**
     * The original search: every vehicle against every pair of stops, then one change between two
     * buses. Kept as the reference the round planner is compared with in the tests, and as a
     * baseline for timing.
     */
    internal suspend fun planLiveJourneyNestedLoops(
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
        walkingPreference: WalkingPreference = WalkingPreference.MORE,
        tracked: List<TrackedVehicle>? = null
    ): JourneyChoice? {
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
        val fleet = tracked ?: trackVehicles(vehicles)

        val initialDistance = walkFromOrigin?.invoke(destinationLatitude, destinationLongitude)
            ?: distance(latitude, longitude,
                TransitStop("", "", destinationLatitude, destinationLongitude))

        // Best (boarding, exit) pair per vehicle, kept online so no candidate list is built.
        var best: JourneyChoice? = null
        var bestScore = Float.POSITIVE_INFINITY
        for (current in fleet) {
            context.ensureActive()
            val vehicle = current.vehicle
            val pattern = current.pattern
            val progressIndex = current.progress
            val arrivals = current.arrivalSeconds
            val speed = current.speed
            var vehicleDistance = -1f
            // A later, reachable exit can still help even if the closest stop
            // to the destination occurs before we can board this vehicle.
            for (destinationIndex in progressIndex + 2 until pattern.stops.size) {
                context.ensureActive()
                val destinationStop = pattern.stops[destinationIndex]
                val remainingWalk = destinationDistance(destinationStop)
                if (remainingWalk >= initialDistance ||
                    remainingWalk > maxDestinationWalkMetres) continue
                var boarding: BoardingCandidate? = null
                var boardingScore = Float.POSITIVE_INFINITY
                for (index in progressIndex + 1 until destinationIndex) {
                    val stop = pattern.stops[index]
                    val walkMetres = walkingDistance(stop)
                    val walkSeconds = walkMetres / WALKING_METRES_PER_SECOND
                    val busSeconds = arrivals[index]
                    if (remainingWalk >= destinationDistance(stop) ||
                        !canCatch(earliestStartSeconds, walkSeconds, busSeconds)) continue
                    val chance = boardingChance(current, earliestStartSeconds, walkSeconds, busSeconds)
                    val score = walkingPreference.score(
                        maxOf(earliestStartSeconds + walkSeconds, busSeconds) +
                            routeDistance(pattern, index, destinationIndex) / speed +
                            (destinationIndex - index) * DWELL_SECONDS_PER_STOP,
                        walkMetres
                    ) + Reliability.penaltySeconds(chance)
                    if (score < boardingScore) {
                        boardingScore = score
                        boarding = BoardingCandidate(index, stop, walkMetres, walkSeconds, busSeconds, chance)
                    }
                }
                if (boarding == null) continue
                val totalSeconds = maxOf(earliestStartSeconds + boarding.walkSeconds, boarding.busSeconds) +
                    routeDistance(pattern, boarding.index, destinationIndex) / speed +
                    (destinationIndex - boarding.index) * DWELL_SECONDS_PER_STOP +
                    remainingWalk / WALKING_METRES_PER_SECOND
                val score = walkingPreference.score(totalSeconds, boarding.walkMetres + remainingWalk) +
                    Reliability.penaltySeconds(boarding.chance)
                if (score >= bestScore) continue
                if (vehicleDistance < 0f) {
                    vehicleDistance = distance(latitude, longitude,
                        TransitStop("", "", vehicle.latitude, vehicle.longitude))
                }
                bestScore = score
                best = JourneyChoice(
                    pattern = pattern,
                    vehicle = vehicle,
                    boardAt = boarding.stop,
                    boardStopIndex = boarding.index,
                    destination = destinationStop,
                    vehicleDistanceMetres = vehicleDistance,
                    walkingMetres = boarding.walkMetres,
                    stopCount = destinationIndex - boarding.index,
                    destinationWalkMetres = remainingWalk,
                    finalLatitude = destinationLatitude,
                    finalLongitude = destinationLongitude,
                    boardingEtaSeconds = boarding.busSeconds,
                    estimatedTotalSeconds = totalSeconds,
                    turnaround = current.turnaround,
                    boardingChance = boarding.chance
                )
            }
        }

        best = best?.let { withBackup(it, fleet, earliestStartSeconds, walkingDistance(it.boardAt)) }
        onDirectJourney?.invoke(best)
        context.ensureActive()
        if (directOnly) return best

        // Index only boarding occurrences that each live vehicle has yet to reach.
        val vehiclesByStop = mutableMapOf<String, MutableList<Pair<TrackedVehicle, Int>>>()
        for (second in fleet) {
            for (index in second.progress + 1 until second.pattern.stops.lastIndex) {
                vehiclesByStop.getOrPut(second.pattern.stops[index].id) { mutableListOf() }
                    .add(second to index)
            }
        }
        // For a second leg boarded at stop b, the exit e > b that minimises ride time plus the
        // walk from e: cost(b, e) = f(e) - g(b), so a suffix argmin over f serves every b.
        val exitsByPattern = HashMap<String, IntArray>()
        for (trackedPattern in fleet.distinctBy { it.pattern.id }) {
            val pattern = trackedPattern.pattern
            val speed = trackedPattern.speed
            val stops = pattern.stops
            val cost = FloatArray(stops.size) { index ->
                val walk = destinationDistance(stops[index])
                if (walk >= initialDistance) Float.POSITIVE_INFINITY
                else routeDistance(pattern, 0, index) / speed +
                    index * DWELL_SECONDS_PER_STOP +
                    walk / WALKING_METRES_PER_SECOND * (1f + walkingPreference.extraWalkingWeight)
            }
            val exits = IntArray(stops.size) { -1 }
            var closest = -1
            for (boardIndex in stops.lastIndex - 1 downTo 0) {
                val candidate = boardIndex + 1
                if (cost[candidate].isFinite() &&
                    (closest < 0 || cost[candidate] <= cost[closest])) closest = candidate
                exits[boardIndex] = closest
            }
            exitsByPattern[pattern.id] = exits
        }
        for (first in fleet) {
            context.ensureActive()
            val pattern = first.pattern
            val firstSpeed = first.speed
            val firstArrivals = first.arrivalSeconds
            val reachable = ArrayList<BoardingCandidate>()
            for (exitIndex in first.progress + 2 until pattern.stops.size) {
                context.ensureActive()
                val boardIndex = exitIndex - 1
                val board = pattern.stops[boardIndex]
                val walk = walkingDistance(board)
                val walkSeconds = walk / WALKING_METRES_PER_SECOND
                val busSeconds = firstArrivals[boardIndex]
                if (canCatch(earliestStartSeconds, walkSeconds, busSeconds)) {
                    reachable.add(BoardingCandidate(boardIndex, board, walk, walkSeconds, busSeconds,
                        boardingChance(first, earliestStartSeconds, walkSeconds, busSeconds)))
                }
                if (reachable.isEmpty()) continue
                // The boarding stop is chosen for this exit, so walk preference applies here too.
                var boarding = reachable[0]
                var firstArrival = Float.POSITIVE_INFINITY
                var boardingScore = Float.POSITIVE_INFINITY
                for (candidate in reachable) {
                    val arrival = maxOf(earliestStartSeconds + candidate.walkSeconds, candidate.busSeconds) +
                        routeDistance(pattern, candidate.index, exitIndex) / firstSpeed +
                        (exitIndex - candidate.index) * DWELL_SECONDS_PER_STOP
                    val score = walkingPreference.score(arrival, candidate.walkMetres) +
                        Reliability.penaltySeconds(candidate.chance)
                    if (score < boardingScore) {
                        boardingScore = score
                        boarding = candidate
                        firstArrival = arrival
                    }
                }
                // Nothing after this point can score better than this: the journey cannot
                // end before the first bus does, nor walk less than the walk to board it.
                if (walkingPreference.score(firstArrival, boarding.walkMetres) +
                    Reliability.penaltySeconds(boarding.chance) >= bestScore) continue
                val exitStop = pattern.stops[exitIndex]
                for ((stopId, transferWalk) in transferStops[exitStop.id].orEmpty()) {
                    for ((second, secondBoardIndex) in vehiclesByStop[stopId].orEmpty()) {
                        context.ensureActive()
                        if (second.pattern.route == pattern.route) continue
                        val secondBoard = second.pattern.stops[secondBoardIndex]
                        val streetTransferWalk = transferWalkingDistances.getOrPut(exitStop.id to stopId) {
                            walkBetweenStops?.invoke(exitStop.latitude, exitStop.longitude,
                                secondBoard.latitude, secondBoard.longitude) ?: transferWalk
                        }
                        if (streetTransferWalk > MAX_TRANSFER_WALK) continue
                        val secondArrival = second.arrivalSeconds[secondBoardIndex]
                        if (!canCatch(firstArrival, streetTransferWalk / WALKING_METRES_PER_SECOND,
                                secondArrival, TRANSFER_MARGIN_SECONDS)) continue
                        val destinationIndex = exitsByPattern.getValue(second.pattern.id)[secondBoardIndex]
                        if (destinationIndex < 0) continue
                        val destination = second.pattern.stops[destinationIndex]
                        val destinationWalk = destinationDistance(destination)
                        if (destinationWalk >= initialDistance ||
                            destinationWalk >= destinationDistance(boarding.stop)) continue
                        val total = secondArrival +
                            routeDistance(second.pattern, secondBoardIndex, destinationIndex) / second.speed +
                            (destinationIndex - secondBoardIndex) * DWELL_SECONDS_PER_STOP +
                            destinationWalk / WALKING_METRES_PER_SECOND
                        val transferSeconds = streetTransferWalk / WALKING_METRES_PER_SECOND
                        val transferChance = Reliability.chance(secondArrival - firstArrival - transferSeconds,
                            Reliability.combine(first.arrivalSigma(firstArrival),
                                second.arrivalSigma(secondArrival), Reliability.walkSigma(transferSeconds)))
                        val score = walkingPreference.score(
                            total, boarding.walkMetres + streetTransferWalk + destinationWalk) +
                            Reliability.penaltySeconds(boarding.chance) +
                            Reliability.penaltySeconds(transferChance)
                        if (score >= bestScore) continue
                        bestScore = score
                        best = JourneyChoice(
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
                                destinationIndex - secondBoardIndex, second.turnaround != null),
                            transferAt = exitStop,
                            transferWalkMetres = streetTransferWalk,
                            turnaround = first.turnaround,
                            boardingChance = boarding.chance,
                            transferChance = transferChance
                        )
                    }
                }
            }
        }
        return best?.let { withBackup(it, fleet, earliestStartSeconds, walkingDistance(it.boardAt)) }
    }

    private fun boardingChance(
        bus: TrackedVehicle,
        freeAtSeconds: Float,
        walkSeconds: Float,
        busSeconds: Float
    ) = Reliability.chance(busSeconds - (freeAtSeconds + walkSeconds),
        Reliability.combine(bus.arrivalSigma(busSeconds), Reliability.walkSigma(walkSeconds)))

    /**
     * Adds the next bus to the journey: the first other vehicle that gets to the same boarding stop
     * (on this line or another that serves both that stop and where the first bus is left) after
     * the planned one, and that the rider could still walk to in time.
     */
    internal fun withBackup(
        choice: JourneyChoice,
        fleet: List<TrackedVehicle>,
        freeAtSeconds: Float,
        walkMetres: Float
    ): JourneyChoice {
        val exit = choice.transferAt ?: choice.destination
        val walkSeconds = walkMetres / WALKING_METRES_PER_SECOND
        var next: BackupBus? = null
        for (other in fleet) {
            if (other.vehicle.id == choice.vehicle.id) continue
            val stops = other.pattern.stops
            val boardIndex = (other.progress + 1 until stops.size).firstOrNull { stops[it].id == choice.boardAt.id }
                ?: continue
            val eta = other.arrivalSeconds[boardIndex]
            if (eta <= choice.boardingEtaSeconds + MIN_BACKUP_GAP_SECONDS ||
                !canCatch(freeAtSeconds, walkSeconds, eta) ||
                (boardIndex + 1 until stops.size).none { stops[it].id == exit.id }) continue
            if (next == null || eta < next.etaSeconds) next = BackupBus(other.pattern.route, other.vehicle, eta)
        }
        return if (next == null) choice else choice.copy(backup = next)
    }

    private data class BoardingCandidate(
        val index: Int,
        val stop: TransitStop,
        val walkMetres: Float,
        val walkSeconds: Float,
        val busSeconds: Float,
        val chance: Float = 1f
    )

    /**
     * Whether someone free from [freeAtSeconds] who walks for [walkSeconds] gets to the stop
     * before the bus. The walk gets some slack (nobody keeps an exact pace); standing at the
     * stop needs none.
     */
    private fun canCatch(
        freeAtSeconds: Float,
        walkSeconds: Float,
        busSeconds: Float,
        marginSeconds: Float = 0f
    ) = freeAtSeconds + walkSeconds * (1f + WALK_SLACK) + marginSeconds <= busSeconds

    private fun arrivalSeconds(
        vehicle: LiveVehicle,
        pattern: TransitPattern,
        progressIndex: Int,
        nowSeconds: Long,
        speed: Float,
        position: RoutePosition
    ): FloatArray {
        val stops = pattern.stops
        val arrivals = FloatArray(stops.size)
        val nextIndex = (progressIndex + 1).coerceAtMost(stops.lastIndex)
        val vehicleToNext = position.toNextStopMetres
        // The position is old: the bus has been driving towards the stop since it was recorded.
        val age = vehicle.ageSecondsAt(nowSeconds)
        for (index in nextIndex until stops.size) {
            arrivals[index] = (vehicleToNext + routeDistance(pattern, nextIndex, index)) /
                speed + (index - nextIndex) * DWELL_SECONDS_PER_STOP - age
        }
        return arrivals
    }

    /**
     * Driving distance between two stops of [pattern]: along its shape when it has one that fits
     * its stops, otherwise the straight lines between stops scaled by the usual circuity.
     */
    private fun routeDistance(pattern: TransitPattern, fromIndex: Int, toIndex: Int): Float {
        if (toIndex <= fromIndex) return 0f
        val distances = roadDistances(pattern)
        return distances[toIndex] - distances[fromIndex]
    }

    // Cumulative road metres to every stop of a pattern, worked out once.
    private val roadCumulative = HashMap<String, FloatArray>()

    @Synchronized
    private fun roadDistances(pattern: TransitPattern): FloatArray = roadCumulative.getOrPut(pattern.id) {
        val straight = cumulativeDistances.getValue(pattern.id)
        val alignment = alignmentOf(pattern)
        FloatArray(straight.size).also { road ->
            for (index in 1 until road.size) {
                val crowFlies = straight[index] - straight[index - 1]
                val segment = if (alignment == null) crowFlies * ROUTE_CIRCUITY else {
                    // Along the shape; a stretch can neither be shorter than the straight line
                    // nor, when the stop pinned to the wrong pass of a looping line, absurdly longer.
                    (alignment.chainages[index] - alignment.chainages[index - 1]).toFloat()
                        .coerceIn(crowFlies, crowFlies * MAX_DETOUR_FACTOR + MAX_DETOUR_SLACK_METRES)
                }
                road[index] = road[index - 1] + segment
            }
        }
    }

    /** Sum of the straight lines between consecutive stops. */
    private fun straightRouteDistance(pattern: TransitPattern, fromIndex: Int, toIndex: Int): Float {
        if (toIndex <= fromIndex) return 0f
        val distances = cumulativeDistances.getValue(pattern.id)
        return distances[toIndex] - distances[fromIndex]
    }

    /**
     * Metres [vehicle] still has to travel along [pattern] to reach the next occurrence of
     * [stopId] ahead of it, or null when the stop is behind it. A vehicle that has left the
     * line (diversion) says nothing about where it is on it, so then it is the straight distance.
     */
    fun routeMetresToStop(vehicle: LiveVehicle, pattern: TransitPattern, stopId: String): Float? {
        val progress = progressIndex(vehicle, pattern) ?: return null
        val nextIndex = progress + 1
        val stopIndex = (nextIndex until pattern.stops.size)
            .firstOrNull { pattern.stops[it].id == stopId } ?: return null
        val segmentStart = pattern.stops[progress]
        val segmentEnd = pattern.stops[nextIndex]
        if (distanceToSegment(vehicle.latitude, vehicle.longitude, segmentStart, segmentEnd) >
            OFF_ROUTE_METRES) return distance(vehicle.latitude, vehicle.longitude, pattern.stops[stopIndex])
        return distance(vehicle.latitude, vehicle.longitude, segmentEnd) +
            straightRouteDistance(pattern, nextIndex, stopIndex)
    }

    fun matchDirection(vehicle: LiveVehicle): TransitPattern? {
        synchronized(this) {
            matchCache[vehicle.id]?.takeIf { it.vehicle == vehicle }?.let { return it.pattern }
        }
        val pattern = inferPattern(vehicle)
        synchronized(this) { matchCache[vehicle.id] = CachedMatch(vehicle, pattern) }
        return pattern
    }

    private fun inferPattern(vehicle: LiveVehicle): TransitPattern? {
        exactTripPattern(vehicle)?.let { return it }

        // GTT often supplies a route and position without a usable scheduled trip ID.
        // Limit inference to nearby segments of that route, using the heading when
        // available and otherwise the direction the vehicle has actually been moving.
        val heading = headingOf(vehicle)
        val candidates = patternsByRoute[vehicle.routeId].orEmpty().mapNotNull { pattern ->
            var score = Double.POSITIVE_INFINITY
            for (index in 0 until pattern.stops.size - 1) {
                score = minOf(score, segmentScore(vehicle, pattern, index, inferred = true, heading = heading))
            }
            if (score.isFinite()) pattern to score else null
        }.sortedBy { it.second }
        val best = candidates.firstOrNull() ?: return null
        // Variants of one direction often share a trunk and tie; stay on the one already
        // matched so the answer does not flip between them as the bus moves.
        val previous = recentInferredPattern(vehicle)
        val sticky = previous?.let { id ->
            candidates.firstOrNull { it.first.id == id && it.second - best.second < AMBIGUOUS_SCORE_MARGIN }
        }
        // Overlapping opposite directions without enough evidence are not a match,
        // unless the vehicle was recently matched to one of them and is still on it.
        val rivals = candidates.filter {
            it.first.directionId != best.first.directionId &&
                it.second - best.second < AMBIGUOUS_SCORE_MARGIN
        }
        if (rivals.isEmpty()) return remember(vehicle, (sticky ?: best).first)
        return sticky?.first?.let { remember(vehicle, it) }
    }

    private fun remember(vehicle: LiveVehicle, pattern: TransitPattern): TransitPattern {
        synchronized(this) { vehicleMotion[vehicle.id]?.inferredPatternId = pattern.id }
        return pattern
    }

    @Synchronized
    private fun recentInferredPattern(vehicle: LiveVehicle): String? {
        val motion = vehicleMotion[vehicle.id] ?: return null
        return motion.inferredPatternId?.takeIf {
            vehicle.timestampSeconds - motion.lastTimestamp <= MOTION_MEMORY_SECONDS
        }
    }

    /**
     * Best available direction of travel: the feed bearing, or the heading derived from
     * the vehicle's last fixes when the feed gives none or contradicts clear movement.
     */
    @Synchronized
    private fun headingOf(vehicle: LiveVehicle): Float? {
        val motion = vehicleMotion.getOrPut(vehicle.id) {
            VehicleMotion(vehicle.latitude, vehicle.longitude, vehicle.timestampSeconds)
        }
        if (vehicle.timestampSeconds > motion.lastTimestamp) {
            val moved = FloatArray(1)
            Location.distanceBetween(
                motion.anchorLatitude, motion.anchorLongitude,
                vehicle.latitude, vehicle.longitude, moved
            )
            if (moved[0] > MAX_PLAUSIBLE_MOVE_METRES ||
                vehicle.timestampSeconds - motion.lastTimestamp > MOTION_MEMORY_SECONDS) {
                // Reused vehicle ID or a long gap: earlier movement says nothing now.
                motion.reset(vehicle)
            } else {
                if (moved[0] >= MIN_MOVE_METRES) {
                    motion.movementHeading = bearing(
                        TransitStop("", "", motion.anchorLatitude, motion.anchorLongitude),
                        TransitStop("", "", vehicle.latitude, vehicle.longitude)
                    ).toFloat()
                    motion.movementDistance = moved[0]
                    motion.movementTimestamp = vehicle.timestampSeconds
                    motion.anchorLatitude = vehicle.latitude
                    motion.anchorLongitude = vehicle.longitude
                }
                motion.lastTimestamp = vehicle.timestampSeconds
            }
        }
        // Movement heading goes stale once the vehicle stops, e.g. before reversing at a terminus.
        val moving = motion.movementHeading?.takeIf {
            vehicle.timestampSeconds - motion.movementTimestamp <= MOVEMENT_FRESH_SECONDS
        }
        val reported = vehicle.bearing
        return when {
            moving == null -> reported?.takeIf { it != UNKNOWN_BEARING }
            reported == null -> moving
            // GTT sends exactly 0 for many buses with no real heading (about 1 in 5 in the live
            // feed, and most of those do not run north), so 0 needs movement to back it up.
            reported == UNKNOWN_BEARING &&
                angleDifference(reported.toDouble(), moving.toDouble()) > MAX_UNKNOWN_BEARING_ERROR -> moving
            motion.movementDistance >= CONTRADICTION_MOVE_METRES &&
                angleDifference(reported.toDouble(), moving.toDouble()) > MAX_FORWARD_BEARING_DIFFERENCE -> moving
            else -> reported
        }
    }

    /**
     * Where a bus should look like it is pointing: its own heading, corrected to the direction of
     * the route segment it is on when the heading is missing or points the wrong way along it.
     */
    fun displayHeading(vehicle: LiveVehicle): Float? {
        val heading = headingOf(vehicle)
        val pattern = matchDirection(vehicle) ?: return heading
        val segment = progressOf(vehicle, pattern) ?: return heading
        val from = pattern.stops[segment]
        val to = pattern.stops[segment + 1]
        if (distanceToSegment(vehicle.latitude, vehicle.longitude, from, to) > MAX_SNAP_METRES) return heading
        val route = bearing(from, to).toFloat()
        return if (heading == null ||
            angleDifference(heading.toDouble(), route.toDouble()) > MAX_FORWARD_BEARING_DIFFERENCE) route
        else heading
    }

    private class VehicleMotion(
        var anchorLatitude: Double,
        var anchorLongitude: Double,
        var lastTimestamp: Long
    ) {
        var movementHeading: Float? = null
        var movementDistance = 0f
        var movementTimestamp = 0L
        var inferredPatternId: String? = null

        fun reset(vehicle: LiveVehicle) {
            anchorLatitude = vehicle.latitude
            anchorLongitude = vehicle.longitude
            lastTimestamp = vehicle.timestampSeconds
            movementHeading = null
            movementDistance = 0f
            movementTimestamp = 0L
            inferredPatternId = null
        }
    }

    // Where each pattern's stops sit along its shape; null when the shape is missing or does not fit.
    private val stopAlignments = HashMap<String, RouteShape.Alignment?>()

    @Synchronized
    private fun alignmentOf(pattern: TransitPattern): RouteShape.Alignment? {
        val shape = pattern.shape ?: return null
        if (pattern.id !in stopAlignments) {
            stopAlignments[pattern.id] =
                shape.align(pattern.stops)?.takeIf { it.worstMetres <= MAX_SHAPE_STOP_GAP_METRES }
        }
        return stopAlignments[pattern.id]
    }

    /**
     * The course of [vehicle] along [pattern], following the roads when the pattern has a shape and
     * joining the stops with straight lines when it does not.
     */
    fun courseOf(vehicle: LiveVehicle, pattern: TransitPattern): BusCourse? {
        if (pattern.stops.size < 2) return null
        // Segment i joins stops i and i + 1; the bus sits somewhere along its segment.
        val segment = progressOf(vehicle, pattern)
        val shape = pattern.shape
        val alignment = alignmentOf(pattern)
        if (shape != null && alignment != null) {
            // Off its line (or position unknown), the bus says nothing about how far along it is.
            fun wholeRoute() = BusCourse(emptyList(), shape.slice(0.0, shape.lengthMetres), emptyList(), pattern.stops)
            if (segment == null) return wholeRoute()
            // The segment comes from straight lines between stops, so on a winding road it can be a
            // stop out. Look one stop either side, but no further: a route that passes the same
            // street twice must not pull the bus onto the wrong pass.
            val last = pattern.stops.size - 1
            val fix = shape.nearest(vehicle.latitude, vehicle.longitude,
                alignment.chainages[(segment - 1).coerceAtLeast(0)], alignment.chainages[(segment + 2).coerceAtMost(last)])
            if (fix != null && fix.distance <= OFF_ROUTE_METRES) {
                // A stop the bus is level with counts as passed.
                val reached = pattern.stops.indices.count { alignment.chainages[it] <= fix.chainage + AT_STOP_METRES }
                return BusCourse(shape.slice(0.0, fix.chainage), shape.slice(fix.chainage, shape.lengthMetres),
                    pattern.stops.subList(0, reached), pattern.stops.subList(reached, pattern.stops.size))
            }
            return wholeRoute()
        }
        val passedStops = segment?.let { pattern.stops.subList(0, it + 1) }.orEmpty()
        val aheadStops = segment?.let { pattern.stops.subList(it + 1, pattern.stops.size) } ?: pattern.stops
        val stops = pattern.stops.map { GeoPoint(it.latitude, it.longitude) }
        val here = GeoPoint(vehicle.latitude, vehicle.longitude)
        return BusCourse(
            passed = segment?.let { stops.subList(0, it + 1) + here }.orEmpty(),
            ahead = segment?.let { listOf(here) + stops.subList(it + 1, stops.size) } ?: stops,
            passedStops = passedStops,
            aheadStops = aheadStops
        )
    }

    /** Index of the segment the vehicle is travelling on within [pattern], or null if it is off the line. */
    fun progressOf(vehicle: LiveVehicle, pattern: TransitPattern): Int? = progressIndex(vehicle, pattern)

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
        progressCache[vehicle.id]?.takeIf {
            it.vehicle == vehicle && it.patternId == pattern.id
        }?.let { return it.progress }
        val progress = computeProgress(vehicle, pattern)
        progressCache[vehicle.id] = CachedProgress(vehicle, pattern.id, progress)
        return progress
    }

    private fun computeProgress(vehicle: LiveVehicle, pattern: TransitPattern): Int? {
        val inferred = exactTripPattern(vehicle) == null
        val heading = headingOf(vehicle)
        val lastSegment = pattern.stops.size - 2
        val prior = vehicleProgress[vehicle.id]
        val sameTrip = !inferred && prior != null && prior.tripId == vehicle.tripId &&
            prior.patternId == pattern.id
        // A bus only moves forward along its trip, but only look a little way ahead: a GPS
        // spike or a route that doubles back must not drag the progress to a far-off segment.
        var progress: Int? = null
        if (sameTrip) {
            val windowed = nearestSegment(vehicle, pattern, prior!!.progress,
                minOf(lastSegment, prior.progress + PROGRESS_WINDOW), inferred, heading)
            if (windowed != null && distanceToSegment(vehicle.latitude, vehicle.longitude,
                    pattern.stops[windowed], pattern.stops[windowed + 1]) <= PROGRESS_RESYNC_METRES) {
                progress = windowed
            }
        }
        // First sighting, or the vehicle is nowhere near where it should be: start afresh.
        if (progress == null) {
            progress = nearestSegment(vehicle, pattern, 0, lastSegment, inferred, heading)
                ?: return null
        }
        vehicleProgress[vehicle.id] = ObservedProgress(vehicle.tripId, pattern.id, progress)
        return progress
    }

    private fun nearestSegment(
        vehicle: LiveVehicle,
        pattern: TransitPattern,
        from: Int,
        to: Int,
        inferred: Boolean,
        heading: Float?
    ): Int? {
        var bestIndex = -1
        var bestScore = Double.POSITIVE_INFINITY
        for (index in from..to) {
            val score = segmentScore(vehicle, pattern, index, inferred, heading)
            if (score < bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex.takeIf { it >= 0 }
    }

    private data class ObservedProgress(
        val tripId: String?,
        val patternId: String,
        val progress: Int
    )

    private class CachedMatch(val vehicle: LiveVehicle, val pattern: TransitPattern?)
    private class CachedProgress(val vehicle: LiveVehicle, val patternId: String, val progress: Int?)

    private fun segmentScore(
        vehicle: LiveVehicle,
        pattern: TransitPattern,
        index: Int,
        inferred: Boolean,
        heading: Float?
    ): Double {
        val segmentDistance = distanceToSegment(
            vehicle.latitude, vehicle.longitude, pattern.stops[index], pattern.stops[index + 1]
        )
        val headingError = heading?.let {
            angleDifference(it.toDouble(), segmentBearings.getValue(pattern.id)[index])
        }
        if (inferred && (segmentDistance > 500.0 ||
                headingError != null && headingError > MAX_FORWARD_BEARING_DIFFERENCE)) return Double.POSITIVE_INFINITY
        return segmentDistance + (headingError ?: 0.0) * 5.0
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
            val encodedShapes = root.optJSONObject("shapes")
            // Patterns often share a shape; decode each one once.
            val shapes = HashMap<String, RouteShape?>()
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
                    },
                    shape = item.optString("shape").takeIf { it.isNotEmpty() }?.let { id ->
                        shapes.getOrPut(id) { encodedShapes?.optString(id)?.let(RouteShape::decode) }
                    },
                    next = item.optJSONObject("next")?.let { next ->
                        PatternSuccessor(
                            patternId = next.getString("id"),
                            waitSeconds = next.getInt("wait"),
                            tripIds = next.optJSONArray("trips")?.let { trips ->
                                (0 until trips.length()).mapTo(HashSet()) { trips.getString(it) }
                            } ?: emptySet()
                        )
                    },
                    lastTripIds = item.optJSONArray("last")?.let { last ->
                        (0 until last.length()).mapTo(HashSet()) { last.getString(it) }
                    } ?: emptySet()
                )
            }
            return TransitIndex(patterns).also { it.speeds = BusSpeedStore.shared(context) }
        }

        fun cleanStopName(value: String) =
            readableCase(value.replace(Regex("""^Fermata\s+\d+\s*-\s*""", RegexOption.IGNORE_CASE), "")
                .trim())

        private val NAME_PARTICLES = setOf("a", "al", "alla", "da", "dal", "dalla", "de", "dei", "degli",
            "del", "della", "delle", "di", "e", "in", "per", "sul", "sulla")
        private val ROMAN_NUMERAL = Regex("^[IVXLC]+$")
        private val HAS_VOWEL = Regex("[AEIOUÀÈÉÌÒÙ]")
        private val WORD_START = Regex("""(^|['’\-/(])(\p{L})""")

        /**
         * GTT writes most stop names in capitals ("CHIESA DELLA SALUTE"); they read better as
         * "Chiesa della Salute". Names already in mixed case are left alone, as are roman numerals
         * ("XX Settembre") and words without vowels, which are abbreviations ("FS").
         */
        internal fun readableCase(name: String): String {
            if (name.any { it.isLowerCase() }) return name
            return name.split(" ").mapIndexed { index, word ->
                when {
                    word.isEmpty() -> word
                    ROMAN_NUMERAL.matches(word) || !HAS_VOWEL.containsMatchIn(word) -> word
                    index > 0 && word.lowercase() in NAME_PARTICLES -> word.lowercase()
                    else -> WORD_START.replace(word.lowercase()) { it.value.uppercase() }
                }
            }.joinToString(" ")
        }

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

        /** Metres from a point to a stop-to-stop segment; flat-earth maths is exact enough at city scale. */
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
            val dx = to.longitude * scale - ax
            val dy = to.latitude - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0 else
                (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            return hypot(px - (ax + t * dx), py - (ay + t * dy)) * METRES_PER_DEGREE
        }

        internal const val MAX_TRANSFER_WALK = 350f
        private const val WALKING_METRES_PER_SECOND = 1.2f
        private const val STALE_MIN_VEHICLES = 10
        private const val STALE_MAX_KNOWN_SHARE = 0.25
        private const val SECONDS_PER_DAY = 86_400L
        // A bus this close behind the planned one is hardly a second chance.
        private const val MIN_BACKUP_GAP_SECONDS = 30f
        private const val SEGMENT_SLACK_METRES = 150f
        private const val MAX_DETOUR_FACTOR = 2.5f
        private const val MAX_DETOUR_SLACK_METRES = 200f
        private const val MAX_SPEED_FIXES = 3_000
        private const val DWELL_SECONDS_PER_STOP = 18f
        private const val WALK_SLACK = 0.15f
        // Getting off one bus and spotting the next takes time even at the same stop.
        private const val TRANSFER_MARGIN_SECONDS = 20f
        // Roads are longer than the straight lines between consecutive stops.
        private const val ROUTE_CIRCUITY = 1.15f
        private const val METRES_PER_DEGREE = 111_195.0
        // How many segments past its last known position a bus may plausibly have advanced.
        private const val PROGRESS_WINDOW = 10
        private const val PROGRESS_RESYNC_METRES = 200.0
        private const val OFF_ROUTE_METRES = 300.0
        private const val AT_STOP_METRES = 10.0
        // A shape whose line misses a stop by more than this is the wrong line for the pattern.
        private const val MAX_SHAPE_STOP_GAP_METRES = 120.0
        // Buses further than this from the end of their line are not yet worth counting on to turn around.
        private const val MAX_TURNAROUND_LOOKAHEAD_SECONDS = 1_200f
        // Passengers off, driver across, doors open: no turnaround is quicker than this.
        private const val MIN_TURNAROUND_SECONDS = 60
        // A scheduled wait is the plan, not a promise; lean towards the later departure.
        private const val TURNAROUND_BUFFER_SECONDS = 90f
        private const val MAX_BOARDING_HEADING_CHECK_METRES = 2_000f
        private const val MAX_FORWARD_BEARING_DIFFERENCE = 100.0
        private const val AMBIGUOUS_SCORE_MARGIN = 75.0
        private const val UNKNOWN_BEARING = 0f
        private const val MAX_UNKNOWN_BEARING_ERROR = 60.0
        // Beyond this the bus is off its line (diversion, depot run) and the route says nothing.
        private const val MAX_SNAP_METRES = 100.0
        // A bus within this of where its duty's last trip ends has finished; further, a missing trip is a glitch.
        private const val DUTY_END_RADIUS_METRES = 1_500.0
        // A bus with no trip this far from every road of its line is on the way to or from the depot.
        private const val OFF_LINE_METRES = 500.0
        // Movement shorter than this is GPS jitter, not a direction of travel.
        private const val MIN_MOVE_METRES = 30f
        // A reported bearing is overruled only by movement clearly longer than jitter.
        private const val CONTRADICTION_MOVE_METRES = 60f
        // No bus covers this between two fixes, so the vehicle ID has been reused.
        private const val MAX_PLAUSIBLE_MOVE_METRES = 3_000f
        private const val MOTION_MEMORY_SECONDS = 300L
        private const val MOVEMENT_FRESH_SECONDS = 90L
    }
}
