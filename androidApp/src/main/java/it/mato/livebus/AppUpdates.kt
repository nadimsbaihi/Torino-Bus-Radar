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

/** A published release of the app: its version and the signed APK to install it from. */
data class AppRelease(val version: String, val apkUrl: String, val sha256: String?)

/**
 * The app is published only as GitHub releases, so this looks there for a newer one and downloads its APK.
 * Android itself then asks the rider to install it, and refuses an APK not signed like the installed app.
 */
class AppUpdates(context: Context, private val client: OkHttpClient = defaultClient) {
    private val preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val folder = File(context.cacheDir, FOLDER)

    /** Looking once in a while is enough; a release a few hours old can wait. */
    fun dueForCheck(nowMillis: Long = System.currentTimeMillis()) =
        nowMillis - preferences.getLong(LAST_CHECK, 0) >= CHECK_INTERVAL_MILLIS

    /** The latest release when it is newer than [currentVersion], else null. */
    suspend fun newerRelease(currentVersion: String): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json").build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            response.body?.string().orEmpty()
        }
        preferences.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
        parseLatest(body)?.takeIf { isNewer(it.version, currentVersion) }
    }

    /** Downloads the release's APK into the cache, reporting whole percents, and checks it arrived intact. */
    suspend fun download(release: AppRelease, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        // Only the update being installed is worth keeping.
        folder.deleteRecursively()
        folder.mkdirs()
        val apk = File(folder, "Torino-Bus-Radar-${release.version}.apk")
        val partial = File(folder, apk.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download answered ${response.code}")
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !release.sha256.equals(actual, ignoreCase = true)) {
            partial.delete()
            throw IOException("Downloaded APK does not match its published checksum")
        }
        if (!partial.renameTo(apk)) throw IOException("Could not keep the downloaded APK")
        apk
    }

    companion object {
        const val FOLDER = "updates"
        private const val STORE = "app_updates"
        private const val LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MILLIS = 12 * 60 * 60 * 1000L
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/nadimsbaihi/Torino-Bus-Radar/releases/latest"

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build()

        /** The release GitHub calls latest, if it is final and carries an APK. */
        fun parseLatest(json: String): AppRelease? {
            val release = JSONObject(json)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
            val version = release.optString("tag_name").removePrefix("v").ifBlank { return null }
            val assets = release.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            val sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            return AppRelease(version, apk.getString("browser_download_url"), sha256)
        }

        /** Compares dotted versions number by number, so 0.10.0 is newer than 0.9.3. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(version: String) = version.substringBefore('-').split('.')
                .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val difference = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (difference != 0) return difference > 0
            }
            return false
        }
    }
}
