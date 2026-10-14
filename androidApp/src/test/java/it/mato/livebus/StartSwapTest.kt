package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartSwapTest {
    private val home = StartSwap.Place("Casa", 45.0, 7.0)
    private val work = StartSwap.Place("Lavoro", 45.1, 7.1)
    private val here = StartSwap.Place("My location", 45.05, 7.05, isMyLocation = true)

    @Test fun fromMyLocationTheDestinationBecomesTheStartAndTheFixTheDestination() {
        val (start, destination) = StartSwap.swap(null, work, here)!!

        assertEquals(work, start)
        assertEquals(here, destination)
        assertTrue(destination.isMyLocation)
    }

    @Test fun swappingBackRestoresMyLocationAndTheOriginalDestination() {
        val (start, destination) = StartSwap.swap(null, work, here)!!

        val (backStart, backDestination) = StartSwap.swap(start, destination, null)!!

        assertNull(backStart)
        assertEquals(work, backDestination)
    }

    @Test fun twoPlacesSimplyTradeRoles() {
        val (start, destination) = StartSwap.swap(home, work, null)!!

        assertEquals(work, start)
        assertEquals(home, destination)
        assertFalse(destination.isMyLocation)
    }

    @Test fun withoutAFixMyLocationCannotBecomeTheDestination() {
        assertNull(StartSwap.swap(null, work, null))
    }
}
