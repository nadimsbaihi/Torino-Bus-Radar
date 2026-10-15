package it.mato.livebus

import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The road line a pattern follows, in travel order. Positions along it are measured in metres
 * from the start ("chainage"), which keeps a route that crosses or doubles back on itself unambiguous.
 */
class RouteShape(val latitudes: DoubleArray, val longitudes: DoubleArray) {
    init {
        require(latitudes.size == longitudes.size && latitudes.size >= 2) { "A shape needs two points" }
    }

    /** A point on the shape, and how far (metres) the query point was from it. */
    class Fix(val chainage: Double, val distance: Double)

    /** Where each stop of a pattern sits along the shape, and the worst stop-to-shape gap. */
    class Alignment(val chainages: DoubleArray, val worstMetres: Double)

    // Flat-earth maths is exact enough at city scale.
    private val scale = cos(Math.toRadians(latitudes.average()))
    private val xs = DoubleArray(latitudes.size) { longitudes[it] * scale * METRES_PER_DEGREE }
    private val ys = DoubleArray(latitudes.size) { latitudes[it] * METRES_PER_DEGREE }
    private val chain = DoubleArray(latitudes.size).also { chain ->
        for (index in 1 until chain.size) {
            chain[index] = chain[index - 1] + hypot(xs[index] - xs[index - 1], ys[index] - ys[index - 1])
        }
    }
    val lengthMetres: Double get() = chain.last()

    /** The nearest point to (lat, lon) between two chainages, or null if the range is empty. */
    fun nearest(latitude: Double, longitude: Double, from: Double, to: Double): Fix? {
        if (to < from) return null
        var best: Fix? = null
        for (segment in 0 until latitudes.size - 1) {
            if (chain[segment + 1] < from || chain[segment] > to) continue
            val fix = project(latitude, longitude, segment, from, to)
            if (best == null || fix.distance < best.distance) best = fix
        }
        return best
    }

    /**
     * Pins every stop to the shape, in order. Each stop could be near several parts of a looping
     * route, so pick the combination with the smallest total gap that never runs backwards.
     */
    fun align(stops: List<TransitStop>): Alignment? {
        if (stops.isEmpty()) return null
        val options = stops.map { candidates(it.latitude, it.longitude) }
        var previous = DoubleArray(options[0].size) { options[0][it].distance }
        val from = ArrayList<IntArray>(stops.size)
        from.add(IntArray(options[0].size))
        for (stop in 1 until stops.size) {
            val costs = DoubleArray(options[stop].size)
            val back = IntArray(options[stop].size)
            for ((index, here) in options[stop].withIndex()) {
                var bestCost = Double.POSITIVE_INFINITY
                var bestBack = 0
                for ((other, there) in options[stop - 1].withIndex()) {
                    // A stop may project marginally behind its predecessor (same pole, GPS noise).
                    val cost = previous[other] + (if (there.chainage <= here.chainage + BACKWARD_SLACK_METRES) 0.0
                        else OUT_OF_ORDER_PENALTY) + (here.chainage - there.chainage).coerceAtLeast(0.0) * TIGHTNESS
                    if (cost < bestCost) {
                        bestCost = cost
                        bestBack = other
                    }
                }
                costs[index] = bestCost + here.distance
                back[index] = bestBack
            }
            previous = costs
            from.add(back)
        }
        var pick = previous.indices.minByOrNull { previous[it] } ?: return null
        val chainages = DoubleArray(stops.size)
        var worst = 0.0
        for (stop in stops.indices.reversed()) {
            chainages[stop] = options[stop][pick].chainage
            worst = maxOf(worst, options[stop][pick].distance)
            pick = from[stop][pick]
        }
        for (stop in 1 until chainages.size) chainages[stop] = maxOf(chainages[stop], chainages[stop - 1])
        return Alignment(chainages, worst)
    }

    /** Which way the road runs at [chainage], in compass degrees (0 north, 90 east). */
    fun bearingAt(chainage: Double): Float {
        var segment = 0
        while (segment < chain.size - 2 && chain[segment + 1] <= chainage) segment++
        // A repeated point has no direction; look past it.
        while (segment < chain.size - 2 && chain[segment + 1] == chain[segment]) segment++
        val degrees = Math.toDegrees(kotlin.math.atan2(xs[segment + 1] - xs[segment], ys[segment + 1] - ys[segment]))
        return ((degrees + 360.0) % 360.0).toFloat()
    }

