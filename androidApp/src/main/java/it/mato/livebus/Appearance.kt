package it.mato.livebus

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Whether the app follows the phone's dark mode or is held light or dark. */
enum class Appearance(val nightMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        private const val STORE = "appearance"
        private const val KEY = "mode"

        fun load(context: Context): Appearance {
            val stored = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == stored } ?: SYSTEM
        }

        fun save(context: Context, appearance: Appearance) {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY, appearance.name).apply()
        }

        /** Applies the saved choice; changing it recreates open activities with the new look. */
        fun apply(context: Context) = AppCompatDelegate.setDefaultNightMode(load(context).nightMode)
    }
}
