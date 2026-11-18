package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MatoLiveFeedTest {
    @Test fun aBusIsNamedAsGttNamesIt() {
        // As heard on the live feed; GTT's own feed has this bus as "9633U" on trip "28513318U".
        val bus = MatoLiveFeed.parse("/2/9633", "[45.01869,7.66063,null,null,28513318,1,159]", 1_000)!!
        assertEquals("9633U", bus.id)
        assertEquals("9633", bus.label)
        assertEquals("2U", bus.routeId)
        assertEquals("28513318U", bus.tripId)
        assertNull(bus.bearing)
        assertEquals("1", bus.directionId)
        assertEquals("159", bus.nextStopCode)
        assertEquals(45.01869, bus.latitude, 0.0)
    }

    @Test fun theSameBusFromBothFeedsIsDrawnOnce() {
        val mato = MatoLiveFeed.parse("/2/9633", "[45.01869,7.66063,null,null,28513318,1,159]", 1_000)!!
        val gtt = LiveVehicle("9633U", "2U", "28513318U", 45.01884, 7.65984, null, 990, "9633")
        assertEquals(listOf(mato), GttRepository.mergePositions(listOf(mato), listOf(gtt)))
    }

    @Test fun theWebsitesDirectionAndNextStopStayOnlyWithItsOwnTrip() {
        val mato = MatoLiveFeed.parse("/2/9633", "[45.01869,7.66063,null,null,28513318,1,159]", 1_000)!!
        val sameTrip = LiveVehicle("9633U", "2U", "28513318U", 45.019, 7.660, null, 1_010, "9633")
        assertEquals("159", GttRepository.mergePositions(listOf(mato), listOf(sameTrip)).single().nextStopCode)
        val otherTrip = sameTrip.copy(tripId = "28513400U")
        val merged = GttRepository.mergePositions(listOf(mato), listOf(otherTrip)).single()
        assertEquals("28513400U", merged.tripId)
        assertNull(merged.directionId)
        assertNull(merged.nextStopCode)
    }

    @Test fun aBusWithNoFixIsLeftOut() {
        assertNull(MatoLiveFeed.parse("/2/9720", "[0,0,null,null,28513258,1,159]", 1_000))
        assertNull(MatoLiveFeed.parse("/2/9720", "[null,null,null,null,28513258,1,159]", 1_000))
    }

    /** An MQTT PUBLISH packet for [payload] on [topic], flagged as retained when the broker replays it on subscribing. */
    private fun publish(topic: String, payload: String, retained: Boolean): ByteArray {
        val topicBytes = topic.toByteArray()
        val body = byteArrayOf((topicBytes.size shr 8).toByte(), topicBytes.size.toByte()) + topicBytes + payload.toByteArray()
        require(body.size < 128)
        return byteArrayOf((0x30 or if (retained) 1 else 0).toByte(), body.size.toByte()) + body
    }

    private fun feed() = MatoLiveFeed(okhttp3.OkHttpClient())

    @Test fun aRetainedMessageIsNotAFreshPosition() {
        val feed = feed()
        // What the broker replays on subscribing: the last message of every topic, however long ago.
        feed.receive(publish("/4/9473", "[45.07,7.68,90,10,28518779,1,620]", retained = true))
        assertEquals(emptyList<LiveVehicle>(), feed.vehicles())
        // The same bus then reports for real.
        feed.receive(publish("/4/9473", "[45.08,7.69,90,10,28518779,1,621]", retained = false))
        assertEquals(listOf("9473U"), feed.vehicles().map { it.id })
        assertEquals(45.08, feed.vehicles().single().latitude, 0.0)
    }

    @Test fun aRetainedMessageDoesNotReplaceALiveOne() {
        val feed = feed()
        feed.receive(publish("/4/5038", "[45.08,7.69,90,10,28518779,1,621]", retained = false))
        feed.receive(publish("/4/5038", "[45.00,7.00,90,10,28518779,1,100]", retained = true))
        assertEquals(45.08, feed.vehicles().single().latitude, 0.0)
    }

    @Test fun aPacketCutInTwoIsStillRead() {
        val feed = feed()
        val packet = publish("/67/9468", "[45.07,7.68,null,null,28518000,0,55]", retained = false)
        feed.receive(packet.copyOfRange(0, 9))
        assertEquals(emptyList<LiveVehicle>(), feed.vehicles())
        feed.receive(packet.copyOfRange(9, packet.size))
        assertEquals(listOf("9468U"), feed.vehicles().map { it.id })
    }
}
