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
class AlertPreferencesTest {
    private val application get() = RuntimeEnvironment.getApplication()

    private fun alert(spare: Int = AlertPreferences.DEFAULT_SPARE, startedAt: Long = System.currentTimeMillis()) =
        BusAlert("5", "gtt:5", "p", "Centro", "bus-1", "s", "Stop", 45.0, 7.6,
            spareMinutes = spare, startedAt = startedAt)

    @Before fun reset() {
        application.getSharedPreferences("bus_alert_settings", 0).edit().clear().commit()
        AlertPreferences.clear(application)
    }

    @Test fun defaultsToOneMinuteSpare() = assertEquals(1, AlertPreferences.spareMinutes(application))

    @Test fun theSpareChoiceSurvivesAnAlertEnding() {
        AlertPreferences.setSpareMinutes(application, 10)
        AlertPreferences.clear(application)
        assertEquals(10, AlertPreferences.spareMinutes(application))
    }

    @Test fun onlyOfferedSpareTimesAreAccepted() {
        AlertPreferences.setSpareMinutes(application, 5)
        AlertPreferences.setSpareMinutes(application, 7)
        assertEquals(5, AlertPreferences.spareMinutes(application))
    }

    @Test fun anAlertKeepsItsBusAndSpareTimeAcrossSaveAndLoad() {
        AlertPreferences.save(application, alert(spare = 5))
        val loaded = AlertPreferences.load(application)!!
        assertEquals(5, loaded.spareMinutes)
        assertEquals("bus-1", loaded.vehicleId)
        assertEquals("s", loaded.stopId)
    }

    @Test fun anAlertLeftRunningForHoursIsNotBroughtBack() {
        AlertPreferences.save(application,
            alert(startedAt = System.currentTimeMillis() - AlertPreferences.MAX_AGE_MILLIS - 1))
        assertNull(AlertPreferences.load(application))
    }

    @Test fun aFreshAlertIsKept() {
        AlertPreferences.save(application, alert())
        assertNotNull(AlertPreferences.load(application))
    }
}
