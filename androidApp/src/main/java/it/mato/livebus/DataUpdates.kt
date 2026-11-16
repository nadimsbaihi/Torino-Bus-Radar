package it.mato.livebus

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPInputStream

/** One timetable file the app can refresh, with the layouts it can read and a check that a copy is usable. */
class DataFile(val name: String, val schemas: IntRange, val validate: (Context, String) -> Unit)

/**
 * The bus, train and metro timetables ship with the app but go stale when GTT or Trenitalia publish a new
 * one. A scheduled job rebuilds them from the operators' feeds and publishes them as a release of this
 * repository; this looks there now and then, downloads what changed, and keeps it beside the bundled copy.
 * A download is checked against its published checksum and parsed before it replaces anything, and
 * [DataFiles.load] falls back to the bundled copy if a downloaded one ever fails to read.
 * New data is read the next time the app starts, so a timetable never changes under a journey on screen.
 */
class DataUpdates(
    private val context: Context,
    private val client: OkHttpClient = defaultClient,
    private val baseUrl: String = BASE_URL,
    private val files: List<DataFile> = FILES
) {
    private val preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val folder = DataFiles.folder(context)

    fun dueForCheck(nowMillis: Long = System.currentTimeMillis()) =
        nowMillis - preferences.getLong(LAST_CHECK, 0) >= CHECK_INTERVAL_MILLIS

    /** Downloads every file whose published copy differs from the one in use; returns the names updated. */
    suspend fun update(): List<String> = withContext(Dispatchers.IO) {
        val root = JSONObject(fetchText(baseUrl + MANIFEST))
        val manifest = root.getJSONObject("files")
        preferences.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
        val updated = files.mapNotNull { file ->
            val entry = manifest.optJSONObject(file.name) ?: return@mapNotNull null
            if (!isOffered(file, entry) || entry.getString("sha256").equals(checksumInUse(file.name), true)) {
                return@mapNotNull null
            }
            try {
                download(file, entry)
                file.name
            } catch (error: Exception) {
                // One bad file (offline, corrupt, unreadable) must not stop the others; the next check retries it.
                android.util.Log.w("MatoLiveBus", "Could not update ${file.name}", error)
                null
            }
        }
        if (updated.isNotEmpty()) preferences.edit().putString(GENERATED, root.optString("generated")).apply()
        updated
    }

    /** When the downloaded timetables were built, as the feed gave it (ISO-8601, UTC), or null. */
    fun generated(): String? = preferences.getString(GENERATED, null)?.takeIf { it.isNotBlank() }

    /** Only a file in a layout this version of the app reads is worth fetching. */
    private fun isOffered(file: DataFile, entry: JSONObject) =
        entry.optInt("schema", -1) in file.schemas && entry.has("asset") && entry.has("sha256")

    private fun checksumInUse(name: String): String? {
        val downloaded = File(folder, name)
        if (downloaded.isFile) return preferences.getString(CHECKSUM + name, null)
        return try {
            sha256(context.assets.open(name).use { it.readBytes() })
        } catch (error: IOException) {
            null
        }
    }

    private fun download(file: DataFile, entry: JSONObject) {
        val bytes = client.newCall(Request.Builder().url(baseUrl + entry.getString("asset")).build())
            .execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download answered ${response.code}")
                val body = response.body ?: throw IOException("Empty download")
                GZIPInputStream(body.byteStream()).use { gz -> readLimited(gz) }
            }
        val actual = sha256(bytes)
        if (!entry.getString("sha256").equals(actual, ignoreCase = true)) {
            throw IOException("${file.name} does not match its published checksum")
        }
        // Parse before keeping it: a copy the app cannot read would only be thrown away at the next start.
        file.validate(context, bytes.toString(Charsets.UTF_8))
        folder.mkdirs()
        val partial = File(folder, file.name + ".part")
        partial.writeBytes(bytes)
        val target = File(folder, file.name)
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("Could not keep ${file.name}")
        }
        preferences.edit().putString(CHECKSUM + file.name, actual).apply()
    }

    private fun fetchText(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Data feed answered ${response.code}")
            response.body?.string().orEmpty()
        }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) throw IOException("Download is larger than any timetable")
        }
        return out.toByteArray()
    }

    companion object {
        private const val STORE = "data_updates"
        private const val LAST_CHECK = "last_check"
        private const val CHECKSUM = "sha256_"
        private const val GENERATED = "generated"
        private const val MANIFEST = "manifest.json"
        private const val MAX_BYTES = 64 * 1024 * 1024
        private const val CHECK_INTERVAL_MILLIS = 12 * 60 * 60 * 1000L
        const val BASE_URL = "https://github.com/nadimsbaihi/Torino-Bus-Radar/releases/download/data/"

        val FILES = listOf(
            DataFile(TransitIndex.ASSET, TransitIndex.SCHEMA..TransitIndex.SCHEMA) { context, json ->
                TransitIndex.parse(context, json)
            }
        ) + TrainIndex.ASSETS.map { name ->
            DataFile(name, TrainIndex.SCHEMAS) { _, json -> TrainIndex.parse(json) }
        }

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build()

        /** Drops a downloaded copy, so the bundled one is used until the next successful update. */
        fun forget(context: Context, name: String) {
            File(DataFiles.folder(context), name).delete()
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(CHECKSUM + name).apply()
        }

        /** An app update brings timetables of its own; downloaded ones from before it are dropped. */
        fun dropIfAppChanged(context: Context, versionCode: Int) {
            val preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            if (preferences.getInt("app_version", versionCode) != versionCode) {
                DataFiles.folder(context).deleteRecursively()
                preferences.edit().clear().apply()
            }
            preferences.edit().putInt("app_version", versionCode).apply()
        }

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
