package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.MapTileIndex
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class MapLayerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        context.getSharedPreferences("map_layer", 0).edit().clear().commit()
    }

    @Test fun theOfflineStreetMapUntilTheRiderChooses() {
        assertEquals(MapLayer.STREETS, MapLayer.load(context))
    }

    @Test fun chosenLayerIsRemembered() {
        MapLayer.save(context, MapLayer.HYBRID)
        assertEquals(MapLayer.HYBRID, MapLayer.load(context))
    }

    @Test fun photoAddressesPutTheRowBeforeTheColumn() {
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/16/23517/34167",
            MapLayer.PHOTOS.getTileURLString(MapTileIndex.getTileIndex(16, 34167, 23517)))
    }
}
