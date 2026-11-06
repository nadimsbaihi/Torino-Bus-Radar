package it.mato.livebus

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The live bus positions the MATO website itself shows: MQTT 3.1.1 over a WebSocket. Every bus
 * publishes to "/<line>/<vehicle>" a JSON list [lat, lon, heading, speed, trip, direction, next stop, full]:
 * the direction is the timetable's direction_id and the next stop the number on its sign (stop_code).
 * The connection is opened when buses are first asked for and closed again once nobody asks.
 */
class MatoLiveFeed(private val client: OkHttpClient, private val url: String = URL) {
    private val positions = ConcurrentHashMap<String, LiveVehicle>()
    private val lock = Any()
    private var socket: WebSocket? = null
    private var inbox = ByteArrayOutputStream()
    @Volatile private var lastAskedMillis = 0L
    @Volatile private var lastMessageMillis = 0L
    private val timer by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mato-live").apply { isDaemon = true } } }
    private var keepAlive: java.util.concurrent.ScheduledFuture<*>? = null

    /** True when the connection has just been opened, so the buses are still arriving. */
    fun connect(): Boolean = synchronized(lock) {
        lastAskedMillis = System.currentTimeMillis()
        if (socket != null) return false
        inbox = ByteArrayOutputStream()
        val request = Request.Builder().url(url)
            .header("Sec-WebSocket-Protocol", "mqtt")
            .header("User-Agent", "Torino-Bus-Radar-Android/0.1")
            .build()
        socket = client.newWebSocket(request, Listener())
        keepAlive = timer.scheduleWithFixedDelay(::tick, 30, 30, TimeUnit.SECONDS)
        true
    }

    /** The buses heard from lately; empty before the first ones arrive. */
    fun vehicles(): List<LiveVehicle> {
        lastAskedMillis = System.currentTimeMillis()
        val now = lastAskedMillis / 1000
        positions.values.removeIf { now - it.timestampSeconds > GttRepository.MAX_POSITION_AGE_SECONDS }
        return positions.values.toList()
    }

    fun close() = synchronized(lock) {
        keepAlive?.cancel(false)
        keepAlive = null
        socket?.close(1000, null)
        socket = null
        positions.clear()
    }

    private fun tick() {
        val idle = System.currentTimeMillis() - lastAskedMillis > IDLE_CLOSE_MILLIS
        if (idle) { close(); return }
        // A connection that went quiet is a dead one; drop it and let the next ask open a new one.
        if (lastMessageMillis != 0L && System.currentTimeMillis() - lastMessageMillis > 90_000) {
            Log.w(TAG, "MATO live feed went quiet; reconnecting on next request")
            close(); return
        }
        socket?.send(byteArrayOf(0xC0.toByte(), 0).toByteString())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            lastMessageMillis = System.currentTimeMillis()
            val connect = lengthPrefixed("MQTT".toByteArray()) + byteArrayOf(4, 2, 0, 60) +
                lengthPrefixed("mato-${System.nanoTime().toString(16)}".toByteArray())
            webSocket.send((byteArrayOf(0x10) + remaining(connect.size) + connect).toByteString())
            val subscribe = byteArrayOf(0, 1) + lengthPrefixed("/+/#".toByteArray()) + byteArrayOf(0)
            webSocket.send((byteArrayOf(0x82.toByte()) + remaining(subscribe.size) + subscribe).toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            lastMessageMillis = System.currentTimeMillis()
            synchronized(inbox) {
                inbox.write(bytes.toByteArray())
                drain()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "MATO live feed failed", t)
            forget(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = forget(webSocket)

        private fun forget(webSocket: WebSocket) = synchronized(lock) {
            if (socket === webSocket) { socket = null; keepAlive?.cancel(false); keepAlive = null }
        }
    }

    /** Reads every whole MQTT packet waiting in the inbox; a packet cut short stays for the next message. */
    private fun drain() {
        var data = inbox.toByteArray()
        var at = 0
        while (data.size - at >= 2) {
            var length = 0
            var multiplier = 1
            var i = at + 1
            var complete = false
            while (i < data.size) {
                val b = data[i++].toInt() and 0xFF
                length += (b and 0x7F) * multiplier
                multiplier *= 128
                if (b and 0x80 == 0) { complete = true; break }
            }
            if (!complete || data.size - i < length) break
            if (data[at].toInt() and 0xF0 == 0x30) publish(data[at].toInt(), data, i, length)
            at = i + length
        }
        inbox = ByteArrayOutputStream().also { it.write(data, at, data.size - at) }
    }

    private fun publish(header: Int, data: ByteArray, start: Int, length: Int) {
        val topicLength = ((data[start].toInt() and 0xFF) shl 8) or (data[start + 1].toInt() and 0xFF)
        val topic = String(data, start + 2, topicLength)
        val payloadAt = start + 2 + topicLength + if ((header shr 1) and 3 != 0) 2 else 0
        val payload = String(data, payloadAt, start + length - payloadAt)
        val vehicle = topic.split('/').getOrNull(2)?.takeIf(String::isNotBlank) ?: return
        // An empty message says the bus has left service.
        if (payload.isEmpty()) { positions.remove(vehicleId(vehicle)); return }
        try {
            parse(topic, payload, System.currentTimeMillis() / 1000)?.let { positions[it.id] = it }
        } catch (error: Exception) {
            Log.d(TAG, "Unreadable MATO live message on $topic")
        }
    }

    private fun lengthPrefixed(bytes: ByteArray) = byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes

    private fun remaining(length: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var n = length
        do {
            var digit = n % 128
            n /= 128
            if (n > 0) digit = digit or 0x80
            out.write(digit)
        } while (n > 0)
        return out.toByteArray()
    }

    companion object {
        /**
         * One bus from a message on "/<line>/<vehicle>", or null when it has no position. Lines, trips
         * and vehicles all carry GTT's trailing U, as in GTT's own feeds: a bus is "9633U" in both,
         * so the two feeds and the trip updates agree on which bus is which.
         */
        internal fun parse(topic: String, payload: String, nowSeconds: Long): LiveVehicle? {
            val parts = topic.split('/')  // "", line, vehicle
            if (parts.size < 3 || parts[1].isBlank() || parts[2].isBlank()) return null
            val v = JSONArray(payload)
            if (v.isNull(0) || v.isNull(1)) return null
            val lat = v.getDouble(0)
            val lon = v.getDouble(1)
            if (lat == 0.0 && lon == 0.0) return null
            return LiveVehicle(
                id = vehicleId(parts[2]),
                routeId = parts[1] + "U",
                tripId = v.opt(4)?.takeIf { it != JSONObject_NULL }?.toString()?.takeIf(String::isNotBlank)?.let { it + "U" },
                latitude = lat,
                longitude = lon,
                bearing = if (v.isNull(2)) null else v.getDouble(2).toFloat(),
                timestampSeconds = nowSeconds,
                label = parts[2],
                directionId = v.opt(5).feedText(),
                nextStopCode = v.opt(6).feedText()
            )
        }

        private fun vehicleId(vehicle: String) = vehicle + "U"

        private fun Any?.feedText(): String? = this?.takeIf { it != JSONObject_NULL }?.toString()?.takeIf(String::isNotBlank)

        const val URL = "https://mapi.5t.torino.it/scre"
        private const val TAG = "MatoLiveFeed"
        private const val IDLE_CLOSE_MILLIS = 120_000L
        private val JSONObject_NULL: Any = org.json.JSONObject.NULL
    }
}
