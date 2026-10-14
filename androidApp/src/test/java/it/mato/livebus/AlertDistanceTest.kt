package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class AlertDistanceTest {
    private val application get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        application.getSharedPreferences("bus_alert_settings", 0).edit().clear().commit()
        AlertPreferences.clear(application)
    }

    @Test fun defaultsToSevenHundredMetres() = assertEquals(700, AlertPreferences.distance(application))

    @Test fun theChoiceSurvivesAnAlertEnding() {
        AlertPreferences.setDistance(application, 300)
        AlertPreferences.clear(application)
        assertEquals(300, AlertPreferences.distance(application))
    }

    @Test fun onlyOfferedDistancesAreAccepted() {
        AlertPreferences.setDistance(application, 1000)
        AlertPreferences.setDistance(application, 42)
        assertEquals(1000, AlertPreferences.distance(application))
    }

    @Test fun anAlertKeepsItsThresholdAcrossSaveAndLoad() {
        AlertPreferences.save(application, BusAlert("5", "gtt:5", "p", "Centro", null, "s", "Stop", 45.0, 7.6,
            thresholdMetres = 500))
        assertEquals(500, AlertPreferences.load(application)!!.thresholdMetres)
    }

    @Test fun anAlertLeftRunningForHoursIsNotBroughtBack() {
        AlertPreferences.save(application, BusAlert("5", "gtt:5", "p", "Centro", null, "s", "Stop", 45.0, 7.6,
            startedAt = System.currentTimeMillis() - AlertPreferences.MAX_AGE_MILLIS - 1))
        assertNull(AlertPreferences.load(application))
    }

    @Test fun aFreshAlertIsKept() {
        AlertPreferences.save(application, BusAlert("5", "gtt:5", "p", "Centro", null, "s", "Stop", 45.0, 7.6))
        assertNotNull(AlertPreferences.load(application))
    }
}
