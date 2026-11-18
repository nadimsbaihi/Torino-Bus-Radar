package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TramFleetTest {
    @Test fun theNumbersTramsCarryAreTrams() {
        // Seen on tram lines on 18 November 2026, in both feeds.
        for (id in listOf("2834", "2893", "5000", "5052", "6010", "6053", "8001", "8051", "5038U", "8051U"))
            assertEquals(id, true, TramFleet.isTram(id))
    }

    @Test fun theNumbersBusesCarryAreBuses() {
        // Including the replacement buses seen on tram lines (811, 850, 9347, 9489, 9512).
        for (id in listOf("54", "811", "850", "1390", "2427", "2799", "2900", "3439", "9000", "9347U", "9512", "14001", "19030U"))
            assertEquals(id, false, TramFleet.isTram(id))
    }

    @Test fun aNumberSeenOnNeitherLeavesItToTheLine() {
        for (id in listOf("4000", "4999U")) assertNull(id, TramFleet.isTram(id))
        for (id in listOf("", "bus", "r4", "gtt:5038")) assertNull(id, TramFleet.isTram(id))
    }
}
