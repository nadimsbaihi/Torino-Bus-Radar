package it.mato.livebus

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class OfflinePlace(
    val name: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val kind: String,
    val exactMatch: Boolean
) {
    override fun toString() = label
}

/** The packaged Torino extract; opening and searching belong on an IO thread. */
class OfflineCity private constructor(
    val mapFile: File,
    val mapCacheKey: String,
    private val database: SQLiteDatabase,
    private val walkGraphFile: File
) {
    private val walkGraph by lazy { WalkGraph.load(walkGraphFile) }

    fun walkingDistancesFrom(latitude: Double, longitude: Double): WalkGraph.Distances =
        walkGraph.distancesFrom(latitude, longitude)

    fun shortWalkMetres(fromLatitude: Double, fromLongitude: Double,
                        toLatitude: Double, toLongitude: Double): Float? =
        walkGraph.shortWalkMetres(fromLatitude, fromLongitude, toLatitude, toLongitude)

    fun shortWalkRoute(fromLatitude: Double, fromLongitude: Double,
                       toLatitude: Double, toLongitude: Double): WalkGraph.Route? =
        walkGraph.shortWalkRoute(fromLatitude, fromLongitude, toLatitude, toLongitude)

    fun search(query: String): List<OfflinePlace> {
        val normalized = normalize(query)
        val words = normalized.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        // Numeric tokens are exact: house 1 must not silently resolve to house 10.
        // Whitespace means AND in both SQLite FTS4 query syntax variants.
        val expression = words.joinToString(" ") { word ->
            if (word.any(Char::isDigit)) "\"$word\"" else "\"$word*\""
        }
        val sql = """
            SELECT p.name, p.label, p.lat, p.lon, p.kind,
                   (p.name_key = ? OR p.address_key = ?) AS exact_match
            FROM search JOIN places p ON p.id = search.rowid
            WHERE search MATCH ?
            ORDER BY exact_match DESC,
                     CASE WHEN p.name_key LIKE ? THEN 0 ELSE 1 END,
                     CASE p.kind WHEN 'place' THEN 0 WHEN 'address' THEN 1 ELSE 2 END,
                     length(p.name), length(p.label), p.id
            LIMIT 12
        """.trimIndent()
        return database.rawQuery(sql, arrayOf(normalized, normalized, expression, "$normalized%"))
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(OfflinePlace(cursor.getString(0), cursor.getString(1),
                            cursor.getDouble(2), cursor.getDouble(3), cursor.getString(4),
                            cursor.getInt(5) != 0))
                    }
                }
            }
    }

    companion object {
        fun open(context: Context): OfflineCity {
            val manifest = JSONObject(context.assets.open("offline/manifest.json")
                .bufferedReader().use { it.readText() })
            // Bundled data can be restored from the APK, so don't include it in backups.
            val directory = File(context.noBackupFilesDir, "offline-torino").apply { mkdirs() }
            val files = manifest.getJSONObject("files")
            for (name in listOf("torino.map", "places.sqlite", "walk_graph.bin")) {
                val specification = files.getJSONObject(name)
                val expected = specification.getString("sha256")
                val target = File(directory, name)
                val marker = File(directory, "$name.sha256")
                if (target.length() == specification.getLong("bytes") &&
                    marker.exists() && marker.readText() == expected) continue
                val temporary = File(directory, "$name.tmp")
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    context.assets.open("offline/$name").use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                    check(checksum == expected) { "Offline city data failed verification: $name" }
                    check(temporary.renameTo(target)) { "Cannot install offline city data: $name" }
                    marker.writeText(expected)
                } finally {
                    temporary.delete()
                }
            }
            val database = SQLiteDatabase.openDatabase(File(directory, "places.sqlite").path,
                null, SQLiteDatabase.OPEN_READONLY)
            return OfflineCity(File(directory, "torino.map"),
                "torino-" + files.getJSONObject("torino.map").getString("sha256").take(12),
                database, File(directory, "walk_graph.bin"))
        }

        private fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }
}
