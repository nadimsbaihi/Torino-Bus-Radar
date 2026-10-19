package it.mato.livebus

import android.view.View
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow

/** The bubble over a spot pressed and held on the map: the question, and the answer to it. */
internal class SpotInfoWindow(
    mapView: MapView,
    onGo: () -> Unit,
    onCancel: () -> Unit
) : MarkerInfoWindow(R.layout.bubble_go_to_spot, mapView) {
    init {
        MapBubble.style(view)
        view.findViewById<View>(R.id.spotGo).setOnClickListener { onGo() }
        view.findViewById<View>(R.id.spotCancel).setOnClickListener { onCancel() }
        // The stock bubble closes when touched; here that would leave the pin with no question.
        view.setOnTouchListener { _, _ -> true }
    }
}
