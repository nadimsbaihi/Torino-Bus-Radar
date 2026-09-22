package it.mato.livebus

import android.app.Application
import org.osmdroid.config.Configuration

class MatoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            load(this@MatoApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
        }
    }
}
