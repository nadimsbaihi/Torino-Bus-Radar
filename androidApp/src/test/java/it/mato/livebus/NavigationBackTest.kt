package it.mato.livebus

import android.os.Looper
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import it.mato.livebus.databinding.ActivityMainBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Duration

/**
 * The journey sheet, the back button and the walking hand-off, on a laid-out screen.
 *
 * The sheet animates between states, so every test lets the clock run (see [settle]) before
 * looking at where it ended up. These tests deliberately use the default paused looper: the
 * legacy one never advances time, and a settling sheet would then re-post itself forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NavigationBackTest {
    private val board = TransitStop("board", "Via Roma", 45.0703, 7.6869)
    private val exit = TransitStop("exit", "Piazza Castello", 45.09, 7.6869)
    private val pattern = TransitPattern("p", "60U", "60", "Exit", "0", emptySet(), listOf(board, exit))
    private val vehicle = LiveVehicle("bus", "60U", null, board.latitude, board.longitude, null,
        System.currentTimeMillis() / 1000)
    private val journey = JourneyChoice(pattern, vehicle, board, 0, exit, 20f, 250f, 1, 120f,
        exit.latitude, exit.longitude, boardingEtaSeconds = 240f, estimatedTotalSeconds = 1_500f)

    @Before fun startEmpty() {
        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("saved_places", 0).edit().clear().commit()
        application.getSharedPreferences("active_journey", 0).edit().clear().commit()
    }

    private fun screen(): Pair<MainActivity, ActivityMainBinding> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val binding = MainActivity::class.java.getDeclaredField("binding")
            .apply { isAccessible = true }.get(activity) as ActivityMainBinding
        settle()
        return activity to binding
    }

    /** Runs layout passes and the sheet's settle animation to the end. */
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    private fun call(activity: MainActivity, name: String, vararg args: Any?) {
        val method = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        method.invoke(activity, *args)
    }

    private fun show(activity: MainActivity, choice: JourneyChoice = journey) {
        val method = MainActivity::class.java.getDeclaredMethod("displayJourney",
            String::class.java, Pair::class.java, JourneyChoice::class.java,
            ScheduledTrainJourney::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(activity, "Piazza Castello", exit.latitude to exit.longitude, choice, null, false)
        settle()
    }

    private fun field(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun back(activity: MainActivity) {
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
    }

    private fun sheet(binding: ActivityMainBinding) = BottomSheetBehavior.from(binding.journeyCard)

    private fun peek(activity: MainActivity) =
        activity.resources.getDimensionPixelSize(R.dimen.journey_peek_height)

    @Test fun aNewJourneyOpensAtThePeekSoTheMapStaysInView() {
        val (activity, binding) = screen()

        show(activity)

        assertEquals(View.VISIBLE, binding.journeyCard.visibility)
        assertEquals(BottomSheetBehavior.STATE_COLLAPSED, sheet(binding).state)
        assertEquals(4, binding.journeySteps.childCount)
        val covered = binding.coordinator.height - binding.journeyCard.top
        assertTrue("collapsed sheet covers $covered px, peek is ${peek(activity)}", covered <= peek(activity))
    }

    @Test fun theExpandedSheetNeverCoversTheSearchBox() {
        val (activity, binding) = screen()
        show(activity)

        sheet(binding).state = BottomSheetBehavior.STATE_EXPANDED
        settle()

        assertEquals(BottomSheetBehavior.STATE_EXPANDED, sheet(binding).state)
        assertTrue("sheet is only ${binding.journeyCard.height} px tall",
            binding.journeyCard.height > peek(activity))
        assertTrue("sheet top ${binding.journeyCard.top} is above the search box bottom ${binding.topControls.bottom}",
            binding.journeyCard.top >= binding.topControls.bottom)
    }

    @Test fun backClosesAnOpenJourneyInsteadOfLeavingTheApp() {
        val (activity, binding) = screen()
        show(activity)

        back(activity)

        assertFalse(activity.isFinishing)
        assertEquals(View.GONE, binding.journeyCard.visibility)
        assertEquals(0, binding.journeySteps.childCount)
        assertNull(field(activity, "journeyChoice"))
        assertNull(field(activity, "directDestination"))
    }

    @Test fun backLeavesTheAppOnceNothingIsOpen() {
        val (activity, _) = screen()

        back(activity)

        assertTrue(activity.isFinishing)
    }

    @Test fun backFirstClearsAHighlightedBusThenClosesTheJourney() {
        val (activity, binding) = screen()
        show(activity)
        MainActivity::class.java.getDeclaredField("highlightedVehicleId")
            .apply { isAccessible = true }.set(activity, "bus")

        back(activity)

        assertNull(field(activity, "highlightedVehicleId"))
        assertEquals(View.VISIBLE, binding.journeyCard.visibility)
        back(activity)
        assertEquals(View.GONE, binding.journeyCard.visibility)
        assertFalse(activity.isFinishing)
    }

    @Test fun backFirstCollapsesAnExpandedSheet() {
        val (activity, binding) = screen()
        show(activity)
        sheet(binding).state = BottomSheetBehavior.STATE_EXPANDED
        settle()
        assertEquals("precondition: the sheet really expanded",
            BottomSheetBehavior.STATE_EXPANDED, sheet(binding).state)

        back(activity)

        assertEquals(BottomSheetBehavior.STATE_COLLAPSED, sheet(binding).state)
        assertEquals(View.VISIBLE, binding.journeyCard.visibility)
        assertNotNull(field(activity, "journeyChoice"))
    }

    @Test fun backFirstDismissesTheSuggestionList() {
        val (activity, binding) = screen()
        show(activity)
        binding.destinationInput.showDropDown()
        settle()
        assertTrue("precondition: the suggestions are showing", binding.destinationInput.isPopupShowing)

        back(activity)

        assertFalse(binding.destinationInput.isPopupShowing)
        assertEquals(View.VISIBLE, binding.journeyCard.visibility)
    }

    @Test fun theCloseButtonDoesWhatBackDoes() {
        val (activity, binding) = screen()
        show(activity)

        binding.closeJourneyButton.performClick()
        settle()

        assertEquals(View.GONE, binding.journeyCard.visibility)
        assertEquals(0, binding.journeySteps.childCount)
        assertNull(field(activity, "journeyChoice"))
        assertFalse(activity.isFinishing)
    }

    @Test fun closingFromAnExpandedSheetAndPlanningAgainOpensAtThePeek() {
        val (activity, binding) = screen()
        show(activity)
        sheet(binding).state = BottomSheetBehavior.STATE_EXPANDED
        settle()
        binding.closeJourneyButton.performClick()
        settle()

        show(activity)

        assertEquals(View.VISIBLE, binding.journeyCard.visibility)
        assertEquals(BottomSheetBehavior.STATE_COLLAPSED, sheet(binding).state)
    }

    @Test fun draggingTheSheetAwayClosesTheJourney() {
        val (activity, binding) = screen()
        show(activity)

        // What a swipe down ends in: the sheet settles hidden, and its callback closes the journey.
        sheet(binding).state = BottomSheetBehavior.STATE_HIDDEN
        settle()

        assertEquals(View.GONE, binding.journeyCard.visibility)
        assertNull(field(activity, "journeyChoice"))
        assertNull(field(activity, "directDestination"))
    }

    @Test fun aLiveUpdateDoesNotPutAnExpandedSheetBackAtThePeek() {
        val (activity, binding) = screen()
        show(activity)
        sheet(binding).state = BottomSheetBehavior.STATE_EXPANDED
        settle()

        // The same journey, redisplayed as each live refresh does.
        show(activity)

        assertEquals(BottomSheetBehavior.STATE_EXPANDED, sheet(binding).state)
    }

    @Test fun aMessageReplacesTheStepsAndShowsInFull() {
        val (activity, binding) = screen()
        show(activity)

        call(activity, "showJourneyMessage", activity.getString(R.string.journey_failed))
        settle()

        assertEquals(0, binding.journeySteps.childCount)
        assertEquals(activity.getString(R.string.journey_failed), binding.sharedDirections.text.toString())
        assertEquals(View.VISIBLE, binding.sharedDirections.visibility)
        assertEquals(BottomSheetBehavior.STATE_EXPANDED, sheet(binding).state)
    }

    @Test fun walkingDoesNotLaunchMapsUntilAsked() {
        val (activity, binding) = screen()
        show(activity)
        shadowOf(activity).clearNextStartedActivities()

        binding.walkButton.performClick()
        settle()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(1, binding.journeySteps.childCount)
        assertEquals(View.VISIBLE, binding.boardStopDirectionsButton.visibility)
        assertEquals(activity.getString(R.string.open_walking_directions),
            binding.boardStopDirectionsButton.text.toString())

        binding.boardStopDirectionsButton.performClick()
        val maps = shadowOf(activity).nextStartedActivity
        assertEquals("google.navigation:q=${exit.latitude},${exit.longitude}&mode=w", maps.data.toString())
        // Back from Maps, the route can be opened again.
        binding.boardStopDirectionsButton.performClick()
        assertEquals("google.navigation:q=${exit.latitude},${exit.longitude}&mode=w",
            shadowOf(activity).nextStartedActivity.data.toString())
    }

    @Test fun choosingTransitAgainRestoresTheSteps() {
        val (activity, binding) = screen()
        show(activity)

        binding.walkButton.performClick()
        binding.busAnywayButton.performClick()

        assertEquals(4, binding.journeySteps.childCount)
        assertEquals(activity.getString(R.string.walk_to_boarding_stop),
            binding.boardStopDirectionsButton.text.toString())
    }

    @Test fun theBoardingStepOffersDirectionsToThatStop() {
        val (activity, binding) = screen()
        show(activity)
        shadowOf(activity).clearNextStartedActivities()

        binding.journeySteps.getChildAt(0).findViewById<View>(R.id.stepDirections).performClick()

        val maps = shadowOf(activity).nextStartedActivity
        assertEquals("google.navigation:q=${board.latitude},${board.longitude}&mode=w", maps.data.toString())
        assertEquals(View.GONE, binding.journeySteps.getChildAt(1).findViewById<View>(R.id.stepDirections).visibility)
    }

    @Test fun withNoAppToOpenDirectionsTheRiderIsToldInsteadOfCrashing() {
        val (activity, binding) = screen()
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)

        call(activity, "openWalkingDirections", GeoPoint(45.07, 7.68))
        settle()

        val message = binding.coordinator.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        assertNotNull("a message should be showing", message)
        assertEquals(activity.getString(R.string.directions_unavailable), message.text.toString())
    }

    @Test fun aLegWithItsOwnStartOpensTheDirectionsPageWithThatOrigin() {
        val (activity, _) = screen()

        call(activity, "openWalkingDirections", GeoPoint(45.08, 7.69), GeoPoint(45.07, 7.68))

        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("com.google.android.apps.maps", intent.`package`)
        assertEquals("https://www.google.com/maps/dir/?api=1&origin=45.07,7.68" +
            "&destination=45.08,7.69&travelmode=walking", intent.data.toString())
    }

    @Test fun theFromRowBelongsToAJourneyAndGoesWithIt() {
        val (activity, binding) = screen()
        assertEquals(View.GONE, binding.startRow.visibility)

        binding.destinationInput.setText("Piazza Castello", false)
        show(activity)
        assertEquals(View.VISIBLE, binding.startRow.visibility)
        assertEquals(activity.getString(R.string.start_from, activity.getString(R.string.start_my_location)),
            binding.startButton.text.toString())
        assertNotNull(binding.swapStartButton.contentDescription)
        assertEquals(View.VISIBLE, binding.alertButton.visibility)

        val start = Class.forName("it.mato.livebus.CustomStart").declaredConstructors.first()
            .apply { isAccessible = true }.newInstance("Casa", GeoPoint(45.0, 7.0))
        MainActivity::class.java.getDeclaredField("customStart").apply { isAccessible = true }.set(activity, start)
        show(activity)
        // Nobody is walking to the stop from there, so the approach alert is not offered.
        assertEquals(View.GONE, binding.alertButton.visibility)

        call(activity, "closeJourney")
        settle()
        assertEquals(View.GONE, binding.startRow.visibility)
        assertNull(field(activity, "customStart"))
    }

    @Test fun theMapIsCentredOnTheSpaceTheSheetLeavesFree() {
        val (activity, binding) = screen()
        val top = binding.topControls.height
        assertEquals("with no journey only the search box is in the way", top / 2, binding.map.mapCenterOffsetY)

        binding.destinationInput.setText("Piazza Castello", false)
        show(activity)

        // The start row, shown once there is a destination, is part of the peek.
        assertEquals(View.VISIBLE, binding.startRow.visibility)
        val startRow = activity.resources.getDimensionPixelSize(R.dimen.journey_start_row_height)
        val covered = minOf(peek(activity) + startRow, binding.journeyCard.height)
        assertTrue("the sheet should reserve map space", covered > 0)
        assertEquals((top - covered) / 2, binding.map.mapCenterOffsetY)

        binding.closeJourneyButton.performClick()
        settle()
        assertEquals(top / 2, binding.map.mapCenterOffsetY)
    }
}
