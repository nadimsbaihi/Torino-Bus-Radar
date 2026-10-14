package it.mato.livebus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.util.MapTileIndex
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineMapRenderTest {
    @Test fun bundledMapRendersTorinoWithoutNetworkTiles() {
        renderPreview(false, "offline-map-preview.png")
    }

    @Test fun nightMapRendersWithADarkBackground() {
        val preview = renderPreview(true, "offline-map-preview-night.png")
        var luminance = 0.0
        var count = 0
        for (py in 0 until 768 step 8) for (px in 0 until 768 step 8) {
            val pixel = preview.getPixel(px, py)
            luminance += (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114) / 255
            count++
        }
        assertTrue("Night map should be dark overall", luminance / count < 0.3)
    }

    @Test fun themeFilesFollowTheNightFlag() {
        assertEquals("map-theme.xml", MapTheme.fileName(false))
        assertEquals("map-theme-night.xml", MapTheme.fileName(true))
        val city = (RuntimeEnvironment.getApplication() as MatoApplication).offlineCity
        assertNotEquals(MapTheme.cacheKey(city, false), MapTheme.cacheKey(city, true))
    }

    @Test @Config(qualifiers = "night") fun nightConfigurationIsDetected() {
        assertTrue(MapTheme.isNight(RuntimeEnvironment.getApplication()))
    }

    @Test @Config(qualifiers = "notnight") fun dayConfigurationIsDetected() {
        assertFalse(MapTheme.isNight(RuntimeEnvironment.getApplication()))
    }

    private fun renderPreview(night: Boolean, fileName: String): Bitmap {
        val city = (RuntimeEnvironment.getApplication() as MatoApplication).offlineCity
        val source = MapsForgeTileSource.createFromFiles(arrayOf(city.mapFile),
            MapTheme.renderTheme(RuntimeEnvironment.getApplication(), night),
            MapTheme.cacheKey(city, night) + "-test", "it")
        try {
            assertTrue(source.bounds.contains(45.0692, 7.6560))
            val zoom = 16
            val scale = 2.0.pow(zoom)
            val x = floor((7.6560 + 180) / 360 * scale).toInt()
            val latitude = Math.toRadians(45.0692)
            val y = floor((1 - asinh(tan(latitude)) / PI) / 2 * scale).toInt()
            val preview = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(preview)
            for (row in 0..2) for (column in 0..2) {
                val tile = source.renderTile(MapTileIndex.getTileIndex(zoom, x + column - 1, y + row - 1))
                assertTrue(tile is BitmapDrawable)
                canvas.drawBitmap((tile as BitmapDrawable).bitmap, column * 256f, row * 256f, null)
            }
            val colours = mutableSetOf<Int>()
            for (py in 0 until 768 step 8) for (px in 0 until 768 step 8) colours.add(preview.getPixel(px, py))
            assertTrue("Rendered tiles must contain map detail, not a blank background", colours.size > 20)
            val output = File("build/reports/$fileName")
            output.parentFile!!.mkdirs()
            output.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("Offline map preview: ${output.absolutePath}")
            return preview
        } finally {
            source.dispose()
        }
    }
}
