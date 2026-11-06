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
        assertEquals(45.01869, bus.latitude, 0.0)
    }

    @Test fun theSameBusFromBothFeedsIsDrawnOnce() {
        val mato = MatoLiveFeed.parse("/2/9633", "[45.01869,7.66063,null,null,28513318,1,159]", 1_000)!!
        val gtt = LiveVehicle("9633U", "2U", "28513318U", 45.01884, 7.65984, null, 990, "9633")
        assertEquals(listOf(mato), GttRepository.mergePositions(listOf(mato), listOf(gtt)))
    }

    @Test fun aBusWithNoFixIsLeftOut() {
        assertNull(MatoLiveFeed.parse("/2/9720", "[0,0,null,null,28513258,1,159]", 1_000))
        assertNull(MatoLiveFeed.parse("/2/9720", "[null,null,null,null,28513258,1,159]", 1_000))
    }
}
