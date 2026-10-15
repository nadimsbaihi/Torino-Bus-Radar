package it.mato.livebus

import android.os.Looper
import android.text.Spanned
import android.text.style.URLSpan
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import it.mato.livebus.databinding.ActivityMainBinding
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CreditsTest {
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    private fun TextView.links() = (text as Spanned).getSpans(0, text.length, URLSpan::class.java).map { it.url }

    @Test fun theMapCreditOpensEverySourceWithTheWordingItsLicenceAsksFor() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val binding = MainActivity::class.java.getDeclaredField("binding")
            .apply { isAccessible = true }.get(activity) as ActivityMainBinding
        settle()
        // GTT's licence asks every use to name it; the line on the map does, and leads to the rest.
        assertTrue(binding.mapAttribution.text.toString().contains("GTT"))
        assertTrue(binding.mapAttribution.text.toString().contains("OpenStreetMap"))

        binding.mapAttribution.performClick()
        settle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val body = findText(dialog.findViewById<android.view.View>(android.R.id.content)!!)!!
        val text = body.text.toString()
        assertTrue(text.contains("Data source: GTT S.p.A. – Gruppo Torinese Trasporti"))
        assertTrue(text.contains("CC BY 4.0"))
        assertTrue(text.contains("ODbL"))
        // The resource escapes apostrophes; none may show.
        assertFalse(text.contains("\\"))
        assertTrue(body.links().containsAll(listOf("https://www.gtt.to.it",
            "https://www.gtt.to.it/gtt_gtfs_license.html", "https://www.openstreetmap.org/copyright",
            "https://creativecommons.org/licenses/by/4.0/")))

        // The open-source licences, one entry per library, each opening its full text.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        settle()
        val list = (ShadowDialog.getLatestDialog() as AlertDialog).listView
        assertEquals(Credits.LIBRARIES.size, list.adapter.count)
        shadowOf(list).performItemClick(Credits.LIBRARIES.indexOfFirst { it.name.startsWith("Mapsforge") })
        settle()
        val licence = findText((ShadowDialog.getLatestDialog() as AlertDialog).findViewById<android.view.View>(android.R.id.content)!!)
        assertTrue(licence!!.text.contains("GNU LESSER GENERAL PUBLIC LICENSE"))
        assertTrue(licence.text.contains("GNU GENERAL PUBLIC LICENSE"))
    }

    @Test fun everyLibraryHasItsLicenceTextInTheApp() {
        val assets = RuntimeEnvironment.getApplication().assets
        for (library in Credits.LIBRARIES) for (name in library.texts) {
            val text = assets.open("licenses/$name").bufferedReader().use { it.readText() }
            assertTrue("$name for ${library.name}", text.length > 1_000)
        }
    }

    /** The longest text in a dialog: its body, not its title or buttons. */
    private fun findText(root: android.view.View): TextView? {
        val found = ArrayList<TextView>()
        fun walk(view: android.view.View) {
            if (view is TextView && view !is android.widget.Button) found.add(view)
            if (view is android.view.ViewGroup && view !is ListView) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return found.maxByOrNull { it.text.length }.also { assertNotNull(it) }
    }
}
