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
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val method = MainActivity::class.java.getDeclaredMethod("freshnessSuffix", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        return method.invoke(activity, seconds) as String
    }

    @Test fun aFreshFetchReadsAsPlainLive() = assertEquals("", suffix(2))

    @Test fun secondsThenMinutesTickUp() {
        assertEquals(" · updated 12 s ago", suffix(12))
        assertEquals(" · updated 3 min ago", suffix(190))
    }
}
