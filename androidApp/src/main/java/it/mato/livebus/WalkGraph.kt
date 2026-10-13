package it.mato.livebus

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/** Pedestrian paths built from the same OpenStreetMap extract as the offline map. */
class WalkGraph private constructor(
    private val latitudes: DoubleArray,
    private val longitudes: DoubleArray,
    private val offsets: IntArray,
    private val neighbours: IntArray,
    private val edgeMetres: FloatArray
) {
    data class Point(val latitude: Double, val longitude: Double)
    data class Route(val metres: Float, val points: List<Point>)

    /**
     * Where a point meets the street network: on the street segment between [lowNode] and
     * [highNode], [alongMetres] from [lowNode] and [offsetMetres] to the side. Either end is
     * a way in, at the cost of walking along the segment to it.
     */
    internal class Snap(
        val lowNode: Int,
        val highNode: Int,
        val lengthMetres: Float,
        val alongMetres: Float,
        val offsetMetres: Float
    ) {
        val lowCost get() = offsetMetres + alongMetres
        val highCost get() = offsetMetres + (lengthMetres - alongMetres)

        fun costTo(node: Int) = if (node == lowNode) lowCost else highCost

        /** Walking directly along one segment, when both points snapped to it. */
        fun directTo(other: Snap): Float? =
            if (lowNode == other.lowNode && highNode == other.highNode)
                offsetMetres + other.offsetMetres + abs(alongMetres - other.alongMetres)
            else null
    }

    private val cells = HashMap<Long, MutableList<Int>>()

    // Stray footpath fragments cut off from the city are no use as a start or finish.
    private val connected = mainComponent()

    init {
        for (node in latitudes.indices) {
            if (!connected[node]) continue
            cells.getOrPut(cellKey(cell(latitudes[node]), cell(longitudes[node]))) {
                ArrayList()
            }.add(node)
        }
    }

    fun distancesFrom(latitude: Double, longitude: Double, maxMetres: Float = MAX_ROUTE_METRES): Distances {
        val distances = FloatArray(latitudes.size) { Float.POSITIVE_INFINITY }
        val start = snap(latitude, longitude)
            ?: return Distances(latitude, longitude, distances, null, null)
        val previous = IntArray(latitudes.size) { -1 }
        val queue = PriorityQueue(QUEUE_ORDER)
        for (node in intArrayOf(start.lowNode, start.highNode)) {
            val metres = start.costTo(node)
            if (metres < distances[node]) {
                distances[node] = metres
                queue.add(QueueEntry(node, metres, metres))
            }
        }
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current.metres > distances[current.node]) continue
            if (current.metres > maxMetres) break
            for (edge in offsets[current.node] until offsets[current.node + 1]) {
                val next = neighbours[edge]
                val metres = current.metres + edgeMetres[edge]
                if (metres < distances[next] && metres <= maxMetres) {
                    distances[next] = metres
                    previous[next] = current.node
                    queue.add(QueueEntry(next, metres, metres))
                }
            }
        }
        return Distances(latitude, longitude, distances, previous, start)
    }

    /** Search a single short transfer without retaining a city-wide distance array. */
    fun shortWalkMetres(fromLatitude: Double, fromLongitude: Double,
                        toLatitude: Double, toLongitude: Double): Float? =
        shortWalk(fromLatitude, fromLongitude, toLatitude, toLongitude, false)?.metres

    fun shortWalkRoute(fromLatitude: Double, fromLongitude: Double,
                       toLatitude: Double, toLongitude: Double): Route? =
        shortWalk(fromLatitude, fromLongitude, toLatitude, toLongitude, true)

    /** A* from one point to another, bounded to a short walk. */
    private fun shortWalk(fromLatitude: Double, fromLongitude: Double,
                          toLatitude: Double, toLongitude: Double, includePoints: Boolean): Route? {
        if (fromLatitude == toLatitude && fromLongitude == toLongitude) {
            return Route(0f, if (includePoints) listOf(Point(fromLatitude, fromLongitude)) else emptyList())
        }
        val straight = approximateMetres(fromLatitude, fromLongitude, toLatitude, toLongitude)
        if (straight <= DIRECT_WALK_METRES) {
            return Route(straight, if (includePoints) listOf(Point(fromLatitude, fromLongitude),
                Point(toLatitude, toLongitude)) else emptyList())
        }
        val start = snap(fromLatitude, fromLongitude) ?: return null
        val end = snap(toLatitude, toLongitude) ?: return null
        var bestMetres = MAX_TRANSFER_METRES
        var bestEnd = -1
        var found = false
        start.directTo(end)?.let { along ->
            if (along <= bestMetres) {
                bestMetres = along
                found = true
            }
        }

        val best = HashMap<Int, Float>()
        val previous = if (includePoints) HashMap<Int, Int>() else null
        val queue = PriorityQueue(QUEUE_ORDER)
        fun heuristic(node: Int) =
            approximateMetres(latitudes[node], longitudes[node], toLatitude, toLongitude)
        for (node in intArrayOf(start.lowNode, start.highNode)) {
            val metres = start.costTo(node)
            if (metres < (best[node] ?: Float.POSITIVE_INFINITY)) {
                best[node] = metres
                queue.add(QueueEntry(node, metres, metres + heuristic(node)))
            }
        }
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current.key >= bestMetres) break
            if (current.metres > (best[current.node] ?: Float.POSITIVE_INFINITY)) continue
            if (current.node == end.lowNode || current.node == end.highNode) {
                val total = current.metres + end.costTo(current.node)
                if (total < bestMetres) {
                    bestMetres = total
                    bestEnd = current.node
                    found = true
                }
            }
            for (edge in offsets[current.node] until offsets[current.node + 1]) {
                val next = neighbours[edge]
                val metres = current.metres + edgeMetres[edge]
                val key = metres + heuristic(next)
                if (metres < (best[next] ?: Float.POSITIVE_INFINITY) && key < bestMetres) {
                    best[next] = metres
                    previous?.set(next, current.node)
                    queue.add(QueueEntry(next, metres, key))
                }
            }
        }
        if (!found) return null
        val points = if (!includePoints) emptyList() else if (bestEnd < 0) {
            listOf(Point(fromLatitude, fromLongitude), Point(toLatitude, toLongitude))
        } else {
            listOf(Point(fromLatitude, fromLongitude)) +
                pathPoints(bestEnd) { previous?.get(it) } + Point(toLatitude, toLongitude)
        }
        return Route(bestMetres, points)
    }

    inner class Distances internal constructor(
        private val latitude: Double,
        private val longitude: Double,
        private val distances: FloatArray,
        private val previous: IntArray?,
        private val start: Snap?
    ) {
        private fun reach(target: Snap): Pair<Float, Int>? {
            var metres = Float.POSITIVE_INFINITY
            var node = -1
            for (candidate in intArrayOf(target.lowNode, target.highNode)) {
                val total = distances[candidate] + target.costTo(candidate)
                if (total < metres) {
                    metres = total
                    node = candidate
                }
            }
            val direct = start?.directTo(target)
            if (direct != null && direct <= metres) return direct to -1
            return if (metres.isFinite()) metres to node else null
        }

        fun metresTo(targetLatitude: Double, targetLongitude: Double): Float? {
            if (latitude == targetLatitude && longitude == targetLongitude) return 0f
            val straight = approximateMetres(latitude, longitude, targetLatitude, targetLongitude)
            if (straight <= DIRECT_WALK_METRES) return straight
            val target = snap(targetLatitude, targetLongitude) ?: return null
            return reach(target)?.first
        }

        fun estimatedMetresTo(targetLatitude: Double, targetLongitude: Double): Float =
            metresTo(targetLatitude, targetLongitude)
                ?: approximateMetres(latitude, longitude, targetLatitude, targetLongitude) * 1.2f

        fun routeTo(targetLatitude: Double, targetLongitude: Double): Route? {
            if (latitude == targetLatitude && longitude == targetLongitude) {
                return Route(0f, listOf(Point(latitude, longitude)))
            }
            val straight = approximateMetres(latitude, longitude, targetLatitude, targetLongitude)
            if (straight <= DIRECT_WALK_METRES) {
                return Route(straight, listOf(Point(latitude, longitude),
                    Point(targetLatitude, targetLongitude)))
            }
            val target = snap(targetLatitude, targetLongitude) ?: return null
            val (metres, endNode) = reach(target) ?: return null
            if (endNode < 0) {
                return Route(metres, listOf(Point(latitude, longitude),
                    Point(targetLatitude, targetLongitude)))
            }
            val parents = previous ?: return null
            val nodes = pathPoints(endNode) { node -> parents[node].takeIf { it >= 0 } }
            return Route(metres, listOf(Point(latitude, longitude)) + nodes +
                Point(targetLatitude, targetLongitude))
        }
    }

    /** Nodes from the search's start to [end]; the start is where the parent chain stops. */
    private fun pathPoints(end: Int, previous: (Int) -> Int?): List<Point> {
        val reversed = ArrayList<Point>()
        var node: Int? = end
        while (node != null) {
            reversed.add(Point(latitudes[node], longitudes[node]))
            node = previous(node)
        }
        reversed.reverse()
        return reversed
    }

    /** Nearest point on any street segment within reach, or null when none is close enough. */
    private fun snap(latitude: Double, longitude: Double): Snap? {
        val x = cell(latitude)
        val y = cell(longitude)
        val metresPerLongitude = METRES_PER_DEGREE * cos(Math.toRadians(latitude))
        var best: Snap? = null
        var bestOffset = MAX_SNAP_METRES
        for (dx in -2..2) for (dy in -2..2) {
            for (node in cells[cellKey(x + dx, y + dy)].orEmpty()) {
                val ax = (longitudes[node] - longitude) * metresPerLongitude
                val ay = (latitudes[node] - latitude) * METRES_PER_DEGREE
                for (edge in offsets[node] until offsets[node + 1]) {
                    val other = neighbours[edge]
                    val bx = (longitudes[other] - longitude) * metresPerLongitude - ax
                    val by = (latitudes[other] - latitude) * METRES_PER_DEGREE - ay
                    val lengthSquared = bx * bx + by * by
                    val t = if (lengthSquared == 0.0) 0.0
                        else (-(ax * bx + ay * by) / lengthSquared).coerceIn(0.0, 1.0)
                    val offset = hypot(ax + t * bx, ay + t * by).toFloat()
                    if (offset < bestOffset) {
                        bestOffset = offset
                        val length = edgeMetres[edge]
                        val fromNode = (t * length).toFloat()
                        // Keep one orientation per segment so two snaps to it can be compared.
                        best = if (node < other) Snap(node, other, length, fromNode, offset)
                        else Snap(other, node, length, length - fromNode, offset)
                    }
                }
            }
        }
        return best
    }

    /** Marks the nodes of the large connected network, ignoring stray fragments. */
    private fun mainComponent(): BooleanArray {
        val component = IntArray(latitudes.size) { -1 }
        val sizes = ArrayList<Int>()
        val stack = IntArray(latitudes.size)
        for (seed in latitudes.indices) {
            if (component[seed] >= 0) continue
            val id = sizes.size
            var size = 0
            var top = 0
            stack[top++] = seed
            component[seed] = id
            while (top > 0) {
                val node = stack[--top]
                size++
                for (edge in offsets[node] until offsets[node + 1]) {
                    val next = neighbours[edge]
                    if (component[next] < 0) {
                        component[next] = id
                        stack[top++] = next
                    }
                }
            }
            sizes.add(size)
        }
        val largest = sizes.maxOrNull() ?: 0
        return BooleanArray(latitudes.size) { sizes[component[it]].toLong() * MAIN_COMPONENT_RATIO >= largest }
    }

    private class QueueEntry(val node: Int, val metres: Float, val key: Float)

    companion object {
        private const val CELL_DEGREES = 0.001
        private const val MAX_SNAP_METRES = 100f
        private const val MAX_ROUTE_METRES = 12_000f
        private const val MAX_TRANSFER_METRES = 500f
        // Points this close are crossed directly (a forecourt, a stop's two sides), not via a street.
        private const val DIRECT_WALK_METRES = 30f
        private const val METRES_PER_DEGREE = 111_195.0
        // A network at least this fraction of the biggest one counts as part of the city.
        private const val MAIN_COMPONENT_RATIO = 20
        private val QUEUE_ORDER = Comparator<QueueEntry> { a, b -> java.lang.Float.compare(a.key, b.key) }

        fun load(file: File): WalkGraph = DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val magic = ByteArray(8)
            input.readFully(magic)
            require(magic.contentEquals("MATOWLK1".toByteArray(Charsets.US_ASCII))) {
                "Unsupported walking graph"
            }
            val nodeCount = input.readInt()
            val edgeCount = input.readInt()
            val latitudes = DoubleArray(nodeCount)
            val longitudes = DoubleArray(nodeCount)
            for (node in 0 until nodeCount) {
                latitudes[node] = input.readDouble()
                longitudes[node] = input.readDouble()
            }
            val offsets = IntArray(nodeCount + 1) { input.readInt() }
            val neighbours = IntArray(edgeCount)
            val edgeMetres = FloatArray(edgeCount)
            for (edge in 0 until edgeCount) {
                neighbours[edge] = input.readInt()
                edgeMetres[edge] = input.readFloat()
            }
            require(offsets.last() == edgeCount) { "Invalid walking graph" }
            WalkGraph(latitudes, longitudes, offsets, neighbours, edgeMetres)
        }

        private fun cell(value: Double) = floor(value / CELL_DEGREES).toInt()
        private fun cellKey(latitudeCell: Int, longitudeCell: Int) =
            (latitudeCell.toLong() shl 32) or (longitudeCell.toLong() and 0xffffffffL)

        private fun approximateMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val north = (lat2 - lat1) * METRES_PER_DEGREE
            val east = (lon2 - lon1) * METRES_PER_DEGREE * cos(Math.toRadians((lat1 + lat2) / 2))
            return hypot(north, east).toFloat()
        }
    }
}
