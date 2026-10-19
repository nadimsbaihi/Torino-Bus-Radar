package it.mato.livebus

import android.content.Context
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * What the map is drawn from. The street map ships with the app and works offline; the photos
 * come from Esri's World Imagery as they are looked at, so they need a connection and cost the
 * app nothing to install.
 */
enum class MapLayer(val online: Boolean, val labels: Boolean) {
    STREETS(online = false, labels = false),
    SATELLITE(online = true, labels = false),
    HYBRID(online = true, labels = true);

    companion object {
        private const val STORE = "map_layer"
        private const val KEY = "layer"

        /** Esri's photos are sharp to about this zoom over Torino; closer, there is nothing to draw. */
        const val PHOTO_MAX_ZOOM = 19

        fun load(context: Context): MapLayer {
            val stored = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == stored } ?: STREETS
        }

        fun save(context: Context, layer: MapLayer) {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY, layer.name).apply()
        }

        val PHOTOS: OnlineTileSourceBase = EsriTiles("EsriWorldImagery", "World_Imagery")

        /**
         * Roads and their names over the photos, on a transparent background. Esri's places layer
         * would name every street a second time, so it is left out.
         */
        val PHOTO_LABELS: List<OnlineTileSourceBase> = listOf(
            EsriTiles("EsriTransportation", "Reference/World_Transportation"))
    }
}

/** An Esri tile service; its addresses put the row before the column, unlike osmdroid's default. */
internal class EsriTiles(name: String, service: String) : OnlineTileSourceBase(
    name, 0, MapLayer.PHOTO_MAX_ZOOM, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/"),
    "Esri, Maxar, Earthstar Geographics"
) {
    override fun getTileURLString(pMapTileIndex: Long) = baseUrl +
        "${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
}
