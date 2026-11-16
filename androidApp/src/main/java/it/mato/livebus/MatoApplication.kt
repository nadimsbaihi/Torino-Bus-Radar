package it.mato.livebus

import android.app.Application
import org.osmdroid.config.Configuration
import org.osmdroid.mapsforge.MapsForgeTileSource

class MatoApplication : Application() {
    val offlineCity by lazy { OfflineCity.open(this) }

    // Read once per run, so the map rebuilt by a theme change places its buses without parsing them again.
    // Only a timetable download replaces them, through [reloadTimetables].
    private val timetableLock = Any()
    private var transit: TransitIndex? = null
    private var trains: TrainIndex? = null

    val transitIndex: TransitIndex
        get() = synchronized(timetableLock) { transit ?: TransitIndex.load(this).also { transit = it } }
    val trainIndex: TrainIndex
        get() = synchronized(timetableLock) { trains ?: TrainIndex.load(this).also { trains = it } }

    /**
     * Reads the timetables again, to pick up a download without restarting the app. Parses first and
     * swaps both at once, so a failure leaves the ones in use; call it off the main thread. Screens
     * hold the old indexes until they are rebuilt.
     */
    fun reloadTimetables() {
        val newTransit = TransitIndex.load(this)
        val newTrains = TrainIndex.load(this)
        synchronized(timetableLock) {
            transit = newTransit
            trains = newTrains
        }
    }

    override fun onCreate() {
        super.onCreate()
        Appearance.apply(this)
        DataUpdates.dropIfAppChanged(this, BuildConfig.VERSION_CODE)
        DataSource.init(this)
        MapsForgeTileSource.createInstance(this)
        Configuration.getInstance().apply {
            load(this@MatoApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            // Satellite photos seen once are kept for offline use, but they are not allowed to fill the phone.
            tileFileSystemCacheMaxBytes = 120L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 100L * 1024 * 1024
            // Two downloads at a time left a screen of photos blank for some seconds.
            tileDownloadThreads = 8
        }
    }
}
