package it.mato.livebus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BusMarkerTest {
    private val activity by lazy {
        Robolectric.buildActivity(MainActivity::class.java).get().apply { setTheme(R.style.Theme_MatoLiveBus) }
    }

    private fun marker(closest: Boolean, bearing: Float?, route: String = "67"): Bitmap {
        val drawable = MainActivity::class.java.getDeclaredMethod("createBusDirectionMarker",
            Boolean::class.javaPrimitiveType, java.lang.Float::class.java, String::class.java, BusLook::class.java,
            Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, closest, bearing, route, BusLook.LIVE, 0xFF1565C0.toInt()) as BitmapDrawable
        return drawable.bitmap
    }

    /** Whether anything is drawn [fraction] of the way from the centre to the edge, towards [bearing]. */
    private fun drawnTowards(bitmap: Bitmap, bearing: Double, fraction: Double): Boolean {
        val angle = Math.toRadians(bearing - 90.0)
        val half = bitmap.width / 2.0
        val x = (half + kotlin.math.cos(angle) * half * fraction).toInt()
        val y = (half + kotlin.math.sin(angle) * half * fraction).toInt()
        return Color.alpha(bitmap.getPixel(x, y)) > 0
    }

    @Test fun theBusPointsTheWayItIsGoing() {
        for (bearing in listOf(0f, 45f, 90f, 200f, 300f)) {
            val bus = marker(false, bearing)
            assertTrue("the point at $bearing°", drawnTowards(bus, bearing.toDouble(), 0.85))
            assertTrue("nothing behind at $bearing°", !drawnTowards(bus, bearing + 180.0, 0.85))
        }
        // With no known direction there is nothing to point with.
        val still = marker(false, null)
        for (bearing in listOf(0.0, 90.0, 180.0, 270.0)) assertTrue(!drawnTowards(still, bearing, 0.85))
        // The bitmap is centred on the bus, whichever way it points, so the marker sits on the bus.
        assertEquals(marker(false, 0f).width, marker(false, 180f).width)
    }

    @Test fun writesAPreview() {
        val bearings = listOf(null, 0f, 60f, 135f, 225f, 300f)
        val cells = bearings.flatMap { b -> listOf(marker(false, b), marker(true, b), marker(false, b, "3165")) }
        val size = cells.maxOf { it.width } + 8
        val sheet = Bitmap.createBitmap(size * 3, size * bearings.size, Bitmap.Config.ARGB_8888)
        Canvas(sheet).apply {
            drawColor(Color.rgb(236, 234, 228))
            cells.forEachIndexed { index, cell ->
                drawBitmap(cell, (index % 3) * size + (size - cell.width) / 2f,
                    (index / 3) * size + (size - cell.height) / 2f, null)
            }
        }
        val output = File("build/reports/bus-marker-preview.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
