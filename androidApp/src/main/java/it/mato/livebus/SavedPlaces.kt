package it.mato.livebus

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

/**
 * A spot the rider named: "Mum's" for an address, or a pin dropped where the bundled data has no
 * address. [address] is what the spot was found as, if anything; it stays searchable.
 */
data class SavedPlace(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
) {
    /** The name, then the address when that says more than the name does. */
    val label: String
        get() = when {
            address.isBlank() -> name
            SavedPlaces.key(address).startsWith(SavedPlaces.key(name)) -> address
            else -> "$name · $address"
        }

    fun isAt(otherLatitude: Double, otherLongitude: Double) =
        abs(latitude - otherLatitude) < SAME_SPOT_DEGREES &&
            abs(longitude - otherLongitude) < SAME_SPOT_DEGREES

    /** The suggestion list shows saved spots as places, marked with a star. */
    fun asOfflinePlace(exactMatch: Boolean) =
        OfflinePlace(name, "★ $label", latitude, longitude, KIND, exactMatch)

    companion object {
        const val KIND = "saved"
        // About four metres: the same doorway, not the next one down the street.
        private const val SAME_SPOT_DEGREES = 0.00004
    }
}

/** The riders' named places, kept on the device and searched along with the bundled city. */
class SavedPlaces(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    enum class NameProblem { BLANK, TAKEN }

    /** Every saved place, by name. */
    fun all(): List<SavedPlace> = read().sortedBy { key(it.name) }

    fun isEmpty() = read().isEmpty()

    fun byName(name: String): SavedPlace? {
        val wanted = key(name)
        return if (wanted.isEmpty()) null else read().firstOrNull { key(it.name) == wanted }
    }

    fun at(latitude: Double, longitude: Double): SavedPlace? =
        read().firstOrNull { it.isAt(latitude, longitude) }

    /**
     * Places whose name or address starts with every word of [query]. A number must match whole,
     * as in the bundled search: house 1 is not house 10. A blank query matches everything.
     */
    fun search(query: String): List<SavedPlace> {
        val words = OfflineCity.searchWords(query)
        if (words.isEmpty()) return all()
        val wanted = words.joinToString(" ")
        return all().filter { place ->
            val available = OfflineCity.searchWords("${place.name} ${place.address}")
            words.all { word ->
                available.any { if (word.any(Char::isDigit)) it == word else it.startsWith(word) }
            }
        }.sortedBy { place ->
            val name = key(place.name)
            when {
                name == wanted -> 0
                name.startsWith(wanted) -> 1
                else -> 2
            }
        }
    }

    /**
     * Adds [place], or replaces the saved place with the same id (a rename). Returns why the name
     * cannot be used, or null once saved.
     */
    @Synchronized
    fun save(place: SavedPlace): NameProblem? {
        val name = clean(place.name)
        if (name.isEmpty()) return NameProblem.BLANK
        val others = read().filter { it.id != place.id }
        if (others.any { key(it.name) == key(name) }) return NameProblem.TAKEN
        write(others + place.copy(name = name))
        return null
    }

    @Synchronized
    fun remove(place: SavedPlace) {
        write(read().filter { it.id != place.id })
    }

    private fun read(): List<SavedPlace> {
        val stored = preferences.getString(PLACES, null) ?: return emptyList()
        return try {
            val array = JSONArray(stored)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                SavedPlace(item.getString("id"), item.getString("name"),
                    item.optString("address"), item.getDouble("lat"), item.getDouble("lon"))
            }
        } catch (_: JSONException) {
            // Unreadable data is no reason to stop the search working.
            emptyList()
        }
    }

    private fun write(places: List<SavedPlace>) {
        val array = JSONArray()
        places.forEach { place ->
            array.put(JSONObject()
                .put("id", place.id)
                .put("name", place.name)
                .put("address", place.address)
                .put("lat", place.latitude)
                .put("lon", place.longitude))
        }
        preferences.edit().putString(PLACES, array.toString()).apply()
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
        private const val STORE = "saved_places"
        private const val PLACES = "places"
        private val WHITESPACE = Regex("\\s+")

        /** What gets stored: no stray spaces, no longer than a dialog can sensibly show. */
        fun clean(name: String) = name.trim().replace(WHITESPACE, " ").take(MAX_NAME_LENGTH)

        /** Two names are the same when a search would read them the same. */
        fun key(name: String) = OfflineCity.searchWords(name).joinToString(" ")

        /**
         * [saved] first, then what the bundled city found, without a second entry for a spot the
         * rider has already named.
         */
        fun merge(saved: List<SavedPlace>, found: List<OfflinePlace>, query: String, limit: Int):
            List<OfflinePlace> {
            val wanted = key(query)
            val named = saved.map { it.asOfflinePlace(exactMatch = key(it.name) == wanted) }
            val rest = found.filter { place -> saved.none { it.isAt(place.latitude, place.longitude) } }
            return (named + rest).take(limit)
        }
    }
}

/** A spot on the map with no address: written as "45.07031, 7.68690", which is also what you can type. */
object MapPin {
    private val COORDINATES = Regex("""^\s*(-?\d{1,2}\.\d+)\s*[,;]\s*(-?\d{1,3}\.\d+)\s*$""")

    fun format(latitude: Double, longitude: Double): String =
        String.format(java.util.Locale.ROOT, "%.5f, %.5f", latitude, longitude)

    fun parse(text: String): Pair<Double, Double>? {
        val match = COORDINATES.matchEntire(text) ?: return null
        val latitude = match.groupValues[1].toDouble()
        val longitude = match.groupValues[2].toDouble()
        return if (abs(latitude) <= 90.0 && abs(longitude) <= 180.0) latitude to longitude else null
    }
}

/** The last few places the rider went to, newest first, offered when the search box is empty. */
class RecentDestinations(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun all(): List<OfflinePlace> {
        val stored = preferences.getString(ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(stored)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val name = item.getString("name")
                OfflinePlace(name, name, item.getDouble("lat"), item.getDouble("lon"), KIND, false)
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    /** Puts [name] first; the same name or the same spot earlier in the list goes. */
    @Synchronized
    fun add(name: String, latitude: Double, longitude: Double) {
        val text = SavedPlaces.clean(name)
        if (text.isEmpty()) return
        val kept = all().filter {
            SavedPlaces.key(it.name) != SavedPlaces.key(text) &&
                !(abs(it.latitude - latitude) < SAME_SPOT_DEGREES && abs(it.longitude - longitude) < SAME_SPOT_DEGREES)
        }
        val array = JSONArray()
        (listOf(OfflinePlace(text, text, latitude, longitude, KIND, false)) + kept).take(MAX).forEach {
            array.put(JSONObject().put("name", it.name).put("lat", it.latitude).put("lon", it.longitude))
        }
        preferences.edit().putString(ITEMS, array.toString()).apply()
    }

    companion object {
        const val KIND = "recent"
        const val MAX = 8
        private const val STORE = "recent_destinations"
        private const val ITEMS = "items"
        private const val SAME_SPOT_DEGREES = 0.00004
    }
}
