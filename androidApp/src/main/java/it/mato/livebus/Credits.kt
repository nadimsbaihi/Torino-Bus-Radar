package it.mato.livebus

import android.app.Activity
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/**
 * Where the app's data and code come from, as their licences ask to be told: GTT's data licence
 * wants its own credit line and link, Trenitalia's timetable and OpenStreetMap theirs, and the
 * libraries a copy of their licence.
 */
object Credits {
    /** A library shipped in the app and the licence texts (in assets/licenses) that come with it. */
    class Library(val name: String, val licence: String, val texts: List<String>)

    private const val APACHE = "Apache License 2.0"

    val LIBRARIES = listOf(
        Library("AndroidX, Material Components for Android", APACHE, listOf("apache-2.0.txt")),
        Library("AndroidSVG", APACHE, listOf("apache-2.0.txt")),
        Library("GTFS Realtime Bindings", APACHE, listOf("apache-2.0.txt")),
        Library("Guava ListenableFuture, Error Prone annotations", APACHE, listOf("apache-2.0.txt")),
        Library("Kotlin, kotlinx.coroutines, JetBrains annotations", APACHE, listOf("apache-2.0.txt")),
        Library("OkHttp, Okio", APACHE, listOf("apache-2.0.txt")),
        Library("osmdroid", APACHE, listOf("apache-2.0.txt")),
        // The LGPL is a set of permissions on top of the GPL, so both come with it.
        Library("Mapsforge (source: github.com/mapsforge/mapsforge)", "GNU LGPL 3.0",
            listOf("lgpl-3.0.txt", "gpl-3.0.txt")),
        Library("Protocol Buffers", "BSD 3-Clause", listOf("protobuf-bsd-3.txt"))
    )

    fun show(activity: Activity) {
        val message = TextView(activity).apply {
            text = HtmlCompat.fromHtml(activity.getString(R.string.credits_body), HtmlCompat.FROM_HTML_MODE_COMPACT)
            movementMethod = LinkMovementMethod.getInstance()
            setTextIsSelectable(false)
            val padding = dp(activity, 24)
            setPadding(padding, dp(activity, 8), padding, 0)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.credits_title)
            .setView(ScrollView(activity).apply { addView(message) })
            .setNeutralButton(R.string.credits_open_source) { _, _ -> showLibraries(activity) }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showLibraries(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.credits_open_source)
            .setItems(LIBRARIES.map { "${it.name}\n${it.licence}" }.toTypedArray()) { _, position ->
                showLicence(activity, LIBRARIES[position])
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showLicence(activity: Activity, library: Library) {
        val text = library.texts.joinToString("\n\n") { name ->
            activity.assets.open("licenses/$name").bufferedReader().use { it.readText() }
        }
        val body = TextView(activity).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val padding = dp(activity, 16)
            setPadding(padding, padding, padding, padding)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(library.licence)
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
}
