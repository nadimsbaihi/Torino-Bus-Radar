package it.mato.livebus

import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
class PlaceShortcutsTest {
    private val application get() = RuntimeEnvironment.getApplication()

    @Before fun startEmpty() {
        application.getSharedPreferences("saved_places", 0).edit().clear().commit()
        application.getSharedPreferences("active_journey", 0).edit().clear().commit()
    }

    @Test fun onlyTheFirstFourPlacesGetAShortcutAndTheyFollowTheSavedList() {
        val saved = SavedPlaces(application)
        listOf("A", "B", "C", "D", "E").forEachIndexed { i, name ->
            saved.save(SavedPlace(name, name, "", 45.0 + i * 0.01, 7.6))
        }
        assertEquals(listOf("place:A", "place:B", "place:C", "place:D"),
            ShortcutManagerCompat.getDynamicShortcuts(application).map { it.id }.sorted())

        saved.remove(saved.byName("A")!!)
        assertEquals(listOf("place:B", "place:C", "place:D", "place:E"),
            ShortcutManagerCompat.getDynamicShortcuts(application).map { it.id }.sorted())
    }

    @Test fun theShortcutIntentCarriesThePlace() {
        val intent = PlaceShortcuts.intentFor(application, SavedPlace("a", "Casa", "", 45.05, 7.65))
        assertEquals(PlaceShortcuts.ACTION_GO_TO_PLACE, intent.action)
        assertEquals("Casa", intent.getStringExtra(PlaceShortcuts.EXTRA_NAME))
        assertEquals(45.05, intent.getDoubleExtra(PlaceShortcuts.EXTRA_LATITUDE, 0.0), 1e-9)
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test fun openingAShortcutPlansAJourneyToThePlace() {
        val place = SavedPlace("a", "Casa", "", 45.05, 7.65)
        SavedPlaces(application).save(place)
        val intent = PlaceShortcuts.intentFor(application, place)

        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()

        val destination = MainActivity::class.java.getDeclaredField("directDestination")
            .apply { isAccessible = true }.get(activity) as GeoPoint
        assertEquals(45.05, destination.latitude, 1e-9)
    }
}
