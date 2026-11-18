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

    private fun marker(closest: Boolean, bearing: Float?, route: String = "67", tram: Boolean = false): Bitmap {
        val drawable = MainActivity::class.java.getDeclaredMethod("createBusDirectionMarker",
            Boolean::class.javaPrimitiveType, java.lang.Float::class.java, String::class.java, BusLook::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(activity, closest, bearing, route, BusLook.LIVE, 0xFF1565C0.toInt(), tram) as BitmapDrawable
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

    @Test fun aTramIsWiderThanItIsTallWhereABusIsRound() {
        val bus = marker(false, null, "4")
        val tram = marker(false, null, "4", tram = true)
        fun drawnWidth(bitmap: Bitmap): Int {
            val row = bitmap.height / 2
            return (0 until bitmap.width).count { Color.alpha(bitmap.getPixel(it, row)) > 0 }
        }
        fun drawnHeight(bitmap: Bitmap): Int {
            val column = bitmap.width / 2
            return (0 until bitmap.height).count { Color.alpha(bitmap.getPixel(column, it)) > 0 }
        }
        assertTrue("a bus is round", kotlin.math.abs(drawnWidth(bus) - drawnHeight(bus)) <= 1)
        assertTrue("a tram is wider than tall", drawnWidth(tram) > drawnHeight(tram) * 1.3)
    }

    @Test fun aTramPointsTheWayItIsGoingToo() {
        // How far from the centre something is drawn along a bearing, in pixels.
        fun reach(bitmap: Bitmap, bearing: Double): Int {
            val angle = Math.toRadians(bearing - 90.0)
            val half = bitmap.width / 2.0
            return (0 until bitmap.width).last {
                val x = (half + kotlin.math.cos(angle) * it).toInt()
                val y = (half + kotlin.math.sin(angle) * it).toInt()
                x in 0 until bitmap.width && y in 0 until bitmap.height && Color.alpha(bitmap.getPixel(x, y)) > 0
            }
        }
        for (bearing in listOf(0f, 90f, 180f, 270f, 45f)) {
            val still = marker(false, null, "4", tram = true)
            val tram = marker(false, bearing, "4", tram = true)
            assertEquals("same size marker at $bearing°", still.width, tram.width)
            assertTrue("the point at $bearing°", reach(tram, bearing.toDouble()) > reach(still, bearing.toDouble()) + 2)
            assertTrue("nothing behind at $bearing°",
                reach(tram, bearing + 180.0) <= reach(still, bearing + 180.0) + 1)
        }
    }

    @Test fun writesAPreview() {
        val bearings = listOf(null, 0f, 60f, 135f, 225f, 300f)
        val cells = bearings.flatMap { b -> listOf(marker(false, b), marker(true, b), marker(false, b, "3165"), marker(false, b, "4", tram = true),
            marker(true, b, "16 CD", tram = true)) }
        val size = cells.maxOf { it.width } + 8
        val sheet = Bitmap.createBitmap(size * 5, size * bearings.size, Bitmap.Config.ARGB_8888)
        Canvas(sheet).apply {
            drawColor(Color.rgb(236, 234, 228))
            cells.forEachIndexed { index, cell ->
                drawBitmap(cell, (index % 5) * size + (size - cell.width) / 2f,
                    (index / 5) * size + (size - cell.height) / 2f, null)
            }
        }
        val output = File("build/reports/bus-marker-preview.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
