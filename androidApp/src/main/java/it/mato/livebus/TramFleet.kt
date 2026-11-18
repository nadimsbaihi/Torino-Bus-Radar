package it.mato.livebus

/**
 * Which vehicles are trams, by the fleet number GTT paints on them. Neither GTT's nor the MATO live
 * feed says what kind of vehicle a position is from, but the numbers fall apart cleanly: on 18
 * November 2026 every vehicle on a tram line numbered 2800-2899, 5000-5099, 6000-6099 or 8000-8099
 * was a tram, and the buses numbered 0-3999, 9000 and up, never 4000-8999. A line says what it is
 * scheduled with; the number says what actually turned up, so a replacement bus on a tram line is
 * not drawn as a tram.
 */
internal object TramFleet {
    private val TRAMS = listOf(2800..2899, 5000..8999)
    private val BUSES = listOf(0..2799, 2900..3999, 9000..99_999)

    /**
     * True for a tram's number, false for a bus's, null for a number seen on neither (or a vehicle
     * with no number), which leaves the line to decide. [vehicle] is a feed's id, "5038U" or "5038".
     */
    fun isTram(vehicle: String): Boolean? {
        val number = vehicle.trim().removeSuffix("U").toIntOrNull() ?: return null
        return when {
            TRAMS.any { number in it } -> true
            BUSES.any { number in it } -> false
            else -> null
        }
    }
}
