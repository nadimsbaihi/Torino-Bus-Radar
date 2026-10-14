package it.mato.livebus

/**
 * One vehicle's run past the stops it has still to reach, as the round planner sees it: a bus
 * tracked live (with the times from [TrackedVehicle]) or a scheduled train. Times are seconds from now.
 */
class PlanTrip(
    // Network stop numbers, in travel order.
    val stops: IntArray,
    // When the vehicle gets to each stop, and when it leaves it (the same array for a bus).
    val arrive: FloatArray,
    val depart: FloatArray,
    val isTrain: Boolean,
    // Vehicles of one line are not changed between; "" never matches.
    val routeKey: String,
    // How far an arrival at a given time may be out, in seconds (standard deviation).
    val sigma: (Float) -> Float,
    // What the caller needs to turn the answer back into its own types.
    val payload: Any
)

/** A walk from one stop to another. */
class Footpath(val to: Int, val metres: Float)

/** Everything a search needs: the stops and runs, how to walk, and what the rider prefers. */
class PlanRequest(
    val stopCount: Int,
    val trips: List<PlanTrip>,
    // Walks from a stop to nearby stops, asked only for the stops a search reaches.
    val footpathsFrom: (Int) -> List<Footpath>,
    // Street metres from the start to each stop, and from each stop to the destination; infinity if none.
    val originWalk: FloatArray,
    val destinationWalk: FloatArray,
    // Walking the whole way, so that rides that do not beat it can be refused.
    val initialDistance: Float,
    // How much more a second of walking counts than a second of travel (WalkingPreference).
    val walkingWeight: Float,
    // Rides per journey, and how many of them may be trains.
    val maxVehicles: Int,
    val maxTrains: Int = 0,
    // Only journeys that include a train count (for asking after a bus-train connection alone).
    val requireTrain: Boolean = false,
    val startSeconds: Float = 0f,
    val maxDestinationWalk: Float = Float.POSITIVE_INFINITY
)

/**
 * A way of being at a stop. Chained back through [from] to the start. [time] is when the rider is
 * there, [boardTime] when ready to board after a walk (with the usual slack on it), [cost] what
 * walking and risky connections have added to the score so far.
 */
class Label internal constructor(
    val stop: Int,
    val time: Float,
    val boardTime: Float,
    val cost: Float,
    val walkMetres: Float,
    // Spread of the time of arrival, seconds; and the length of the walk that came last.
    val sigma: Float,
    val lastWalkSeconds: Float,
    // The vehicle that brought the rider here, or null for the start or a walk.
    val trip: PlanTrip?,
    val boardPos: Int,
    val alightPos: Int,
    val boardChance: Float,
    // Metres of the walk that led to this stop, when it is the start or a walk.
    val footMetres: Float,
    val from: Label?,
    val vehicles: Int,
    val trains: Int,
    // Extra time needed to change from the vehicle that brought the rider.
    internal val margin: Float,
    internal val lastRoute: String,
    internal val lastWasTrain: Boolean,
    // Walk to the destination from the stop where the last bus was boarded fresh; the exit must beat it.
    internal val firstBoardDestination: Float
)

/** Why a journey is on offer next to the best one. */
enum class OptionReason { BEST, FEWEST_CHANGES, FASTEST, LEAST_WALKING }

/** A finished journey: a label at the last stop, and the walk from there to the destination. */
class PlannedJourney internal constructor(
    val last: Label,
    val destinationWalkMetres: Float,
    val totalSeconds: Float,
    val walkingMetres: Float,
    val score: Float
) {
    val vehicles: Int get() = last.vehicles

    /** The rides, first to last, with the walk made before boarding each. */
    fun legs(): List<PlannedLeg> {
        val rides = ArrayList<Label>()
        var label: Label? = last
        while (label != null) {
            if (label.trip != null) rides.add(label)
            label = label.from
        }
        return rides.asReversed().map { ride ->
            val boarding = requireNotNull(ride.from)
            PlannedLeg(requireNotNull(ride.trip), ride.boardPos, ride.alightPos,
                if (boarding.trip == null) boarding.footMetres else 0f, ride.boardChance, ride)
        }
    }

    /** Identity of the journey for removing duplicates: which runs, boarded and left where. */
    fun signature(): String = legs().joinToString("|") {
        "${System.identityHashCode(it.trip)}:${it.boardPos}-${it.alightPos}"
    }
}

