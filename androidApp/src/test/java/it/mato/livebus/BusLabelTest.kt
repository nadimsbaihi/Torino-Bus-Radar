package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusLabelTest {
    @Test fun aNameWithASpaceTakesTwoLines() {
        assertEquals(listOf("16", "CD"), busLabelLines("16 CD"))
        assertEquals(listOf("STAR", "1"), busLabelLines("STAR 1"))
    }

    @Test fun aNameInOnePieceStaysOnOneLine() {
        assertEquals(listOf("60"), busLabelLines("60"))
        assertEquals(listOf("10N"), busLabelLines("10N"))
        assertEquals(listOf("59/"), busLabelLines("59/"))
    }

    @Test fun stackedNamesAreSetSmallerThanALoneNumber() {
        assertTrue(busLabelTextDp(false, listOf("16", "CD")) < busLabelTextDp(false, listOf("60")))
        assertTrue(busLabelTextDp(true, listOf("STAR", "1")) < busLabelTextDp(true, listOf("60")))
    }

    @Test fun theMarkerGrowsWithTheWidestLineAndWithEveryExtraLine() {
        val size = 8f
        val oneShort = busLabelRadius(listOf(8f), size)
        assertTrue(busLabelRadius(listOf(20f), size) > oneShort)
        assertTrue(busLabelRadius(listOf(8f, 8f), size) > oneShort)
    }

    @Test fun everyCornerOfEveryLineLiesWithinTheRadius() {
        val size = 8f
        val widths = listOf(19f, 4f)
        val radius = busLabelRadius(widths, size)
        widths.forEachIndexed { index, width ->
            val centre = (index - 0.5f) * size
            val corner = Math.hypot((width / 2f).toDouble(), (Math.abs(centre) + size * 0.36f).toDouble())
            assertTrue(corner <= radius + 1e-4)
        }
    }
}
