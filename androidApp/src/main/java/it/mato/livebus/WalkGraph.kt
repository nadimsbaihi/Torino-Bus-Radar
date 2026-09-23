package it.mato.livebus

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.util.PriorityQueue
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

    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        for (node in latitudes.indices) {
            cells.getOrPut(cellKey(cell(latitudes[node]), cell(longitudes[node]))) {
                ArrayList()
            }.add(node)
        }
    }

    fun distancesFrom(latitude: Double, longitude: Double, maxMetres: Float = MAX_ROUTE_METRES): Distances {
        val start = nearestNode(latitude, longitude)
            ?: return Distances(latitude, longitude,
                FloatArray(latitudes.size) { Float.POSITIVE_INFINITY }, null, -1)
        val distances = FloatArray(latitudes.size) { Float.POSITIVE_INFINITY }
        val previous = IntArray(latitudes.size) { -1 }
        val queue = PriorityQueue(compareBy<QueueEntry> { it.metres })
        distances[start.first] = start.second
        queue.add(QueueEntry(start.first, start.second))
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
                    queue.add(QueueEntry(next, metres))
                }
            }
        }
        return Distances(latitude, longitude, distances, previous, start.first)
    }

    /** Search a single short transfer without retaining a city-wide distance array. */
    fun shortWalkMetres(fromLatitude: Double, fromLongitude: Double,
                        toLatitude: Double, toLongitude: Double): Float? =
        shortWalk(fromLatitude, fromLongitude, toLatitude, toLongitude, false)?.metres

    fun shortWalkRoute(fromLatitude: Double, fromLongitude: Double,
                       toLatitude: Double, toLongitude: Double): Route? =
        shortWalk(fromLatitude, fromLongitude, toLatitude, toLongitude, true)

    private fun shortWalk(fromLatitude: Double, fromLongitude: Double,
                          toLatitude: Double, toLongitude: Double, includePoints: Boolean): Route? {
        if (fromLatitude == toLatitude && fromLongitude == toLongitude) {
            return Route(0f, if (includePoints) listOf(Point(fromLatitude, fromLongitude)) else emptyList())
        }
        val start = nearestNode(fromLatitude, fromLongitude) ?: return null
        val end = nearestNode(toLatitude, toLongitude) ?: return null
        val best = HashMap<Int, Float>()
        val previous = if (includePoints) HashMap<Int, Int>() else null
        val queue = PriorityQueue(compareBy<QueueEntry> { it.metres })
        best[start.first] = start.second
        queue.add(QueueEntry(start.first, start.second))
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current.metres > (best[current.node] ?: Float.POSITIVE_INFINITY)) continue
            if (current.metres + end.second > MAX_TRANSFER_METRES) break
            if (current.node == end.first) {
                val points = if (includePoints) {
                    pathPoints(start.first, end.first) { previous?.get(it) }
                        ?.let { listOf(Point(fromLatitude, fromLongitude)) + it +
                            Point(toLatitude, toLongitude) } ?: return null
                } else emptyList()
                return Route(current.metres + end.second, points)
            }
            for (edge in offsets[current.node] until offsets[current.node + 1]) {
                val next = neighbours[edge]
                val metres = current.metres + edgeMetres[edge]
                if (metres < (best[next] ?: Float.POSITIVE_INFINITY) &&
                    metres + end.second <= MAX_TRANSFER_METRES) {
                    best[next] = metres
                    previous?.set(next, current.node)
                    queue.add(QueueEntry(next, metres))
                }
            }
        }
        return null
    }

    inner class Distances internal constructor(
        private val latitude: Double,
        private val longitude: Double,
        private val distances: FloatArray,
        private val previous: IntArray?,
        private val startNode: Int
    ) {
        fun metresTo(targetLatitude: Double, targetLongitude: Double): Float? {
            if (latitude == targetLatitude && longitude == targetLongitude) return 0f
            val target = nearestNode(targetLatitude, targetLongitude) ?: return null
            val route = distances[target.first]
            return if (route.isFinite()) route + target.second else null
        }

        fun estimatedMetresTo(targetLatitude: Double, targetLongitude: Double): Float =
            metresTo(targetLatitude, targetLongitude)
                ?: approximateMetres(latitude, longitude, targetLatitude, targetLongitude) * 1.2f

        fun routeTo(targetLatitude: Double, targetLongitude: Double): Route? {
            val metres = metresTo(targetLatitude, targetLongitude) ?: return null
            if (latitude == targetLatitude && longitude == targetLongitude) {
                return Route(0f, listOf(Point(latitude, longitude)))
            }
            val target = nearestNode(targetLatitude, targetLongitude) ?: return null
            val parents = previous ?: return null
            val nodes = pathPoints(startNode, target.first) { node ->
                parents[node].takeIf { it >= 0 }
            } ?: return null
            return Route(metres, listOf(Point(latitude, longitude)) + nodes +
                Point(targetLatitude, targetLongitude))
        }
    }

    private fun pathPoints(start: Int, end: Int, previous: (Int) -> Int?): List<Point>? {
        val reversed = ArrayList<Point>()
        var node = end
        while (true) {
            reversed.add(Point(latitudes[node], longitudes[node]))
            if (node == start) break
            node = previous(node) ?: return null
        }
        reversed.reverse()
        return reversed
    }

    private fun nearestNode(latitude: Double, longitude: Double): Pair<Int, Float>? {
        val x = cell(latitude)
        val y = cell(longitude)
        var closest = -1
        var best = MAX_SNAP_METRES
        for (dx in -2..2) for (dy in -2..2) {
            for (node in cells[cellKey(x + dx, y + dy)].orEmpty()) {
                val metres = approximateMetres(latitude, longitude, latitudes[node], longitudes[node])
                if (metres < best) {
                    closest = node
                    best = metres
                }
            }
        }
        return if (closest >= 0) closest to best else null
    }

    private data class QueueEntry(val node: Int, val metres: Float)

    companion object {
        private const val CELL_DEGREES = 0.001
        private const val MAX_SNAP_METRES = 100f
        private const val MAX_ROUTE_METRES = 12_000f
        private const val MAX_TRANSFER_METRES = 500f

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
            val north = (lat2 - lat1) * 111_195.0
            val east = (lon2 - lon1) * 111_195.0 * cos(Math.toRadians((lat1 + lat2) / 2))
            return hypot(north, east).toFloat()
        }
    }
}
