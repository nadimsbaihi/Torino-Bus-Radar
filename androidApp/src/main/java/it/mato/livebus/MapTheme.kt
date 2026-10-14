package it.mato.livebus

import android.content.Context
import android.content.res.Configuration
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme

/** The offline map's look follows the interface: a light render theme by day, a dark one at night. */
object MapTheme {
    fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    fun fileName(night: Boolean) = if (night) "map-theme-night.xml" else "map-theme.xml"

    /** Tiles of the two themes are cached apart, so switching never shows the other one's tiles. */
    fun cacheKey(city: OfflineCity, night: Boolean) =
        city.mapCacheKey + (if (night) "-night-v1" else "-simple-v3")

    fun renderTheme(context: Context, night: Boolean) =
        AssetsRenderTheme(context.assets, "offline/", fileName(night))
}
