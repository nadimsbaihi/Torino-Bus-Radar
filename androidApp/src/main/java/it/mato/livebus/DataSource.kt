package it.mato.livebus

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where the live feeds come from. By default GTT's own GTFS-realtime feeds; a rider can point any
 * of the three at another GTFS-realtime URL (a proxy, a mirror, a test feed). A blank one means GTT's.
 */
object DataSource {
    const val DEFAULT_POSITIONS = "https://percorsieorari.gtt.to.it/das_gtfsrt/vehicle_position.aspx"
    const val DEFAULT_TRIP_UPDATES = "https://percorsieorari.gtt.to.it/das_gtfsrt/trip_update.aspx"
    const val DEFAULT_ALERTS = "https://percorsieorari.gtt.to.it/das_gtfsrt/alerts.aspx"

    private const val STORE = "data_source"
    private const val MATO_LIVE = "mato_live"

    enum class Feed(val key: String, val default: String) {
        POSITIONS("positions", DEFAULT_POSITIONS),
        TRIP_UPDATES("trip_updates", DEFAULT_TRIP_UPDATES),
        ALERTS("alerts", DEFAULT_ALERTS)
    }

    // Read by the repository on every fetch, from any thread.
    @Volatile private var custom: Map<Feed, String> = emptyMap()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        matoLive = prefs.getBoolean(MATO_LIVE, BuildConfig.MATO_LIVE_DEFAULT)
        custom = Feed.entries.mapNotNull { feed ->
            prefs.getString(feed.key, null)?.trim()?.takeIf(::isValid)?.let { feed to it }
        }.toMap()
    }

    /** The URL to fetch [feed] from: the rider's, or GTT's. */
    fun url(feed: Feed): String = custom[feed] ?: feed.default

    /** The rider's own URL for [feed], or empty when it uses GTT's. */
    fun customUrl(feed: Feed): String = custom[feed].orEmpty()

    /**
     * Whether bus positions also come from the MATO website's live feed: its buses are taken first,
     * GTT's position feed fills in the ones it lacks, and either stands alone when the other fails.
     * On by default in the "mato" APK.
     */
    @Volatile private var matoLive = BuildConfig.MATO_LIVE_DEFAULT

    fun matoLive(): Boolean = matoLive

    fun isCustom(): Boolean = custom.isNotEmpty() || matoLive != BuildConfig.MATO_LIVE_DEFAULT

    fun isValid(url: String): Boolean {
        val lower = url.trim().lowercase()
        return (lower.startsWith("https://") || lower.startsWith("http://")) && lower.length > 8 &&
            url.trim().toHttpUrlOrNull() != null
    }

    /** Saves the rider's URLs (blank resets one to GTT's) and applies them at once. */
    fun save(context: Context, urls: Map<Feed, String>, matoLive: Boolean = false) {
        val edit = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
        edit.putBoolean(MATO_LIVE, matoLive)
        Feed.entries.forEach { feed ->
            val value = urls[feed].orEmpty().trim()
            if (value.isEmpty() || value == feed.default) edit.remove(feed.key) else edit.putString(feed.key, value)
        }
        edit.apply()
        init(context)
    }
}
