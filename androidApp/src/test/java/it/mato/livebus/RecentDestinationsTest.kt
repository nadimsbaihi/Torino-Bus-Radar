package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class RecentDestinationsTest {
    private lateinit var recents: RecentDestinations

    @Before fun startEmpty() {
        RuntimeEnvironment.getApplication().getSharedPreferences("recent_destinations", 0).edit().clear().commit()
        recents = RecentDestinations(RuntimeEnvironment.getApplication())
    }

    @Test fun newestComesFirstAndSurvivesRestart() {
        recents.add("Porta Nuova", 45.062, 7.678)
        recents.add("Lingotto", 45.03, 7.67)

        val reopened = RecentDestinations(RuntimeEnvironment.getApplication()).all()
        assertEquals(listOf("Lingotto", "Porta Nuova"), reopened.map { it.name })
        assertEquals(45.062, reopened[1].latitude, 1e-9)
    }

    @Test fun theSamePlaceMovesToTheFrontInsteadOfRepeating() {
        recents.add("Porta Nuova", 45.062, 7.678)
        recents.add("Lingotto", 45.03, 7.67)
        recents.add("porta  nuova", 45.062, 7.678)
        // The same doorway under another spelling is the same place too.
        recents.add("Stazione", 45.03, 7.67)

        assertEquals(listOf("Stazione", "porta nuova"), recents.all().map { it.name })
    }

    @Test fun onlyTheLastEightAreKept() {
        for (i in 1..12) recents.add("Place $i", 45.0 + i * 0.01, 7.6)

        val names = recents.all().map { it.name }
        assertEquals(RecentDestinations.MAX, names.size)
        assertEquals("Place 12", names.first())
        assertEquals("Place 5", names.last())
    }

    @Test fun blankNamesAreIgnored() {
        recents.add("   ", 45.0, 7.6)
        assertEquals(emptyList<OfflinePlace>(), recents.all())
    }
}
