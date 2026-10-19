package it.mato.livebus

import android.app.Application
import org.osmdroid.config.Configuration
import org.osmdroid.mapsforge.MapsForgeTileSource

class MatoApplication : Application() {
    val offlineCity by lazy { OfflineCity.open(this) }

    override fun onCreate() {
        super.onCreate()
        MapsForgeTileSource.createInstance(this)
        Configuration.getInstance().apply {
            load(this@MatoApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
        }
    }
}
