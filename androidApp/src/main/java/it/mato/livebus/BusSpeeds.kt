package it.mato.livebus

import java.time.Instant
import java.time.ZoneId
import kotlin.math.pow

/** Parts of the service day in which buses run at visibly different speeds. */
enum class SpeedBucket {
    NIGHT, MORNING_PEAK, MIDDAY, EVENING_PEAK, EVENING;

    companion object {
        private val ROME = ZoneId.of("Europe/Rome")

        fun at(epochSeconds: Long): SpeedBucket {
            val time = Instant.ofEpochSecond(epochSeconds).atZone(ROME)
            return atMinute(time.hour * 60 + time.minute)
        }

        /** [minuteOfDay] counted from local midnight. */
        fun atMinute(minuteOfDay: Int): SpeedBucket = when {
            minuteOfDay < 7 * 60 || minuteOfDay >= 22 * 60 -> NIGHT
            minuteOfDay < 9 * 60 + 30 -> MORNING_PEAK
            minuteOfDay < 16 * 60 + 30 -> MIDDAY
            minuteOfDay < 19 * 60 + 30 -> EVENING_PEAK
            else -> EVENING
        }
    }
}

/**
 * How fast buses really move along their lines, learned from the live feed.
 *
 * A speed here is the pace between stops, with the time spent standing at stops taken out (the
 * planner adds that separately), in metres of road per second. Every route and part of the day keeps
 * a robust running mean of the samples it has seen. Few samples are not trusted on their own: a
 * route's estimate is pulled towards the city-wide estimate for that part of the day, which in turn
 * is pulled towards [DEFAULT_METRES_PER_SECOND]. Old evidence fades with a half-life of two weeks.
 */
class SpeedEstimator {
    private class Stat(var mean: Double, var weight: Double, var day: Long)

    private val stats = HashMap<String, Stat>()

    @get:Synchronized
    val entryCount: Int get() = stats.size

    /** Bumped on every sample, so a caller can tell whether there is anything new to save. */
    @get:Synchronized
    var revision = 0
        private set

    /** The speed to plan with for a route at a given time. */
    @Synchronized
    fun speed(routeId: String, bucket: SpeedBucket, epochDay: Long): Float {
        val city = shrunk(CITY, bucket, epochDay, DEFAULT_METRES_PER_SECOND.toDouble(), CITY_PRIOR_WEIGHT)
        return shrunk(routeId, bucket, epochDay, city, ROUTE_PRIOR_WEIGHT).toFloat()
    }

    /** How many samples back the estimate for a route, after fading; 0 when it runs on the priors. */
    @Synchronized
    fun evidence(routeId: String, bucket: SpeedBucket, epochDay: Long): Double =
        stats[key(routeId, bucket)]?.let { effectiveWeight(it, epochDay) } ?: 0.0

    /** Adds one measured speed. Samples outside what a bus can do are dropped as measurement errors. */
    @Synchronized
    fun observe(routeId: String, bucket: SpeedBucket, epochDay: Long, metresPerSecond: Double) {
        if (!metresPerSecond.isFinite() || metresPerSecond < MIN_SAMPLE || metresPerSecond > MAX_SAMPLE) return
        add(key(routeId, bucket), epochDay, metresPerSecond)
        add(key(CITY, bucket), epochDay, metresPerSecond)
        revision++
    }

    private fun add(key: String, epochDay: Long, sample: Double) {
        val stat = stats[key]
        if (stat == null) {
            stats[key] = Stat(sample, 1.0, epochDay)
            return
        }
        val weight = effectiveWeight(stat, epochDay)
        // Once there is a track record, one wild sample (a bus held at lights, a GPS jump) is
        // clipped towards what is normal rather than allowed to drag the mean.
        val clipped = if (weight >= WINSOR_AFTER) sample.coerceIn(stat.mean * 0.5, stat.mean * 1.8) else sample
        stat.mean += (clipped - stat.mean) * maxOf(1.0 / (weight + 1.0), MIN_STEP)
        stat.weight = minOf(weight + 1.0, MAX_WEIGHT)
        stat.day = epochDay
    }

    private fun shrunk(id: String, bucket: SpeedBucket, epochDay: Long, prior: Double, priorWeight: Double): Double {
        val stat = stats[key(id, bucket)] ?: return prior
        val weight = effectiveWeight(stat, epochDay)
        return (stat.mean * weight + prior * priorWeight) / (weight + priorWeight)
    }

    private fun effectiveWeight(stat: Stat, epochDay: Long): Double =
        stat.weight * 0.5.pow((epochDay - stat.day).coerceAtLeast(0) / HALF_LIFE_DAYS)

    private fun key(id: String, bucket: SpeedBucket) = "$id\t${bucket.ordinal}"

