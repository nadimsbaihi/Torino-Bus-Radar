package it.mato.livebus

import android.content.Context
import androidx.annotation.DrawableRes

/** How a search suggestion reads: an icon for its kind, its name, and a second line saying where or what it is. */
data class PlaceSuggestion(@DrawableRes val icon: Int, val title: String, val detail: String) {
    companion object {
        // Closer than this, two places of one name are the same place to a rider.
        private const val SAME_PLACE_METRES = 400.0

        /**
         * The city data maps one station as many points (entrances, platforms, the building), each
         * named alike; a list of identical names helps nobody. Keeps the first, best-ranked, of each
         * name and kind of place within [SAME_PLACE_METRES]. A stop and a station stay apart from a place.
         */
        fun collapseNearby(places: List<OfflinePlace>): List<OfflinePlace> {
            val kept = mutableListOf<OfflinePlace>()
            places.forEach { place ->
                val same = kept.any { other ->
                    group(other.kind) == group(place.kind) &&
                        SavedPlaces.key(other.name) == SavedPlaces.key(place.name) &&
                        metresBetween(other, place) < SAME_PLACE_METRES
                }
                if (!same) kept += place
            }
            return kept
        }

        private fun group(kind: String) = when (kind) {
            "bus_stop", "train_station" -> kind
            else -> "place"
        }

        private fun metresBetween(a: OfflinePlace, b: OfflinePlace): Double {
            val latitude = Math.toRadians((a.latitude + b.latitude) / 2)
            val north = (a.latitude - b.latitude) * 111_320.0
            val east = (a.longitude - b.longitude) * 111_320.0 * kotlin.math.cos(latitude)
            return kotlin.math.sqrt(north * north + east * east)
        }

        fun of(context: Context, place: OfflinePlace): PlaceSuggestion {
            // A spot pressed on the map is known only by its coordinates, which make a poor title.
            if (place.kind == "pin" || MapPin.parse(place.name) != null) {
                return PlaceSuggestion(if (place.kind == RecentDestinations.KIND) R.drawable.ic_history else R.drawable.ic_place,
                    context.getString(R.string.suggestion_map_spot), place.name)
            }
            // The bundled labels read "name · town" (or "name · GTT stop"); the town is the useful part.
            val rest = place.label.removePrefix("★ ").let { label ->
                if (label.startsWith(place.name)) label.removePrefix(place.name).removePrefix(" · ") else label
            }.takeIf { it.isNotBlank() && it != place.name }
            return when (place.kind) {
                SavedPlace.KIND -> PlaceSuggestion(R.drawable.ic_bookmark_filled, place.name,
                    rest ?: context.getString(R.string.suggestion_saved))
                RecentDestinations.KIND -> PlaceSuggestion(R.drawable.ic_history, place.name,
                    context.getString(R.string.suggestion_recent))
                "bus_stop" -> PlaceSuggestion(R.drawable.ic_bus, place.name, context.getString(R.string.suggestion_bus_stop))
                "train_station" -> PlaceSuggestion(R.drawable.ic_train, place.name,
                    context.getString(R.string.suggestion_train_station))
                else -> PlaceSuggestion(R.drawable.ic_place, place.name, rest.orEmpty())
            }
        }
    }
}
