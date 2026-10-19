package it.mato.livebus

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.infowindow.InfoWindow

/** A live bus on its way to a stop, [etaSeconds] from now. */
data class StopArrival(val vehicleId: String, val route: String, val headsign: String, val etaSeconds: Float)

object StopArrivals {
    const val MAX_SHOWN = 5

    /**
     * The next buses to reach the stop with [stopId], soonest first. Uses the same arrival
     * estimates as the journey planner, and counts only a bus that has the stop still ahead of it.
     */
    fun upcoming(tracked: List<TrackedVehicle>, stopId: String, limit: Int = MAX_SHOWN): List<StopArrival> =
        tracked.mapNotNull { current ->
            // A pattern may call at the stop twice (a loop line); the first call still ahead counts.
            val index = current.pattern.stops.indices
                .firstOrNull { it > current.progress && current.pattern.stops[it].id == stopId }
                ?: return@mapNotNull null
            val eta = current.arrivalSeconds.getOrNull(index)?.takeIf { it.isFinite() } ?: return@mapNotNull null
            StopArrival(current.vehicle.id, current.pattern.route, current.pattern.headsign, eta.coerceAtLeast(0f))
        }
            // A bus about to turn around is tracked twice; its earlier run is the one that matters.
            .groupBy { it.vehicleId }.map { (_, runs) -> runs.minBy { it.etaSeconds } }
            .sortedBy { it.etaSeconds }
            .take(limit)
}

/** The bubble over a tapped stop: the next buses heading there, redrawn as the live feed refreshes. */
class StopArrivalsWindow(mapView: MapView, private val onClosed: () -> Unit) :
    InfoWindow(R.layout.bubble_stop_arrivals, mapView) {
    private val title = mView.findViewById<TextView>(R.id.stopArrivalsTitle)
    private val rows = mView.findViewById<LinearLayout>(R.id.stopArrivalsRows)
    private val empty = mView.findViewById<TextView>(R.id.stopArrivalsEmpty)

    init {
        MapBubble.style(mView)
        mView.setOnClickListener { close() }
    }

    /** A null [arrivals] means they are still being worked out: the title shows, the list waits. */
    fun render(stopName: String, arrivals: List<StopArrival>?) {
        title.text = stopName
        rows.removeAllViews()
        empty.visibility = if (arrivals != null && arrivals.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(mView.context)
        arrivals.orEmpty().forEach { arrival ->
            val row = inflater.inflate(R.layout.item_stop_arrival, rows, false)
            val minutes = ceilMinutes(arrival.etaSeconds)
            row.findViewById<TextView>(R.id.arrivalRoute).text = arrival.route
            row.findViewById<TextView>(R.id.arrivalHeadsign).text = TransitIndex.readableCase(arrival.headsign)
            row.findViewById<TextView>(R.id.arrivalEta).text =
                mView.context.getString(R.string.stop_arrival_eta, minutes)
            row.contentDescription = mView.context.getString(R.string.stop_arrival_row,
                arrival.route, TransitIndex.readableCase(arrival.headsign), minutes)
            rows.addView(row)
        }
    }

    override fun onOpen(item: Any?) = Unit

    override fun onClose() = onClosed()
}
