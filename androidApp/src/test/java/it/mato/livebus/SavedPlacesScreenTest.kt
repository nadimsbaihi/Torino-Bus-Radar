package it.mato.livebus

import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import it.mato.livebus.databinding.ActivityMainBinding
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
class SavedPlacesScreenTest {
    @Before fun startEmpty() {
        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("saved_places", 0).edit().clear().commit()
        // The first-visit tips are not what these screens are about; under the legacy looper the
        // card's bottom inset also re-enters the coordinator's pre-draw, which a phone never does.
        application.getSharedPreferences("active_journey", 0).edit().clear()
            .putBoolean("hints_seen", true).commit()
    }

    private fun screen(): Pair<MainActivity, ActivityMainBinding> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val binding = MainActivity::class.java.getDeclaredField("binding")
            .apply { isAccessible = true }.get(activity) as ActivityMainBinding
        return activity to binding
    }

    private fun dialog(): AlertDialog =
        org.robolectric.shadows.ShadowDialog.getLatestDialog() as AlertDialog

    private fun invoke(activity: MainActivity, name: String, vararg args: Any) {
        val method = MainActivity::class.java.declaredMethods.single { it.name == name }
        method.isAccessible = true
        method.invoke(activity, *args)
    }

    @Test fun bookmarkButtonNamesTheDestinationAndTheNameThenResolves() {
        val (activity, binding) = screen()
        val comala = (RuntimeEnvironment.getApplication() as MatoApplication).offlineCity.search("Comala").first()
        binding.destinationInput.setText(comala.name, false)
        // What choosing Comala from the suggestions leaves behind.
        MainActivity::class.java.getDeclaredField("lastGeocodedAddress").apply { isAccessible = true }
            .set(activity, comala.name to (comala.latitude to comala.longitude))
        MainActivity::class.java.getDeclaredField("lastResolvedPlace").apply { isAccessible = true }
            .set(activity, comala)

        binding.savedPlaceButton.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val form = dialog()
        val name = form.findViewById<TextInputEditText>(R.id.savePlaceName)!!
        assertEquals(comala.name, name.text.toString())
        assertTrue(form.findViewById<android.widget.TextView>(R.id.savePlaceAddress)!!.text.contains("Ferrucci"))
        name.setText("Dalla Anna")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        val saved = SavedPlaces(RuntimeEnvironment.getApplication()).byName("Dalla Anna")!!
        assertEquals(comala.latitude, saved.latitude, 1e-9)
        assertEquals("Dalla Anna", binding.destinationInput.text.toString())
        assertEquals(activity.getString(R.string.edit_saved_place), binding.savedPlaceButton.contentDescription)
    }

    @Test fun aNameAlreadyInUseIsRefusedAndTheDialogStaysOpen() {
        SavedPlaces(RuntimeEnvironment.getApplication())
            .save(SavedPlace("a", "Casa", "", 45.05, 7.65))
        val (_, binding) = screen()
        binding.destinationInput.setText("45.07031, 7.68690", false)

        binding.savedPlaceButton.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val form = dialog()
        form.findViewById<TextInputEditText>(R.id.savePlaceName)!!.setText("casa")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        assertTrue(form.isShowing)
        assertEquals(1, SavedPlaces(RuntimeEnvironment.getApplication()).all().size)
    }

    // Opening the journey sheet animates, and the legacy looper never advances the clock that
    // animation needs, so it would settle forever. The paused looper lets it finish.
    @LooperMode(LooperMode.Mode.PAUSED)
    @Test fun longPressOnTheMapStartsAJourneyToThatSpot() {
        val (activity, binding) = screen()

        invoke(activity, "goToMapPoint", GeoPoint(45.07031, 7.68690))

        assertEquals("45.07031, 7.68690", binding.destinationInput.text.toString())
        val destination = MainActivity::class.java.getDeclaredField("directDestination")
            .apply { isAccessible = true }.get(activity) as GeoPoint
        assertEquals(45.07031, destination.latitude, 1e-9)
        assertEquals(View.VISIBLE, binding.journeyCard.visibility)
        assertFalse(binding.findRouteButton.isEnabled && binding.searchProgress.visibility != View.VISIBLE &&
            binding.sharedDirections.text.isNullOrEmpty())
    }

    private fun receiver(binding: ActivityMainBinding): org.osmdroid.events.MapEventsReceiver {
        val overlay = binding.map.overlays.filterIsInstance<org.osmdroid.views.overlay.MapEventsOverlay>().single()
        return overlay.javaClass.getDeclaredField("mReceiver")
            .apply { isAccessible = true }.get(overlay) as org.osmdroid.events.MapEventsReceiver
    }

    private fun longPress(binding: ActivityMainBinding, point: GeoPoint) {
        receiver(binding).longPressHelper(point)
    }

    private fun pins(binding: ActivityMainBinding) =
        binding.map.overlays.filterIsInstance<org.osmdroid.views.overlay.Marker>()

    private fun bubbles(binding: ActivityMainBinding) =
        org.osmdroid.views.overlay.infowindow.InfoWindow.getOpenedInfoWindowsOn(binding.map)

    private fun directDestination(activity: MainActivity) =
        MainActivity::class.java.getDeclaredField("directDestination")
            .apply { isAccessible = true }.get(activity) as GeoPoint?

    @Test fun longPressOnTheMapMarksTheSpotAndAsksThereBeforeGoing() {
        val (activity, binding) = screen()

        longPress(binding, GeoPoint(45.07031, 7.68690))

        // Marked on the map, with the question in a bubble on it, not in a dialog over everything.
        assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        val pin = pins(binding).single()
        assertEquals(45.07031, pin.position.latitude, 1e-9)
        val bubble = bubbles(binding).single().view
        assertEquals(activity.getString(R.string.map_pin_confirm_title),
            bubble.findViewById<android.widget.TextView>(org.osmdroid.library.R.id.bubble_title).text.toString())
        assertEquals("45.07031, 7.68690",
            bubble.findViewById<android.widget.TextView>(org.osmdroid.library.R.id.bubble_description).text.toString())
        // Nothing has started yet.
        assertTrue(binding.destinationInput.text.isNullOrEmpty())
        assertNull(directDestination(activity))
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test fun sayingGoOnTheBubbleStartsAJourneyToThatSpot() {
        val (activity, binding) = screen()
        longPress(binding, GeoPoint(45.07031, 7.68690))

        bubbles(binding).single().view.findViewById<View>(R.id.spotGo).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("45.07031, 7.68690", binding.destinationInput.text.toString())
        assertEquals(45.07031, directDestination(activity)!!.latitude, 1e-9)
        assertTrue("the question is over", bubbles(binding).isEmpty())
    }

    @Test fun sayingCancelOnTheBubbleTakesTheMarkBackAndLeavesTheScreenAlone() {
        val (activity, binding) = screen()
        longPress(binding, GeoPoint(45.07031, 7.68690))

        bubbles(binding).single().view.findViewById<View>(R.id.spotCancel).performClick()

        assertTrue(pins(binding).isEmpty())
        assertTrue(bubbles(binding).isEmpty())
        assertTrue(binding.destinationInput.text.isNullOrEmpty())
        assertNull(directDestination(activity))
    }

    @Test fun tappingElsewhereOnTheMapDismissesTheQuestion() {
        val (activity, binding) = screen()
        longPress(binding, GeoPoint(45.07031, 7.68690))

        assertTrue(receiver(binding).singleTapConfirmedHelper(GeoPoint(45.08, 7.70)))

        assertTrue(pins(binding).isEmpty())
        assertTrue(bubbles(binding).isEmpty())
        assertNull(directDestination(activity))
        // With no question open, a tap is left for the rest of the map.
        assertFalse(receiver(binding).singleTapConfirmedHelper(GeoPoint(45.08, 7.70)))
    }

    @Test fun aSecondLongPressMovesTheMarkAndTheQuestion() {
        val (_, binding) = screen()
        longPress(binding, GeoPoint(45.07031, 7.68690))

        longPress(binding, GeoPoint(45.06, 7.67))

        assertEquals(45.06, pins(binding).single().position.latitude, 1e-9)
        assertEquals(1, bubbles(binding).size)
    }

    @Test fun theQuestionSurvivesTheBusRefresh() {
        val (activity, binding) = screen()
        longPress(binding, GeoPoint(45.07031, 7.68690))

        MainActivity::class.java.getDeclaredMethod("renderVehicles").apply { isAccessible = true }.invoke(activity)

        assertEquals(45.07031, pins(binding).single().position.latitude, 1e-9)
        assertEquals(1, bubbles(binding).size)
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test fun choosingAnotherDestinationWithdrawsTheQuestion() {
        val (activity, binding) = screen()
        longPress(binding, GeoPoint(45.07031, 7.68690))

        invoke(activity, "goToMapPoint", GeoPoint(45.06, 7.67))

        assertTrue(bubbles(binding).isEmpty())
        MainActivity::class.java.getDeclaredMethod("renderVehicles").apply { isAccessible = true }.invoke(activity)
        assertTrue("the old mark must not come back", pins(binding).none { it.position.latitude == 45.07031 })
    }

    @Test fun removingTheNamedDestinationLeavesAPinThatStillWorks() {
        val place = SavedPlace("a", "Casa", "", 45.05, 7.65)
        SavedPlaces(RuntimeEnvironment.getApplication()).save(place)
        val (activity, binding) = screen()
        binding.destinationInput.setText("Casa", false)
        assertEquals(activity.getString(R.string.edit_saved_place), binding.savedPlaceButton.contentDescription)

        binding.savedPlaceButton.performClick()
        val form = dialog()
        form.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()

        assertTrue(SavedPlaces(RuntimeEnvironment.getApplication()).isEmpty())
        assertEquals("45.05000, 7.65000", binding.destinationInput.text.toString())
        assertEquals(activity.getString(R.string.save_destination), binding.savedPlaceButton.contentDescription)
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test fun pressingAndHoldingTheBookmarkListsEverySavedPlaceToPickFrom() {
        val saved = SavedPlaces(RuntimeEnvironment.getApplication())
        saved.save(SavedPlace("a", "Work", "", 45.07, 7.66))
        saved.save(SavedPlace("b", "Casa", "Via Roma 1", 45.05, 7.65))
        val (activity, binding) = screen()

        assertTrue(binding.savedPlaceButton.performLongClick())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val list = dialog()
        val rows = (0 until list.listView.adapter.count).map { list.listView.adapter.getItem(it) }
        assertEquals(listOf("Casa · Via Roma 1", "Work"), rows)

        // Picking one goes there, as choosing it from the suggestions would.
        list.listView.performItemClick(null, 1, 1L)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(list.isShowing)
        assertEquals("Work", binding.destinationInput.text.toString())
        val destination = MainActivity::class.java.getDeclaredField("directDestination")
            .apply { isAccessible = true }.get(activity) as GeoPoint
        assertEquals(45.07, destination.latitude, 1e-9)
        assertEquals(activity.getString(R.string.edit_saved_place), binding.savedPlaceButton.contentDescription)
    }

    @Test fun pressingAndHoldingTheBookmarkWithNothingSavedSaysSo() {
        val (activity, binding) = screen()

        assertTrue(binding.savedPlaceButton.performLongClick())
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        val message = binding.coordinator
            .findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
        assertEquals(activity.getString(R.string.no_saved_places), message.text.toString())
    }
}