class PlannedLeg(
    val trip: PlanTrip,
    val boardPos: Int,
    val alightPos: Int,
    // Metres walked from the start or the previous ride to this boarding stop.
    val walkBeforeMetres: Float,
    val boardChance: Float,
    val arrival: Label
)

class PlannedChoice(val journey: PlannedJourney, val reason: OptionReason)

/**
 * Finds journeys by rounds, the way RAPTOR does: round k holds the best ways to be at each stop having
 * ridden k vehicles. A round scans every run once, boarding wherever a way of being at a stop in the
 * previous round (possibly after a short walk) catches it; then the rider walks on from where they
 * got off, ready for the next round. What is kept at a stop is not one earliest time but the few ways
 * that no other beats in time, readiness to board, and walking and risk cost, so a slower way with
 * less walking is not thrown away.
 *
 * Call [step] once per round (so the caller can show early results between them), then [choose].
 */
class RoundPlanner(private val request: PlanRequest, private val check: () -> Unit = {}) {
    private val candidates = ArrayList<PlannedJourney>()
    private var bestScore = Float.POSITIVE_INFINITY
    private var round = 0
    private var previous: Array<ArrayList<Label>?> = arrayOfNulls(request.stopCount)

    init {
        for (stop in 0 until request.stopCount) {
            val metres = request.originWalk[stop]
            if (!metres.isFinite()) continue
            val seconds = metres / WALK_SPEED
            previous[stop] = arrayListOf(Label(stop, request.startSeconds + seconds,
                request.startSeconds + seconds * (1f + WALK_SLACK), request.walkingWeight * seconds, metres,
                0f, seconds, null, 0, 0, 1f, metres, null, 0, 0, 0f, "", false, Float.POSITIVE_INFINITY))
        }
    }

    val rounds: Int get() = round

    /** The best finished journey so far that satisfies [matching]. */
    fun best(matching: (PlannedJourney) -> Boolean = { true }): PlannedJourney? =
        candidates.filter(matching).minByOrNull { it.score }

    /** Rides one more vehicle; false when [PlanRequest.maxVehicles] is reached. */
    fun step(): Boolean {
        if (round >= request.maxVehicles) return false
        round++
        val arrivals = arrayOfNulls<ArrayList<Label>>(request.stopCount)
        scanTrips(arrivals)
        val next = arrayOfNulls<ArrayList<Label>>(request.stopCount)
        if (round < request.maxVehicles) {
            for (stop in 0 until request.stopCount) {
                check()
                val here = arrivals[stop] ?: continue
                for (label in here) {
                    insert(next, label)
                    // A rider far behind the best journey so far will not catch up by changing.
                    if (label.time + label.cost > bestScore + RELAX_SLACK_SECONDS) continue
                    for (path in request.footpathsFrom(stop)) {
                        val seconds = path.metres / WALK_SPEED
                        insert(next, Label(path.to, label.time + seconds,
                            label.time + seconds * (1f + WALK_SLACK),
                            label.cost + request.walkingWeight * seconds,
                            label.walkMetres + path.metres, label.sigma, seconds, null, 0, 0, 1f,
                            path.metres, label, label.vehicles, label.trains, label.margin, label.lastRoute,
                            label.lastWasTrain, label.firstBoardDestination))
                    }
                }
            }
        }
        previous = next
        return true
    }

    /** A place on a run where the rider could have got on, and what getting on there cost. */
    private class Boarding(
        val label: Label,
        val position: Int,
        val chance: Float,
        val cost: Float,
        // Walk to the destination from here, which later exits of a bus ride must beat.
        val first: Float
    )