    /** The shape between two chainages, with interpolated ends. */
    fun slice(from: Double, to: Double): List<GeoPoint> {
        val start = from.coerceIn(0.0, lengthMetres)
        val end = to.coerceIn(start, lengthMetres)
        val points = ArrayList<GeoPoint>()
        points.add(pointAt(start))
        for (index in 1 until chain.size - 1) {
            if (chain[index] > start && chain[index] < end) {
                points.add(GeoPoint(latitudes[index], longitudes[index]))
            }
        }
        points.add(pointAt(end))
        return points
    }

    private fun pointAt(chainage: Double): GeoPoint {
        var segment = 0
        while (segment < chain.size - 2 && chain[segment + 1] < chainage) segment++
        val length = chain[segment + 1] - chain[segment]
        val t = if (length == 0.0) 0.0 else ((chainage - chain[segment]) / length).coerceIn(0.0, 1.0)
        return GeoPoint(
            latitudes[segment] + (latitudes[segment + 1] - latitudes[segment]) * t,
            longitudes[segment] + (longitudes[segment + 1] - longitudes[segment]) * t
        )
    }

    /** The few nearest places on the shape for a point, each on a different stretch of the line. */
    private fun candidates(latitude: Double, longitude: Double): List<Fix> {
        val all = (0 until latitudes.size - 1).map { project(latitude, longitude, it, 0.0, lengthMetres) }
        val nearest = all.minOf { it.distance }
        val kept = ArrayList<Fix>()
        for (fix in all.filter { it.distance <= nearest + CANDIDATE_MARGIN_METRES }.sortedBy { it.distance }) {
            if (kept.none { kotlin.math.abs(it.chainage - fix.chainage) < DISTINCT_STRETCH_METRES }) kept.add(fix)
            if (kept.size == MAX_CANDIDATES) break
        }
        return kept
    }

    private fun project(latitude: Double, longitude: Double, segment: Int, from: Double, to: Double): Fix {
        val px = longitude * scale * METRES_PER_DEGREE
        val py = latitude * METRES_PER_DEGREE
        val dx = xs[segment + 1] - xs[segment]
        val dy = ys[segment + 1] - ys[segment]
        val lengthSquared = dx * dx + dy * dy
        val along = if (lengthSquared == 0.0) 0.0
            else (((px - xs[segment]) * dx + (py - ys[segment]) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val chainage = (chain[segment] + along * hypot(dx, dy)).coerceIn(from, to)
        // Measure to the point actually returned, so a clamped range stays honest.
        val t = if (lengthSquared == 0.0) 0.0 else (chainage - chain[segment]) / hypot(dx, dy)
        return Fix(chainage, hypot(px - (xs[segment] + t * dx), py - (ys[segment] + t * dy)))
    }

    companion object {
        private const val METRES_PER_DEGREE = 111_195.0
        private const val BACKWARD_SLACK_METRES = 15.0
        private const val OUT_OF_ORDER_PENALTY = 500.0
        // Prefers the tighter fit when two alignments are otherwise as close to the stops.
        private const val TIGHTNESS = 0.001
        private const val CANDIDATE_MARGIN_METRES = 30.0
        private const val DISTINCT_STRETCH_METRES = 25.0
        private const val MAX_CANDIDATES = 8

        /** Decodes Google's polyline format at five decimals, as written by tools/build_transit_index.py. */
        fun decode(encoded: String): RouteShape? {
            val latitudes = ArrayList<Double>()
            val longitudes = ArrayList<Double>()
            var index = 0
            var latitude = 0
            var longitude = 0
            fun next(): Int? {
                var result = 0
                var shift = 0
                while (true) {
                    if (index >= encoded.length) return null
                    val chunk = encoded[index++].code - 63
                    result = result or ((chunk and 0x1F) shl shift)
                    shift += 5
                    if (chunk < 0x20) break
                }
                return if (result and 1 != 0) (result shr 1).inv() else result shr 1
            }
            while (index < encoded.length) {
                latitude += next() ?: return null
                longitude += next() ?: return null
                latitudes.add(latitude / 1e5)
                longitudes.add(longitude / 1e5)
            }
            return if (latitudes.size < 2) null
            else RouteShape(latitudes.toDoubleArray(), longitudes.toDoubleArray())
        }
    }
}
