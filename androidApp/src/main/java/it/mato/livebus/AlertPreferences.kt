package it.mato.livebus

import android.content.Context

data class BusAlert(
    val route: String,
    val routeId: String,
    val patternId: String,
    val headsign: String,
    val vehicleId: String? = null,
    val stopId: String? = null,
    val stopName: String,
    val latitude: Double,
    val longitude: Double,
    val thresholdMetres: Int = AlertPreferences.DEFAULT_DISTANCE,
    val finalAddress: String? = null,
    val finalLatitude: Double? = null,
    val finalLongitude: Double? = null,
    val transferName: String? = null,
    val transferLatitude: Double? = null,
    val transferLongitude: Double? = null,
    // Replans keep this, so the whole journey, not each leg, is what ages out.
    val startedAt: Long = System.currentTimeMillis()
)

object AlertPreferences {
    private const val STORE = "bus_alert"
    // Apart from STORE, which is wiped whenever an alert ends; this is the rider's standing choice.
    private const val SETTINGS = "bus_alert_settings"
    const val DEFAULT_DISTANCE = 700
    val DISTANCES = listOf(300, 500, DEFAULT_DISTANCE, 1000)
    // No bus journey in Turin takes this long; an alert still around is one that was never ended.
    const val MAX_AGE_MILLIS = 2 * 60 * 60_000L

    /** How close, in metres along the route, a bus must be before the rider is notified. */
    fun distance(context: Context): Int {
        val stored = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getInt("distance", DEFAULT_DISTANCE)
        return stored.takeIf { it in DISTANCES } ?: DEFAULT_DISTANCE
    }

    fun setDistance(context: Context, metres: Int) {
        if (metres !in DISTANCES) return
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putInt("distance", metres).apply()
    }

    fun save(context: Context, alert: BusAlert) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true)
            .putString("route", alert.route)
            .putString("routeId", alert.routeId)
            .putString("patternId", alert.patternId)
            .putString("headsign", alert.headsign)
            .putString("vehicleId", alert.vehicleId)
            .putString("stopId", alert.stopId)
            .putString("stopName", alert.stopName)
            .putLong("latitude", alert.latitude.toBits())
            .putLong("longitude", alert.longitude.toBits())
            .putInt("threshold", alert.thresholdMetres)
            .putString("finalAddress", alert.finalAddress)
            .putLong("finalLatitude", alert.finalLatitude?.toBits() ?: 0)
            .putLong("finalLongitude", alert.finalLongitude?.toBits() ?: 0)
            .putString("transferName", alert.transferName)
            .putLong("transferLatitude", alert.transferLatitude?.toBits() ?: 0)
            .putLong("transferLongitude", alert.transferLongitude?.toBits() ?: 0)
            .putLong("startedAt", alert.startedAt)
            .apply()
    }

    fun load(context: Context): BusAlert? {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return null
        // A restarted service must not bring back yesterday's alert; one saved before this was
        // recorded has no start time and is stale too.
        val startedAt = prefs.getLong("startedAt", 0)
        if (System.currentTimeMillis() - startedAt !in 0..MAX_AGE_MILLIS) {
            clear(context)
            return null
        }
        return BusAlert(
            route = prefs.getString("route", null) ?: return null,
            routeId = prefs.getString("routeId", null) ?: return null,
            patternId = prefs.getString("patternId", null) ?: return null,
            headsign = prefs.getString("headsign", null) ?: return null,
            vehicleId = prefs.getString("vehicleId", null),
            stopId = prefs.getString("stopId", null),
            stopName = prefs.getString("stopName", null) ?: return null,
            latitude = Double.fromBits(prefs.getLong("latitude", 0)),
            longitude = Double.fromBits(prefs.getLong("longitude", 0)),
            thresholdMetres = prefs.getInt("threshold", DEFAULT_DISTANCE),
            finalAddress = prefs.getString("finalAddress", null),
            finalLatitude = prefs.getLong("finalLatitude", 0)
                .takeIf { it != 0L }?.let(Double::fromBits),
            finalLongitude = prefs.getLong("finalLongitude", 0)
                .takeIf { it != 0L }?.let(Double::fromBits),
            transferName = prefs.getString("transferName", null),
            transferLatitude = prefs.getLong("transferLatitude", 0)
                .takeIf { it != 0L }?.let(Double::fromBits),
            transferLongitude = prefs.getLong("transferLongitude", 0)
                .takeIf { it != 0L }?.let(Double::fromBits),
            startedAt = startedAt
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
