package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
class LiveStatusFreshnessTest {
    private fun suffix(seconds: Long): String {
        // Without the first-visit tips: under the legacy looper their card's bottom inset re-enters
        // the coordinator's pre-draw, which a phone never does.
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("active_journey", 0).edit()
            .putBoolean("hints_seen", true).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val method = MainActivity::class.java.getDeclaredMethod("freshnessSuffix", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        return method.invoke(activity, seconds) as String
    }

    @Test fun aFreshFetchReadsAsPlainLive() {
        assertEquals("", suffix(2))
        // Between two routine refreshes the label stays still.
        assertEquals("", suffix(20))
    }

    @Test fun secondsThenMinutesTickUp() {
        assertEquals(" · updated 45 s ago", suffix(45))
        assertEquals(" · updated 3 min ago", suffix(190))
    }
}
