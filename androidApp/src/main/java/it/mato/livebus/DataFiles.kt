package it.mato.livebus

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Where the timetable files live. Each ships inside the app, and [DataUpdates] may have put a newer
 * copy beside them in the app's own storage; the newer copy is read when it is sound.
 */
object DataFiles {
    private const val FOLDER = "data"
    private const val TAG = "MatoLiveBus"

    fun folder(context: Context) = File(context.filesDir, FOLDER)

    /** Reads [name] with [parse]: the downloaded copy if it parses, else the copy bundled with the app. */
    fun <T> load(context: Context, name: String, parse: (String) -> T): T {
        val downloaded = File(folder(context), name)
        if (downloaded.isFile) {
            try {
                return parse(downloaded.readText())
            } catch (error: Exception) {
                // A damaged or unreadable download must never leave the app without a timetable.
                Log.w(TAG, "Dropping downloaded $name", error)
                DataUpdates.forget(context, name)
            }
        }
        return parse(context.assets.open(name).bufferedReader().use { it.readText() })
    }
}
