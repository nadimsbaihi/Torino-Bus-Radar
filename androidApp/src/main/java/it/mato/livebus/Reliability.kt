package it.mato.livebus

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How likely a rider is to make a boarding or a change, given how much time they have to spare
 * and how well anyone can know when the bus will be there.
 *
 * The time of a bus arrival is treated as normally distributed around the estimate. The spread
 * grows with how far ahead the arrival is (traffic has longer to interfere) and with the age of
 * the position the estimate rests on (the bus has been moving unseen); a bus that is not yet on
 * its run adds more. The walker's own pace adds a little. A missed connection costs a wait for
 * the next bus, which is what [penaltySeconds] charges in proportion to the chance of missing.
 */
object Reliability {
    // What nobody can predict about a bus a few seconds away: a light, a door that sticks.
    private const val BASE_SECONDS = 20f
    // Share of the time still to wait that the estimate may be out by (one standard deviation).
    private const val HORIZON_SHARE = 0.10f
    // A position this many seconds old has been drifting; each second of age adds this much doubt.
    private const val AGE_SECONDS_PER_SECOND = 0.5f
    // Walking pace is a guess too: one standard deviation as a share of the walk.
    private const val WALK_SHARE = 0.10f
    // About what waiting for the following bus costs when one is missed.
    private const val MISS_COST_SECONDS = 420f
    // A bus that has to finish one run and start another is less certain than one already on its way.
    const val TURNAROUND_EXTRA_SECONDS = 60f

    /** Standard deviation, in seconds, of when a bus [etaSeconds] away (from a [ageSeconds]-old fix) arrives. */
    fun arrivalSigma(etaSeconds: Float, ageSeconds: Float, extraSeconds: Float = 0f): Float {
        val horizon = HORIZON_SHARE * etaSeconds.coerceAtLeast(0f)
        val age = AGE_SECONDS_PER_SECOND * ageSeconds.coerceAtLeast(0f)
        return sqrt(BASE_SECONDS * BASE_SECONDS + horizon * horizon + age * age + extraSeconds * extraSeconds)
    }

    /** Spread of the walk of [walkSeconds]. */
    fun walkSigma(walkSeconds: Float) = WALK_SHARE * walkSeconds.coerceAtLeast(0f)

    /** Chance that something with [marginSeconds] to spare and a [sigmaSeconds] uncertainty is made. */
    fun chance(marginSeconds: Float, sigmaSeconds: Float): Float {
        if (sigmaSeconds <= 0f) return if (marginSeconds >= 0f) 1f else 0f
        return normalCdf(marginSeconds / sigmaSeconds)
    }

    /** Combines independent uncertainties. */
    fun combine(vararg sigmas: Float): Float = sqrt(sigmas.sumOf { (it * it).toDouble() }.toFloat())

    /** Seconds to add to a journey's score for the chance [chance] of making a connection. */
    fun penaltySeconds(chance: Float): Float = (1f - chance.coerceIn(0f, 1f)) * MISS_COST_SECONDS

    // Abramowitz and Stegun 7.1.26; good to about 1.5e-7, far beyond what the inputs deserve.
    private fun normalCdf(z: Float): Float {
        val x = abs(z) / sqrt(2f)
        val t = 1f / (1f + 0.3275911f * x)
        val poly = ((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f
        val erf = 1f - poly * t * exp(-x * x)
        return if (z >= 0f) 0.5f * (1f + erf) else 0.5f * (1f - erf)
    }
}
