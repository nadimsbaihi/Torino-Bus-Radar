package it.mato.livebus

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
class GeoUriTest {
    private fun point(uri: String) = GeoUri.parse(uri) as GeoTarget.Point

    @Test fun plainCoordinates() {
        assertEquals(GeoTarget.Point(45.07031, 7.6869), point("geo:45.07031,7.6869"))
        assertEquals(GeoTarget.Point(45.07, -7.5), point("geo:45.07,-7.5?z=15"))
    }

    @Test fun zeroZeroWithCoordinatesAndLabelInTheQuery() {
        assertEquals(GeoTarget.Point(45.07, 7.68), point("geo:0,0?q=45.07,7.68(Porta%20Susa)"))
    }

    @Test fun zeroZeroWithAnAddressIsASearch() {
        assertEquals(GeoTarget.Search("Via Roma 1, Torino"), GeoUri.parse("geo:0,0?q=Via%20Roma%201,%20Torino"))
    }

    @Test fun coordinatesWinOverALabelQuery() {
        assertEquals(GeoTarget.Point(45.0, 7.6), point("geo:45.0,7.6?q=Cafe"))
    }

    @Test fun otherSchemesAndNonsenseAreRefused() {
        assertNull(GeoUri.parse("https://example.com"))
        assertNull(GeoUri.parse("geo:"))
        assertNull(GeoUri.parse("geo:95.0,7.6"))
        assertNull(GeoUri.parse("geo:0,0"))
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test fun aGeoLinkPlansAJourneyToThatPoint() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=45.07031,7.68690(Qui)"))

        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()

        val destination = MainActivity::class.java.getDeclaredField("directDestination")
            .apply { isAccessible = true }.get(activity) as GeoPoint
        assertEquals(45.07031, destination.latitude, 1e-9)
        assertEquals(7.6869, destination.longitude, 1e-9)
    }
}
