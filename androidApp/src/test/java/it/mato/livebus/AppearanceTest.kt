package it.mato.livebus

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class AppearanceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test fun followsThePhoneUntilTheRiderChooses() {
        assertEquals(Appearance.SYSTEM, Appearance.load(context))
    }

    @Test fun savedChoiceIsRemembered() {
        Appearance.save(context, Appearance.DARK)
        assertEquals(Appearance.DARK, Appearance.load(context))
        Appearance.save(context, Appearance.LIGHT)
        assertEquals(Appearance.LIGHT, Appearance.load(context))
    }

    @Test fun unknownStoredValueFallsBackToSystem() {
        context.getSharedPreferences("appearance", 0).edit().putString("mode", "SEPIA").commit()
        assertEquals(Appearance.SYSTEM, Appearance.load(context))
    }

    @Test fun applyingSetsTheNightModeOfTheApp() {
        Appearance.save(context, Appearance.DARK)
        Appearance.apply(context)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
        Appearance.save(context, Appearance.LIGHT)
        Appearance.apply(context)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
        Appearance.save(context, Appearance.SYSTEM)
        Appearance.apply(context)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode())
    }
}