    private fun scanTrips(arrivals: Array<ArrayList<Label>?>) {
        for (trip in request.trips) {
            check()
            if (trip.isTrain && request.maxTrains == 0) continue
            // Boardings of this run worth keeping: none costs more and sets a lower bar than another.
            val boardings = ArrayList<Boarding>(2)
            for (position in trip.stops.indices) {
                val stop = trip.stops[position]
                if (boardings.isNotEmpty()) {
                    val time = trip.arrive[position]
                    if (time <= MAX_HORIZON_SECONDS) {
                        for (boarding in boardings) {
                            val from = boarding.label
                            val label = Label(stop, time, time, boarding.cost, from.walkMetres, trip.sigma(time), 0f,
                                trip, boarding.position, position, boarding.chance, 0f, from, round,
                                from.trains + if (trip.isTrain) 1 else 0,
                                if (trip.isTrain) ALIGHTING_SECONDS else TRANSFER_MARGIN_SECONDS,
                                trip.routeKey, trip.isTrain, boarding.first)
                            if (insert(arrivals, label)) finish(label, trip)
                        }
                    }
                }
                val ready = previous[stop] ?: continue
                val departs = trip.depart[position]
                if (departs > MAX_HORIZON_SECONDS) continue
                for (label in ready) {
                    if (label.vehicles != round - 1) continue
                    if (trip.isTrain && label.trains >= request.maxTrains) continue
                    if (trip.routeKey.isNotEmpty() && trip.routeKey == label.lastRoute) continue
                    val required = if (trip.isTrain) label.time + TRAIN_BOARDING_SECONDS
                        else label.boardTime + label.margin
                    if (required > departs) continue
                    val chance = Reliability.chance(
                        departs - label.time - if (trip.isTrain) TRAIN_BOARDING_SECONDS else 0f,
                        Reliability.combine(label.sigma, Reliability.walkSigma(label.lastWalkSeconds),
                            trip.sigma(departs)))
                    // A bus boarded fresh (from the start, or off a train) sets the bar the exit must clear.
                    val first = if (trip.isTrain) label.firstBoardDestination
                        else if (label.vehicles == 0 || label.lastWasTrain) request.destinationWalk[stop]
                        else label.firstBoardDestination
                    keep(boardings, Boarding(label, position, chance,
                        label.cost + Reliability.penaltySeconds(chance), first))
                }
            }
        }
    }

    private fun keep(boardings: ArrayList<Boarding>, candidate: Boarding) {
        for (other in boardings) {
            if (other.cost <= candidate.cost && other.first >= candidate.first &&
                (other.cost < candidate.cost || other.first > candidate.first ||
                    other.label.walkMetres <= candidate.label.walkMetres)) return
        }
        boardings.removeAll { candidate.cost <= it.cost && candidate.first >= it.first }
        boardings.add(candidate)
        if (boardings.size > MAX_BOARDINGS) boardings.remove(boardings.maxByOrNull { it.cost })
    }

    /** Scores [arrival] as the end of a journey, if the rider may get off the vehicle there and walk on. */
    private fun finish(arrival: Label, trip: PlanTrip) {
        // A journey of trains alone is the train planner's business, and not one this one can show.
        if (arrival.vehicles == arrival.trains) return
        if (request.requireTrain && arrival.trains == 0) return
        val walk = request.destinationWalk[arrival.stop]
        if (!walk.isFinite()) return
        if (!trip.isTrain) {
            // A ride must bring the rider usefully closer: not further than walking all the way, nor
            // than the stop it was boarded at.
            if (walk > request.maxDestinationWalk) return
            if (arrival.trains == 0 && walk >= request.initialDistance) return
            if (walk >= arrival.firstBoardDestination) return
        }
        val seconds = walk / WALK_SPEED
        val total = arrival.time + seconds
        val score = total + arrival.cost + request.walkingWeight * seconds
        if (score > bestScore + KEEP_SLACK_SECONDS) return
        candidates.add(PlannedJourney(arrival, walk, total, arrival.walkMetres + walk, score))
        if (score < bestScore) {
            bestScore = score
            if (candidates.size > PURGE_AT) candidates.removeAll { it.score > bestScore + KEEP_SLACK_SECONDS }
        }
    }

