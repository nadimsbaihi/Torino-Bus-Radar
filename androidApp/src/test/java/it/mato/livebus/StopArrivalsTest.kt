package it.mato.livebus

import android.app.Application
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StopArrivalsTest {
    private val a = TransitStop("a", "A", 45.0, 7.0)
    private val b = TransitStop("b", "B", 45.01, 7.0)
    private val c = TransitStop("c", "C", 45.02, 7.0)
    private val line = TransitPattern("p", "r4", "4", "Falchera", "0", emptySet(), listOf(a, b, c, b))
    private val other = TransitPattern("q", "r9", "9", "Nord", "0", emptySet(), listOf(c, b))
    private val now = System.currentTimeMillis() / 1000

    private fun vehicle(id: String) = LiveVehicle(id, "r4", null, 45.0, 7.0, null, now)
    private fun tracked(id: String, pattern: TransitPattern, progress: Int, vararg etas: Float, turnaround: Turnaround? = null) =
        TrackedVehicle(vehicle(id), pattern, progress, etas, turnaround)

    @Test fun listsBusesSoonestFirstAndCapsTheList() {
        val buses = (1..7).map { tracked("v$it", line, 0, 0f, (700 - it * 60).toFloat(), 900f, 1000f) }

        val shown = StopArrivals.upcoming(buses, "b")

        assertEquals(5, shown.size)
        assertEquals(listOf("v7", "v6", "v5", "v4", "v3"), shown.map { it.vehicleId })
        assertEquals(shown.map { it.etaSeconds }.sorted(), shown.map { it.etaSeconds })
        assertEquals("4", shown.first().route)
        assertEquals("Falchera", shown.first().headsign)
    }

    @Test fun ignoresBusesThatHavePassedTheStopOrDoNotCallThere() {
        val passed = tracked("passed", line, 2, 0f, 0f, 0f, 60f)
        val elsewhere = tracked("elsewhere", other, 0, 0f, 300f)
        val coming = tracked("coming", other, 0, 0f, 400f)

        val shown = StopArrivals.upcoming(listOf(passed, elsewhere, coming), "c")

        // "passed" is already beyond c; the others start at c (progress 0) so it is behind them too.
        assertEquals(emptyList<String>(), shown.map { it.vehicleId })
        assertEquals(listOf("elsewhere", "coming"),
            StopArrivals.upcoming(listOf(elsewhere, coming), "b").map { it.vehicleId })
    }

    @Test fun aLoopLineUsesTheNextCallStillAhead() {
        val bus = tracked("loop", line, 1, 0f, 0f, 200f, 500f)

        assertEquals(500f, StopArrivals.upcoming(listOf(bus), "b").single().etaSeconds, 0f)
    }

    @Test fun aBusTrackedTwiceAppearsOnceWithItsEarlierRun() {
        val now = tracked("t", line, 0, 0f, 300f, 400f, 500f)
        val later = tracked("t", other, -1, 900f, 1000f, turnaround = Turnaround("p", 900f))

        val shown = StopArrivals.upcoming(listOf(later, now), "b")

        assertEquals(1, shown.size)
        assertEquals(300f, shown.single().etaSeconds, 0f)
    }

    @Test fun nothingOnTheWayIsAnEmptyList() {
        assertTrue(StopArrivals.upcoming(emptyList(), "b").isEmpty())
    }

    @Test fun theBubbleShowsRowsOrTheEmptyMessage() {
        val activity = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).create().get()
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_Light)
        val window = StopArrivalsWindow(MapView(activity)) {}
        val view = window.view
        val title = view.findViewById<TextView>(R.id.stopArrivalsTitle)
        val rows = view.findViewById<LinearLayout>(R.id.stopArrivalsRows)
        val empty = view.findViewById<TextView>(R.id.stopArrivalsEmpty)

        window.render("Via Roma", listOf(StopArrival("v", "4", "Falchera", 130f)))
        assertEquals("Via Roma", title.text.toString())
        assertEquals(1, rows.childCount)
        assertEquals("~3 min", rows.getChildAt(0).findViewById<TextView>(R.id.arrivalEta).text.toString())
        assertEquals(android.view.View.GONE, empty.visibility)

        window.render("Via Roma", emptyList())
        assertEquals(0, rows.childCount)
        assertEquals(android.view.View.VISIBLE, empty.visibility)
    }
}
