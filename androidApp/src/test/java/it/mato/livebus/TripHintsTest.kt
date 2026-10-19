package it.mato.livebus

import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun progress(trip: String, start: String, vehicle: String, next: Int, at: Long) =
        GtfsRealtime.FeedEntity.newBuilder().setId(trip + vehicle)
            .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId(trip).setStartTime(start).setStartDate("20261019"))
                .setVehicle(GtfsRealtime.VehicleDescriptor.newBuilder().setId(vehicle).setLabel(vehicle.removeSuffix("U")))
                .setTimestamp(at)
                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder().setStopSequence(next + 1))
                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder().setStopSequence(next)))
            .build()

    @Test fun busesMissingFromThePositionsComeFromTheTripUpdates() {
        val now = 1_792_417_000L
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(now))
            // On its way to stop 10 of the trip it is running; its next run does not count yet.
            .addEntity(progress("now", "15:28:00", "9645U", 10, now - 20))
            .addEntity(progress("later", "16:10:00", "9645U", 1, now - 20))
            // Already has a real position.
            .addEntity(progress("seen", "15:00:00", "858U", 7, now - 20))
            // Not yet off its first stop: nothing says where it is.
            .addEntity(progress("waiting", "15:45:00", "700U", 1, now - 20))
            // An update too old to place a bus with.
            .addEntity(progress("old", "14:00:00", "701U", 5, now - 600))
            .build()

        val unplaced = GttRepository.unplaced(feed, setOf("858U"), now)

        assertEquals(listOf("9645U"), unplaced.map { it.id })
        val bus = unplaced.single()
        assertEquals("now", bus.tripId)
        assertEquals(10, bus.nextStopSequence)
        assertEquals("9645", bus.label)
        assertTrue(bus.estimated)
    }
}
