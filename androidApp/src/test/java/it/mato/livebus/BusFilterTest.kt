package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class BusFilterTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        context.getSharedPreferences("bus_filter", 0).edit().clear().commit()
    }

    @Test fun everyLineShowsUntilTheRiderHidesOne() {
        assertTrue(BusFilter.hidden(context).isEmpty())
    }

    @Test fun hiddenLinesAreRemembered() {
        BusFilter.saveHidden(context, setOf("4", "16 CD"))
        assertEquals(setOf("4", "16 CD"), BusFilter.hidden(context))
    }

    @Test fun linesAreListedByNumberThenName() {
        assertEquals(listOf("2", "4", "10", "16 CD", "STAR 1"), BusFilter.sorted(listOf("STAR 1", "16 CD", "10", "4", "2", "4")))
    }

    @Test fun aLineAddedLaterShowsAlthoughOthersAreHidden() {
        val shown = BusFilter.shown(listOf("4", "10", "NEW"), setOf("4"))
        assertEquals(listOf("10", "NEW"), shown)
    }

    @Test fun searchingMatchesPartOfAName() {
        val lines = listOf("4", "14", "16 CD", "16N", "STAR 1")
        assertEquals(lines, BusFilter.matching(lines, ""))
        assertEquals(listOf("4", "14"), BusFilter.matching(lines, "4"))
        assertEquals(listOf("16 CD"), BusFilter.matching(lines, "d"))
        assertEquals(listOf("16 CD"), BusFilter.matching(lines, "16cd"))
        assertEquals(listOf("STAR 1"), BusFilter.matching(lines, "star"))
        assertTrue(BusFilter.matching(lines, "zz").isEmpty())
    }
}
