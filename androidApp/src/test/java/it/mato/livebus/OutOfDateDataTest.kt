package it.mato.livebus

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OutOfDateDataTest {
    private val stops = listOf(TransitStop("A", "A", 45.0, 7.0), TransitStop("B", "B", 45.02, 7.0))
    private val line = TransitPattern("L", "L", "L", "End", "0", setOf("known"), stops)
    private val index: TransitIndex =
        TransitIndex::class.java.getDeclaredConstructor(List::class.java).apply { isAccessible = true }
            .newInstance(listOf(line))

    private fun buses(known: Int, unknown: Int, withoutTrip: Int = 0) =
        List(known) { LiveVehicle("k$it", "L", "known", 45.01, 7.0, null, 0) } +
            List(unknown) { LiveVehicle("u$it", "L", "new$it", 45.01, 7.0, null, 0) } +
            List(withoutTrip) { LiveVehicle("n$it", "L", null, 45.01, 7.0, null, 0) }

    @Test fun mostlyUnknownTripsMeanTheDataIsOld() = assertTrue(index.looksOutOfDate(buses(known = 3, unknown = 27)))

    @Test fun aFewStrangersAreNormal() = assertFalse(index.looksOutOfDate(buses(known = 27, unknown = 3)))

    @Test fun tooFewBusesSayNothing() = assertFalse(index.looksOutOfDate(buses(known = 0, unknown = 5)))

    @Test fun busesWithoutATripDoNotCount() =
        assertFalse(index.looksOutOfDate(buses(known = 20, unknown = 2, withoutTrip = 100)))
}
