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
class MarkerColorTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        context.getSharedPreferences("map_colors", 0).edit().clear().commit()
    }

    @Test fun blueBusesAndAPurpleRiderUntilTheRiderChooses() {
        assertEquals(MapColors(MarkerColor.BLUE, MarkerColor.PURPLE), MapColors.load(context))
    }

    @Test fun bothChoicesAreRemembered() {
        MapColors.save(context, MapColors(MarkerColor.RED, MarkerColor.GRAPHITE))
        assertEquals(MapColors(MarkerColor.RED, MarkerColor.GRAPHITE), MapColors.load(context))
    }

    @Test fun theRiderIsNeverTheBusesColour() {
        MapColors.BUS_OPTIONS.forEach { assertTrue(MapColors.clash(it, it)) }
        assertTrue(MapColors.clash(MarkerColor.BLUE, MarkerColor.MAPS_BLUE))
        // A clash stored somehow (an older version, say) falls back to the default pair.
        context.getSharedPreferences("map_colors", 0).edit()
            .putString("buses", "PURPLE").putString("position", "PURPLE").commit()
        assertEquals(MapColors.DEFAULT, MapColors.load(context))
    }

    @Test fun choosingOnlyTheBusesGivesTheRiderAColourApart() {
        context.getSharedPreferences("map_colors", 0).edit().putString("buses", "ORANGE").commit()
        assertEquals(MapColors(MarkerColor.ORANGE, MarkerColor.PURPLE), MapColors.load(context))
        // Purple buses would wear the rider's default colour, so the rider moves to the next one.
        context.getSharedPreferences("map_colors", 0).edit().putString("buses", "PURPLE").commit()
        assertEquals(MapColors(MarkerColor.PURPLE, MarkerColor.MAPS_BLUE), MapColors.load(context))
    }
}
