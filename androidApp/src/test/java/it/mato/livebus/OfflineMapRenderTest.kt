package it.mato.livebus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme
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
        val city = (RuntimeEnvironment.getApplication() as MatoApplication).offlineCity
        val source = MapsForgeTileSource.createFromFiles(arrayOf(city.mapFile),
            AssetsRenderTheme(RuntimeEnvironment.getApplication().assets, "offline/", "map-theme.xml"),
            city.mapCacheKey + "-simple-v1", "it")
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
            val output = File("build/reports/offline-map-preview.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("Offline map preview: ${output.absolutePath}")
        } finally {
            source.dispose()
        }
    }
}
