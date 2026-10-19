package it.mato.livebus

import android.app.Application
import org.osmdroid.config.Configuration
import org.osmdroid.mapsforge.MapsForgeTileSource

class MatoApplication : Application() {
    val offlineCity by lazy { OfflineCity.open(this) }

    override fun onCreate() {
        super.onCreate()
        Appearance.apply(this)
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
