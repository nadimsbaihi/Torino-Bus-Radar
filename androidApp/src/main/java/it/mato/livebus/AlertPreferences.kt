package it.mato.livebus

import android.content.Context

/** A reminder to leave for one bus line's stop, as the background service runs it. */
data class BusAlert(
    val route: String,
    val routeId: String,
    val patternId: String,
    val headsign: String,
    // The bus the journey planned with; another one of the line is chosen when it cannot be caught.
    val vehicleId: String? = null,
    val stopId: String,
    val stopName: String,
    val latitude: Double,
    val longitude: Double,
    // How early the rider wants to be at the stop before the bus.
    val spareMinutes: Int = AlertPreferences.DEFAULT_SPARE,
    // Replans keep this, so the whole reminder, not each bus, is what ages out.
    val startedAt: Long = System.currentTimeMillis()
)

object AlertPreferences {
    private const val STORE = "bus_alert"
    // Apart from STORE, which is wiped whenever an alert ends; this is the rider's standing choice.
    private const val SETTINGS = "bus_alert_settings"
    const val DEFAULT_SPARE = 1
    val SPARE_MINUTES = listOf(DEFAULT_SPARE, 5, 10)
    // No bus journey in Turin takes this long; an alert still around is one that was never ended.
    const val MAX_AGE_MILLIS = 2 * 60 * 60_000L

    /** Minutes the rider wants to be at the stop before the bus; the last choice is kept. */
    fun spareMinutes(context: Context): Int {
        val stored = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getInt("spareMinutes", DEFAULT_SPARE)
        return stored.takeIf { it in SPARE_MINUTES } ?: DEFAULT_SPARE
    }

    fun setSpareMinutes(context: Context, minutes: Int) {
        if (minutes !in SPARE_MINUTES) return
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putInt("spareMinutes", minutes).apply()
    }

    fun save(context: Context, alert: BusAlert) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .clear()
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
            .putInt("spareMinutes", alert.spareMinutes)
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
            stopId = prefs.getString("stopId", null) ?: return null,
            stopName = prefs.getString("stopName", null) ?: return null,
            latitude = Double.fromBits(prefs.getLong("latitude", 0)),
            longitude = Double.fromBits(prefs.getLong("longitude", 0)),
            spareMinutes = prefs.getInt("spareMinutes", DEFAULT_SPARE).takeIf { it in SPARE_MINUTES }
                ?: DEFAULT_SPARE,
            startedAt = startedAt
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
