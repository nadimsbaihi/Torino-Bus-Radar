package it.mato.livebus

/**
 * When a rider has to set off for a bus, and which bus to set off for. Kept free of Android so
 * the reminder's decisions can be tested on their own.
 */
internal object DepartureTiming {
    const val WALK_METRES_PER_SECOND = 1.2f
    // Closer than this the rider is at the stop already: there is nothing to set off for.
    const val AT_STOP_METRES = 60f
    // A reminder that would go off sooner than this is no reminder: the rider just has to go.
    const val MIN_LEAD_SECONDS = 120f

    /** One bus of the line on its way to the rider's stop. */
    class Arrival(val vehicleId: String, val etaSeconds: Float)

    fun walkSeconds(walkMetres: Float) =
        if (walkMetres < AT_STOP_METRES) 0f else walkMetres / WALK_METRES_PER_SECOND

    /** Seconds from now until the rider has to set off to be at the stop [spareMinutes] before the bus. */
    fun leaveInSeconds(busEtaSeconds: Float, walkMetres: Float, spareMinutes: Int) =
        busEtaSeconds - walkSeconds(walkMetres) - spareMinutes * 60f

    /** Whether a reminder makes sense: there is still a while to wait before setting off. */
    fun worthReminding(busEtaSeconds: Float, walkMetres: Float, spareMinutes: Int) =
        leaveInSeconds(busEtaSeconds, walkMetres, spareMinutes) >= MIN_LEAD_SECONDS

    /**
     * The bus to set off for: [preferredId] (the one planned, or already chosen) while it leaves the
     * rider the spare time they asked for, otherwise the soonest that does. With none that does,
     * the soonest the rider can still reach at all, so they are told to go at once.
     */
    fun chooseBus(
        arrivals: List<Arrival>,
        preferredId: String?,
        walkMetres: Float,
        spareMinutes: Int
    ): Arrival? {
        val sorted = arrivals.sortedBy { it.etaSeconds }
        fun fits(arrival: Arrival) = leaveInSeconds(arrival.etaSeconds, walkMetres, spareMinutes) >= 0f
        sorted.firstOrNull { it.vehicleId == preferredId }?.takeIf(::fits)?.let { return it }
        return sorted.firstOrNull(::fits)
            ?: sorted.firstOrNull { it.etaSeconds >= walkSeconds(walkMetres) }
    }
}

/**
 * Whether a rider got on the bus that just left their stop. One GPS fix can be off by enough to
 * mistake one case for the other, so it takes [VOTES_NEEDED] fixes that agree.
 */
internal class BoardingCheck {
    enum class Verdict { ON_BOARD, MISSED, UNSURE }

    private var onBoard = 0
    private var missed = 0
    private var checks = 0

    /**
     * One more look: the rider's distance to the stop and to the bus, or nulls without a fix.
     * After [MAX_CHECKS] looks that settle nothing the bus counts as missed, which at worst tells
     * a rider who is on it about the next one.
     */
    fun record(toStopMetres: Float?, toBusMetres: Float?): Verdict {
        checks++
        if (toStopMetres != null && toBusMetres != null) {
            when {
                // Away from the stop and nearer the bus than the stop: riding it.
                toStopMetres > DepartureTiming.AT_STOP_METRES && toBusMetres < toStopMetres -> onBoard++
                // Clearly nearer the stop than the bus: left behind.
                toBusMetres > toStopMetres + LEFT_BEHIND_MARGIN_METRES -> missed++
            }
        }
        return when {
            onBoard >= VOTES_NEEDED -> Verdict.ON_BOARD
            missed >= VOTES_NEEDED -> Verdict.MISSED
            checks >= MAX_CHECKS -> Verdict.MISSED
            else -> Verdict.UNSURE
        }
    }

    companion object {
        const val VOTES_NEEDED = 2
        const val MAX_CHECKS = 6
        private const val LEFT_BEHIND_MARGIN_METRES = 50f
    }
}
