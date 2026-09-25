package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class LocalizationTest {
    @Test
    @Config(qualifiers = "it")
    fun italianDeviceLocaleUsesItalianStringsAndPlurals() {
        val context = RuntimeEnvironment.getApplication()

        assertEquals("Quanto vuoi camminare?", context.getString(R.string.walking_label))
        assertEquals("Poco ▾", context.getString(R.string.walking_preset_less))
        assertEquals("A piedi · ~12 min", context.getString(R.string.walk_summary, 12))
        assertEquals("A piedi · 12 min ↗", context.getString(R.string.walk_option, 12))
        assertEquals("1 veicolo in tempo reale sulla linea 4",
            context.resources.getQuantityString(R.plurals.live_vehicle_count, 1, 1, "4"))
        assertEquals("2 veicoli in tempo reale sulla linea 4",
            context.resources.getQuantityString(R.plurals.live_vehicle_count, 2, 2, "4"))
    }

    @Test
    @Config(qualifiers = "fr")
    fun unsupportedDeviceLocaleUsesEnglishFallback() {
        val context = RuntimeEnvironment.getApplication()

        assertEquals("Your journey", context.getString(R.string.your_journey))
        assertEquals("Walk · ~12 min", context.getString(R.string.walk_summary, 12))
    }
}
