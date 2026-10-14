package it.mato.livebus

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** Home-screen shortcuts that open the app and plan a journey to a saved place. */
object PlaceShortcuts {
    const val ACTION_GO_TO_PLACE = "it.mato.livebus.GO_TO_PLACE"
    const val EXTRA_NAME = "place_name"
    const val EXTRA_LATITUDE = "place_latitude"
    const val EXTRA_LONGITUDE = "place_longitude"
    const val MAX = 4

    /** The places that get a shortcut: the first few by name, as the saved list shows them. */
    fun chosen(places: List<SavedPlace>, limit: Int = MAX) = places.take(limit)

    fun intentFor(context: Context, place: SavedPlace) =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_GO_TO_PLACE)
            .putExtra(EXTRA_NAME, place.name)
            .putExtra(EXTRA_LATITUDE, place.latitude)
            .putExtra(EXTRA_LONGITUDE, place.longitude)

    fun publish(context: Context, places: List<SavedPlace>) {
        runCatching {
            val limit = minOf(MAX, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))
            val shortcuts = chosen(places, limit).map { place ->
                ShortcutInfoCompat.Builder(context, "place:${place.id}")
                    .setShortLabel(place.name)
                    .setLongLabel(place.label)
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_bookmark))
                    .setIntent(intentFor(context, place))
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }
}