    /** Adds [label] to its stop's set unless another beats it; drops those it beats. */
    private fun insert(sets: Array<ArrayList<Label>?>, label: Label): Boolean {
        val list = sets[label.stop] ?: ArrayList<Label>(2).also { sets[label.stop] = it }
        for (other in list) {
            if (beats(other, label)) return false
        }
        list.removeAll { beats(label, it) }
        list.add(label)
        if (list.size > MAX_LABELS_PER_STOP) list.remove(list.maxByOrNull { it.time + it.cost })
        return list.contains(label)
    }

    private fun beats(a: Label, b: Label): Boolean =
        a.lastRoute == b.lastRoute && a.trains == b.trains &&
            a.time <= b.time && a.boardTime + a.margin <= b.boardTime + b.margin && a.cost <= b.cost &&
            a.firstBoardDestination >= b.firstBoardDestination &&
            (a.time < b.time || a.boardTime + a.margin < b.boardTime + b.margin || a.cost < b.cost ||
                a.firstBoardDestination > b.firstBoardDestination || a.walkMetres <= b.walkMetres)

    /** The best journey, and those worth showing beside it, each with the reason it is there. */
    fun choose(): List<PlannedChoice> {
        val best = candidates.minByOrNull { it.score } ?: return emptyList()
        val chosen = LinkedHashMap<String, PlannedChoice>()
        fun offer(journey: PlannedJourney?, reason: OptionReason) {
            if (journey != null) chosen.putIfAbsent(journey.signature(), PlannedChoice(journey, reason))
        }
        offer(best, OptionReason.BEST)
        // The fewest rides that still gets there in reasonable time.
        offer(candidates.filter { it.vehicles < best.vehicles && it.totalSeconds <= best.totalSeconds + EXTRA_TIME_SECONDS }
            .minWithOrNull(compareBy<PlannedJourney> { it.vehicles }.thenBy { it.score }),
            OptionReason.FEWEST_CHANGES)
        // The soonest there, if the best trades time for less walking.
        offer(candidates.minByOrNull { it.totalSeconds }
            ?.takeIf { it.totalSeconds < best.totalSeconds - MIN_TIME_GAIN_SECONDS }, OptionReason.FASTEST)
        // The least walking within a modest extra time.
        offer(candidates.filter { it.totalSeconds <= best.totalSeconds + EXTRA_WALK_TIME_SECONDS &&
            it.walkingMetres <= best.walkingMetres - MIN_WALK_GAIN_METRES }
            .minWithOrNull(compareBy<PlannedJourney> { it.walkingMetres }.thenBy { it.score }),
            OptionReason.LEAST_WALKING)
        return chosen.values.toList()
    }

    companion object {
        const val WALK_SPEED = 1.2f
        const val WALK_SLACK = 0.15f
        const val TRANSFER_MARGIN_SECONDS = 20f
        const val ALIGHTING_SECONDS = 60f
        const val TRAIN_BOARDING_SECONDS = 120f
        private const val MAX_HORIZON_SECONDS = 3 * 3600f
        private const val MAX_LABELS_PER_STOP = 8
        private const val MAX_BOARDINGS = 4
        private const val RELAX_SLACK_SECONDS = 120f
        private const val KEEP_SLACK_SECONDS = 1_800f
        private const val PURGE_AT = 2_000
        private const val EXTRA_TIME_SECONDS = 1_200f
        private const val EXTRA_WALK_TIME_SECONDS = 900f
        private const val MIN_TIME_GAIN_SECONDS = 60f
        private const val MIN_WALK_GAIN_METRES = 150f
    }
}