    /** One line per entry; bounded to [MAX_ENTRIES] by dropping the entries with least evidence. */
    @Synchronized
    fun serialize(epochDay: Long): String {
        val kept = stats.entries
            .map { it to effectiveWeight(it.value, epochDay) }
            .filter { it.second >= PRUNE_BELOW }
            .sortedByDescending { it.second }
            .take(MAX_ENTRIES)
        return buildString {
            append(FORMAT).append('\n')
            for ((entry, _) in kept) {
                val stat = entry.value
                append(entry.key).append('\t').append("%.2f".format(java.util.Locale.ROOT, stat.mean))
                    .append('\t').append("%.1f".format(java.util.Locale.ROOT, stat.weight))
                    .append('\t').append(stat.day).append('\n')
            }
        }
    }

    /** Replaces the contents with [text] from [serialize]; anything unreadable is skipped. */
    @Synchronized
    fun restore(text: String) {
        stats.clear()
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next() != FORMAT) return
        while (lines.hasNext() && stats.size < MAX_ENTRIES) {
            val parts = lines.next().split('\t')
            if (parts.size != 5) continue
            val bucket = parts[1].toIntOrNull()
            val mean = parts[2].toDoubleOrNull()
            val weight = parts[3].toDoubleOrNull()
            val day = parts[4].toLongOrNull()
            if (bucket == null || bucket !in SpeedBucket.values().indices || mean == null ||
                weight == null || day == null || !mean.isFinite() || !weight.isFinite() ||
                mean < MIN_SAMPLE || mean > MAX_SAMPLE || weight <= 0.0) continue
            stats["${parts[0]}\t$bucket"] = Stat(mean, minOf(weight, MAX_WEIGHT), day)
        }
        revision++
    }

    companion object {
        /**
         * Metres of road per second between stops, standing time excluded. Torino buses average
         * 15 to 18 km/h door to door once stops are counted; 5.5 m/s (20 km/h) moving, plus the
         * planner's 18 s per stop on roughly 400 m of road between stops, lands at about 16 km/h.
         * The old 6 m/s measured against straight lines plus 15% for the road was close, but
         * assumed more than the city's traffic gives.
         */
        const val DEFAULT_METRES_PER_SECOND = 5.5f
        private const val CITY = "*"
        private const val FORMAT = "bus-speeds-1"
        // Samples a route needs before it counts as much as the city-wide figure.
        private const val ROUTE_PRIOR_WEIGHT = 12.0
        // Samples a part of the day needs before the city figure counts as much as the default.
        private const val CITY_PRIOR_WEIGHT = 25.0
        private const val HALF_LIFE_DAYS = 14.0
        private const val MIN_STEP = 0.03
        private const val MAX_WEIGHT = 300.0
        private const val WINSOR_AFTER = 8.0
        private const val MIN_SAMPLE = 1.0
        private const val MAX_SAMPLE = 16.0
        private const val PRUNE_BELOW = 0.5
        const val MAX_ENTRIES = 1_500
    }
}

/** Turns two consecutive fixes of one vehicle on one line into a speed sample, or rejects them. */
object SpeedSampler {
    /** Where a vehicle was on its line at one fix. */
    class Fix(
        val timestampSeconds: Long,
        val patternId: String,
        // Metres along the line from its first stop.
        val alongMetres: Double,
        // Stops of the line the vehicle was past at this fix.
        val stopsPassed: Int,
        // How far the vehicle was from the line itself.
        val offLineMetres: Double
    )

    /**
     * The moving speed between [previous] and [current] in metres per second, or null when the pair
     * says nothing about driving: the same fix repeated, a long gap, a different line or trip,
     * a bus off its road, a bus standing (at a stop, a terminus, or in a jam), going backwards, or a
     * jump no bus could make. Time at the stops the bus passed is taken out ([dwellSeconds] each).
     */
    fun sample(previous: Fix, current: Fix, dwellSeconds: Float): Double? {
        val seconds = current.timestampSeconds - previous.timestampSeconds
        if (seconds < MIN_INTERVAL_SECONDS || seconds > MAX_INTERVAL_SECONDS) return null
        if (previous.patternId != current.patternId) return null
        if (previous.offLineMetres > MAX_OFF_LINE_METRES || current.offLineMetres > MAX_OFF_LINE_METRES) return null
        val metres = current.alongMetres - previous.alongMetres
        // GPS jitter on a stationary bus, or a bus that has not really moved.
        if (metres < MIN_PROGRESS_METRES) return null
        val moving = seconds - (current.stopsPassed - previous.stopsPassed).coerceAtLeast(0) * dwellSeconds
        // Most of the interval was spent standing: the pace of the rest is not measurable.
        if (moving < seconds * MIN_MOVING_SHARE || moving < MIN_INTERVAL_SECONDS) return null
        val speed = metres / moving
        return speed.takeIf { it in 1.0..MAX_SPEED }
    }

    private const val MIN_INTERVAL_SECONDS = 8
    private const val MAX_INTERVAL_SECONDS = 180
    private const val MAX_OFF_LINE_METRES = 60.0
    private const val MIN_PROGRESS_METRES = 25.0
    private const val MIN_MOVING_SHARE = 0.5f
    private const val MAX_SPEED = 16.0
}
