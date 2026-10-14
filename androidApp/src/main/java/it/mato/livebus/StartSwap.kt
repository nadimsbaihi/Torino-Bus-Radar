package it.mato.livebus

/** Swapping where a journey starts with where it ends. */
object StartSwap {
    data class Place(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        /** The rider's own position, which has no fixed spot to carry as a start. */
        val isMyLocation: Boolean = false
    )

    /**
     * The new start (null meaning My location) and destination once [start] and [destination]
     * trade places. A null [start] is My location, which becomes the destination as [here], the
     * current fix; null comes back when that fix is not to hand. Swapping twice restores the journey.
     */
    fun swap(start: Place?, destination: Place, here: Place?): Pair<Place?, Place>? {
        val newDestination = start ?: here ?: return null
        val newStart = destination.takeUnless { it.isMyLocation }
        return newStart to newDestination
    }
}
