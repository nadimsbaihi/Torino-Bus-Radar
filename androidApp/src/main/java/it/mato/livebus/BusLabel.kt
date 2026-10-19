package it.mato.livebus

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** How sure the map is of a bus's place: a fresh fix, a late one, or only estimated from its trip. */
internal enum class BusLook { LIVE, LATE, ESTIMATED }

/** A line's name as it is set inside its round bus marker: "16 CD" and "STAR 1" take two lines. */
internal fun busLabelLines(route: String): List<String> =
    route.trim().split(Regex("\\s+"), limit = 2).filter(String::isNotEmpty).ifEmpty { listOf(route) }

/** Text size, in dp, that [lines] are drawn at; stacked names are smaller than a lone number. */
internal fun busLabelTextDp(closest: Boolean, lines: List<String>): Float = when {
    lines.size > 1 -> if (closest) 10f else 8f
    closest -> 12f
    lines.single().length > 2 -> 8f
    else -> 10f
}

/**
 * Radius of the circle that just holds [lineWidths], stacked one [textSize] apart and centred:
 * the farthest corner of any line's box. The marker grows to this rather than shrinking the text
 * until it cannot be read.
 */
internal fun busLabelRadius(lineWidths: List<Float>, textSize: Float): Float {
    var radius = 0f
    lineWidths.forEachIndexed { index, width ->
        val centre = (index - (lineWidths.size - 1) / 2f) * textSize
        val farEdge = abs(centre) + textSize * CAP_HALF_HEIGHT
        radius = max(radius, hypot(width / 2f, farEdge))
    }
    return radius
}

// Capitals and digits stand about 0.72 em tall.
private const val CAP_HALF_HEIGHT = 0.36f
