package it.mato.livebus

import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Test

class TripHintsTest {
    private fun update(trip: String, start: String, vehicle: String?) = GtfsRealtime.FeedEntity.newBuilder()
        .setId(trip)
        .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId(trip).setStartTime(start).setStartDate("20261015"))
            .apply { vehicle?.let { setVehicle(GtfsRealtime.VehicleDescriptor.newBuilder().setId(it)) } })
        .build()

    @Test fun eachVehicleGetsItsTripsSoonestFirst() {
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addEntity(update("next", "11:10:00", "6013U"))
            .addEntity(update("now", "09:56:00", "6013U"))
            .addEntity(update("other", "10:00:00", "9506U"))
            // An update that names no vehicle cannot be tied to a position.
            .addEntity(update("loose", "10:00:00", null))
            .build()

        assertEquals(mapOf("6013U" to listOf("now", "next"), "9506U" to listOf("other")), GttRepository.tripHints(feed))
    }
}
