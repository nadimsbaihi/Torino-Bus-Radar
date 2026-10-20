package it.mato.livebus

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.StringRes

/** A colour for the buses or the rider's own dot, with a shade for the dark map. */
enum class MarkerColor(@StringRes val label: Int, @ColorInt val day: Int, @ColorInt val night: Int) {
    BLUE(R.string.marker_color_blue, 0xFF1565C0.toInt(), 0xFF2F7BE0.toInt()),
    // The blue map apps use for "you are here", so the dot reads as the rider at once.
    MAPS_BLUE(R.string.marker_color_maps_blue, 0xFF4285F4.toInt(), 0xFF4285F4.toInt()),
    ORANGE(R.string.marker_color_orange, 0xFFEF6C00.toInt(), 0xFFEF6C00.toInt()),
    PURPLE(R.string.marker_color_purple, 0xFF8E24AA.toInt(), 0xFFBA68C8.toInt()),
    RED(R.string.marker_color_red, 0xFFD32F2F.toInt(), 0xFFEF5350.toInt()),
    // A neutral among the hues, for anyone who wants the colour kept for the bus to catch.
    GRAPHITE(R.string.marker_color_graphite, 0xFF37474F.toInt(), 0xFF607D8B.toInt());

    fun color(night: Boolean) = if (night) this.night else day
}

/**
 * The buses' colour and the rider's, each the rider's choice among hues far apart on the colour
 * wheel (blue, orange, purple, red) and a neutral graphite. Pale colours such as gold are not offered
 * (they fade on the light map), and every colour keeps a bus's white number readable.
 */
data class MapColors(val buses: MarkerColor, val position: MarkerColor) {
    companion object {
        val BUS_OPTIONS = listOf(MarkerColor.BLUE, MarkerColor.ORANGE, MarkerColor.PURPLE, MarkerColor.RED, MarkerColor.GRAPHITE)
        val POSITION_OPTIONS = listOf(MarkerColor.MAPS_BLUE, MarkerColor.ORANGE, MarkerColor.PURPLE, MarkerColor.RED, MarkerColor.GRAPHITE)
        val DEFAULT = MapColors(MarkerColor.BLUE, MarkerColor.PURPLE)

        private const val STORE = "map_colors"

        /** Colours too alike to tell the rider from a bus: the same one, or the two blues. */
        fun clash(buses: MarkerColor, position: MarkerColor) = buses == position ||
            (buses == MarkerColor.BLUE && position == MarkerColor.MAPS_BLUE)

        fun load(context: Context): MapColors {
            val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val buses = BUS_OPTIONS.firstOrNull { it.name == store.getString("buses", null) } ?: DEFAULT.buses
            val position = POSITION_OPTIONS.firstOrNull { it.name == store.getString("position", null) }
                ?: DEFAULT.position.takeUnless { clash(buses, it) } ?: POSITION_OPTIONS.first { !clash(buses, it) }
            return if (clash(buses, position)) DEFAULT else MapColors(buses, position)
        }

        fun save(context: Context, colors: MapColors) {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putString("buses", colors.buses.name).putString("position", colors.position.name).apply()
        }
    }
}
