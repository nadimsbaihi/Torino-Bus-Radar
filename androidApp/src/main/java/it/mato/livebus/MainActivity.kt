package it.mato.livebus

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.annotation.SuppressLint
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import android.content.res.ColorStateList
import android.database.DataSetObserver
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.Insets
import androidx.core.view.GravityCompat
import androidx.core.view.doOnLayout
import android.graphics.Typeface
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.LocationServices
import it.mato.livebus.databinding.ActivityMainBinding
import it.mato.livebus.databinding.DialogPickStartBinding
import it.mato.livebus.databinding.DialogSavePlaceBinding
import it.mato.livebus.databinding.ItemJourneyStepBinding
import it.mato.livebus.databinding.ItemPlaceSuggestionBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.PopupMenu
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.content.DialogInterface
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.UUID
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.coroutines.resume

private data class CustomStart(val name: String, val point: GeoPoint)

/** The button under a journey message, and what it does. */
private class MessageAction(@StringRes val label: Int, val run: () -> Unit) {
    operator fun invoke() = run()
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val repository = GttRepository()
    private val transitIndex by lazy { TransitIndex.load(this) }
    private val trainIndex by lazy { TrainIndex.load(this) }
    private val journeyPlanner by lazy { JourneyPlanner(transitIndex, trainIndex) }
    private val offlineCity get() = (application as MatoApplication).offlineCity
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var offlineMapSource: MapsForgeTileSource? = null
    private var currentLocation = GeoPoint(45.0703, 7.6869)
    private var originLocation: Location? = null
    private var locationRequest: Deferred<Location?>? = null
    private var permissionRequest: CompletableDeferred<Boolean>? = null
    private var planningJob: Job? = null
    private var lastGeocodedAddress: Pair<String, Pair<Double, Double>>? = null
    private var lastResolvedPlace: OfflinePlace? = null
    private val savedPlaces by lazy { SavedPlaces(this) }
    private val recentDestinations by lazy { RecentDestinations(this) }
    private lateinit var destinationAdapter: ArrayAdapter<OfflinePlace>
    private var liveVehicles: List<LiveVehicle> = emptyList()
    private var refreshJob: Job? = null
    /** Elapsed-realtime of the last successful fetch while the chip shows live data, else null. */
    private var liveStatusFetchedAt: Long? = null
    private var selectedRoute: String? = null
    private var selectedPattern: TransitPattern? = null
    private var highlightedVehicleId: String? = null
    private var courseOverlays: List<Overlay> = emptyList()
    private var journeyChoice: JourneyChoice? = null
    private var trainJourneyChoice: ScheduledTrainJourney? = null
    private var mixedJourneyChoice: MixedJourney? = null
    // What the planner offered for the journey on screen, and which of those is showing.
    private var journeyOptions: List<JourneyOption> = emptyList()
    private var selectedOption = -1
    private var plannedDestination: Pair<String, Pair<Double, Double>>? = null
    private var walkingMode = false
    // Walking beats the ride to this destination; the journey then opens on the walk.
    private var walkingIsFaster = false
    private var zoomButtonsRetired = false
    // Where the rider chose to ride although walking is quicker, so live updates keep the ride.
    private var rideAnywayTo: GeoPoint? = null
    private var directWalkingMetres = 0f
    private var directWalkingSeconds = 0f
    private var walkingFromOrigin: WalkGraph.Distances? = null
    private var walkingFromDestination: WalkGraph.Distances? = null
    private var directDestination: GeoPoint? = null
    private var transitGuide: JourneyGuide? = null
    private var automaticReplanPending = false
    private var missingBusRefreshes = 0
    private var includeTrains = true
    // GTT's notices about the metro (a closure, buses replacing it), as of the last search.
    private var metroAlerts: List<String> = emptyList()
    // The journey options offer the least walking and the fastest trip; the first pick weighs both.
    private val walkingPreference = WalkingPreference.BALANCED
    private val busIconCache = mutableMapOf<Triple<Boolean, Int, String>, BitmapDrawable>()
    /** Where walking is measured from and the journey is drawn from. */
    private val startPoint get() = customStart?.point ?: currentLocation
    private val myLocationLabel get() = getString(R.string.start_my_location)
    private val journeySheet get() = BottomSheetBehavior.from(binding.journeyCard)
    private var mapInsets = 0 to 0
    // A start other than the rider's own position, set from the journey panel and gone with the journey.
    private var customStart: CustomStart? = null
    private val appUpdates by lazy { AppUpdates(this) }
    // One bubble serves every marker, as osmdroid's own does; only one is open at a time.
    private val markerBubble by lazy {
        MarkerInfoWindow(R.layout.bubble_marker, binding.map).also { MapBubble.style(it.view) }
    }
    private var pendingUpdate: File? = null
    private var stopWindow: StopArrivalsWindow? = null
    private var openStop: TransitStop? = null
    private var stopJob: Job? = null
    // What the button under a journey message does, while one shows.
    private var messageAction: MessageAction? = null

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        permissionRequest?.complete(granted)
        permissionRequest = null
        if (!granted) showLocationHint()
    }
    private val locationSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (deviceLocationEnabled()) {
            if (binding.destinationInput.text.isNullOrBlank()) requestLocation()
            else findDestinationRoute()
        } else {
            showLocationHint()
        }
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) enableAlert() else showNotificationsBlocked() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.requestFocus()
        applySystemBarInsets()
        ViewCompat.setAccessibilityHeading(binding.journeyTitle, true)

        setupMap()
        setupJourneySheet()
        setupBackNavigation()
        setupDestinationPicker()
        setupSearchPresets()
        setupRouteCard()
        setupAppMenu()
        setupHints()
        binding.mapAttribution.setOnClickListener { Credits.show(this) }
        binding.locationButton.setOnClickListener {
            if (deviceLocationEnabled()) requestLocation() else openLocationSettings()
        }
        binding.locationSettingsButton.setOnClickListener { messageAction?.invoke() }
        binding.liveStatus.setOnClickListener { startRefreshing(forceRefresh = true) }
        binding.alertButton.setOnClickListener { if (isAlertActive()) stopAlert() else requestAlert() }
        binding.modeSwitchButton.setOnClickListener {
            planningJob?.cancel()
            if (walkingMode) {
                rideAnywayTo = directDestination
                selectBusMode()
            } else {
                rideAnywayTo = null
                selectWalkingMode()
            }
        }
        binding.closeJourneyButton.setOnClickListener { closeJourney() }
        binding.refreshJourneyButton.setOnClickListener { refreshJourney() }
        consumeSharedDirections(intent)
        requestLocation()
        startRefreshing()
        consumeReplan(intent)
        consumeGoToPlace(intent)
        consumeGeoLink(intent)
        savedPlaces.publishShortcuts()
        checkForUpdate()
    }

    /**
     * Now and then, looks for a newer release and offers it; [asked] looks right away and says when
     * there is none. Debug builds are never released, so they only look when asked.
     */
    private fun checkForUpdate(asked: Boolean = false) {
        if (!asked && (BuildConfig.DEBUG || !appUpdates.dueForCheck())) return
        lifecycleScope.launch {
            val release = try {
                appUpdates.newerRelease(BuildConfig.VERSION_NAME)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Update check failed", error)
                if (asked) Snackbar.make(binding.coordinator, R.string.update_check_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            when {
                release != null -> Snackbar.make(binding.coordinator,
                    getString(R.string.update_available, release.version), Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.update_action) { downloadUpdate(release) }.show()
                asked -> Snackbar.make(binding.coordinator,
                    getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadUpdate(release: AppRelease) {
        val progress = Snackbar.make(binding.coordinator, getString(R.string.update_downloading, 0),
            Snackbar.LENGTH_INDEFINITE).also { it.show() }
        lifecycleScope.launch {
            try {
                val apk = appUpdates.download(release) { percent ->
                    progress.setText(getString(R.string.update_downloading, percent))
                }
                progress.dismiss()
                installUpdate(apk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Update download failed", error)
                progress.dismiss()
                Snackbar.make(binding.coordinator, R.string.update_failed, Snackbar.LENGTH_LONG)
                    .setAction(R.string.retry) { downloadUpdate(release) }.show()
            }
        }
    }

    /** Hands the APK to the system installer, first sending the rider to allow it if they never have. */
    private fun installUpdate(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingUpdate = apk
            Snackbar.make(binding.coordinator, R.string.update_allow_install, Snackbar.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        pendingUpdate = null
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
        startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSharedDirections(intent)
        consumeReplan(intent)
        consumeGoToPlace(intent)
        consumeGeoLink(intent)
    }

    private fun setupJourneySheet() {
        journeySheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheet: View, newState: Int) {
                // Swiping the sheet away closes the journey, like the close button does.
                if (newState == BottomSheetBehavior.STATE_HIDDEN &&
                    binding.journeyCard.visibility == View.VISIBLE) closeJourney()
                // A state set without a slide (before layout, say) still settles the map controls.
                if (newState == BottomSheetBehavior.STATE_EXPANDED) followSheet(1f)
                if (newState == BottomSheetBehavior.STATE_COLLAPSED) followSheet(0f)
            }

            // The map's own controls ride up with the sheet; once it is pulled over the map they
            // would land on the search bar, so they fade out on the way.
            override fun onSlide(sheet: View, slideOffset: Float) = followSheet(slideOffset)
        })
        binding.journeyHandle.setOnClickListener {
            journeySheet.state = if (journeySheet.state == BottomSheetBehavior.STATE_EXPANDED)
                BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_EXPANDED
        }
        // The sheet never grows over the search box, nor the From/To card that replaces it, and the
        // map is framed around what is left.
        binding.coordinator.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val routeBottom = if (binding.routeCard.visibility == View.GONE) 0 else binding.routeCard.bottom
            val available = binding.coordinator.height - maxOf(binding.topControls.height, routeBottom)
            if (available > 0) journeySheet.maxHeight = available
            updateMapInsets()
        }
        binding.journeyCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateJourneyPeek()
            updateMapInsets()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stepBack()) return
                // Nothing is left to undo, so let the system leave the app; the next visit steps back again.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    /** Undoes the innermost thing the rider opened. False once the map is back at rest. */
    private fun stepBack(): Boolean {
        val journeyOpen = binding.journeyCard.visibility == View.VISIBLE
        val peek = journeyPeek()
        return when {
            binding.drawer.isDrawerOpen(GravityCompat.START) -> {
                binding.drawer.closeDrawer(GravityCompat.START)
                true
            }
            binding.destinationInput.isPopupShowing -> {
                binding.destinationInput.dismissDropDown()
                true
            }
            binding.hintsCard.visibility == View.VISIBLE -> {
                hideHints()
                true
            }
            journeyOpen && journeySheet.state == BottomSheetBehavior.STATE_EXPANDED &&
                binding.journeyCard.height > peek -> {
                journeySheet.state = BottomSheetBehavior.STATE_COLLAPSED
                true
            }
            highlightedVehicleId != null -> {
                clearHighlightedCourse()
                true
            }
            journeyOpen -> {
                closeJourney()
                true
            }
            else -> false
        }
    }

    /**
     * Fresh bus positions, then the journey planned again from them. A header button rather than
     * pull-to-refresh: a pull down at the top of the list is how the sheet is dragged closed, and
     * the sheet claims that gesture first.
     */
    private fun refreshJourney() {
        startRefreshing(forceRefresh = true)
        if (!binding.destinationInput.text.isNullOrBlank()) findDestinationRoute()
    }

    private fun closeJourney() {
        planningJob?.cancel()
        stopWindow?.close()
        customStart = null
        rideAnywayTo = null
        // After a swap the box holds "My location", which is no destination to leave behind.
        if (binding.destinationInput.text.toString().trim() == myLocationLabel) binding.destinationInput.setText("")
        journeyChoice = null
        trainJourneyChoice = null
        mixedJourneyChoice = null
        walkingFromOrigin = null
        walkingFromDestination = null
        selectedRoute = null
        selectedPattern = null
        highlightedVehicleId = null
        directDestination = null
        transitGuide = null
        binding.journeySteps.removeAllViews()
        hideTravelModeChoices()
        hideJourneySheet()
        renderVehicles()
    }

    private fun followSheet(slideOffset: Float) {
        fadeMapControls(slideOffset)
        revealRouteCard(slideOffset)
    }

    /** Past halfway up, the search bar hands over to the From/To card; on the way down it takes over again. */
    private fun revealRouteCard(slideOffset: Float) {
        if (binding.routeCard.visibility == View.GONE) return
        val shown = ((slideOffset - 0.5f) / 0.5f).coerceIn(0f, 1f)
        binding.routeCard.alpha = shown
        binding.routeCard.visibility = if (shown > 0f) View.VISIBLE else View.INVISIBLE
        binding.searchCard.alpha = 1f - shown
        binding.searchCard.visibility = if (shown < 1f) View.VISIBLE else View.INVISIBLE
    }

    private fun fadeMapControls(slideOffset: Float) {
        val alpha = (1f - slideOffset.coerceAtLeast(0f) * 3f).coerceIn(0f, 1f)
        listOfNotNull(binding.locationButton, binding.zoomControls.takeUnless { zoomButtonsRetired },
            binding.mapAttribution).forEach {
            it.alpha = alpha
            it.visibility = if (alpha == 0f) View.INVISIBLE else View.VISIBLE
        }
    }

    /** Shows the journey sheet. A null [state] leaves it wherever the rider put it. */
    private fun showJourneySheet(state: Int? = null) {
        binding.journeyCard.visibility = View.VISIBLE
        val target = state ?: BottomSheetBehavior.STATE_COLLAPSED.takeIf {
            journeySheet.state == BottomSheetBehavior.STATE_HIDDEN
        }
        if (target != null) journeySheet.state = target
        // A journey has the map to itself: the tips step aside, and so does the live count,
        // which the sheet's own refresh button stands in for.
        binding.hintsCard.visibility = View.GONE
        binding.liveStatus.visibility = View.GONE
        updateRouteCard()
        updateMapInsets()
    }

    private fun hideJourneySheet() {
        // Gone is enough: there is nothing to animate once it is out of sight, and the next
        // journey sets the state it wants. A swipe has already left the sheet hidden.
        binding.journeyCard.visibility = View.GONE
        binding.liveStatus.visibility = View.VISIBLE
        fadeMapControls(0f)
        updateRouteCard()
        updateMapInsets()
    }

    /** The first visit opens on tips for what the map answers to, until the rider says they have them. */
    private fun setupHints() {
        binding.hintsDoneButton.setOnClickListener {
            getSharedPreferences(JOURNEY_STORE, MODE_PRIVATE).edit().putBoolean(HINTS_SEEN, true).apply()
            hideHints()
        }
        val seen = getSharedPreferences(JOURNEY_STORE, MODE_PRIVATE).getBoolean(HINTS_SEEN, false)
        if (!seen) showHints()
    }

    private fun showHints() {
        if (binding.journeyCard.visibility == View.VISIBLE) closeJourney()
        binding.hintsCard.visibility = View.VISIBLE
    }

    private fun hideHints() {
        binding.hintsCard.visibility = View.GONE
    }

    /** Tells the rider once, as the first course appears, that its stops answer to a tap too. */
    private fun hintAtStopsOnce() {
        val preferences = getSharedPreferences(JOURNEY_STORE, MODE_PRIVATE)
        if (preferences.getBoolean(COURSE_HINT_SEEN, false)) return
        preferences.edit().putBoolean(COURSE_HINT_SEEN, true).apply()
        Snackbar.make(binding.coordinator, R.string.course_hint, Snackbar.LENGTH_LONG).show()
    }

    /** Keeps the map centred on the part of it that the search box and journey sheet leave visible. */
    private fun updateMapInsets() {
        val insets = binding.topControls.height to journeyInset()
        if (insets == mapInsets) return
        mapInsets = insets
        binding.map.setMapCenterOffset(0, (insets.first - insets.second) / 2)
    }

    /**
     * What shows of the sheet before it is pulled up: the handle, the summary and the first step,
     * measured where they are laid out, so whatever sits above the steps can never push them out.
     * A message shows its text instead; before layout, the dimension is the guess.
     */
    private fun journeyPeek(): Int {
        // Quicker on foot, the peek reaches down to the question of riding anyway, so it is asked at rest.
        val first = binding.modeSwitchButton.takeIf { walkingMode && it.visibility == View.VISIBLE && it.height > 0 }
            ?: binding.journeySteps.getChildAt(0)
            ?: binding.sharedDirections.takeIf { it.visibility == View.VISIBLE && it.height > 0 }
        if (first == null || first.height == 0 || binding.journeyCard.height == 0) {
            return resources.getDimensionPixelSize(R.dimen.journey_peek_height)
        }
        // Layout positions, not drawn ones: a sheet scrolled while open keeps the same peek.
        var bottom = first.bottom
        var parent = first.parent as? View
        while (parent != null && parent != binding.journeyCard) {
            bottom += parent.top
            parent = parent.parent as? View
        }
        return bottom + resources.getDimensionPixelSize(R.dimen.journey_peek_margin)
    }

    /** Follows the first step wherever layout put it; the sheet then settles to the new peek. */
    private fun updateJourneyPeek() {
        val peek = journeyPeek()
        if (journeySheet.peekHeight != peek) journeySheet.peekHeight = peek
    }

    /** The height of map the sheet covers at rest: its peek, or less when the content is short. */
    private fun journeyInset(): Int {
        if (binding.journeyCard.visibility != View.VISIBLE) return 0
        val peek = journeyPeek()
        return if (binding.journeyCard.height > 0) minOf(peek, binding.journeyCard.height) else peek
    }

    private fun renderJourneyGuide(guide: JourneyGuide) {
        binding.journeySteps.removeAllViews()
        var railIn: Rail? = null
        guide.steps.forEachIndexed { index, step ->
            val row = ItemJourneyStepBinding.inflate(layoutInflater, binding.journeySteps, false)
            row.stepText.text = step.text
            step.detail?.let { detail ->
                row.stepDetail.text = detail
                row.stepDetail.setCompoundDrawablesRelative(
                    sizedIcon(R.drawable.ic_schedule, row.stepDetail.textSize, R.color.muted), null, null, null)
                row.stepDetail.visibility = View.VISIBLE
            }
            row.stepIcon.setImageResource(when {
                step.getOff -> R.drawable.ic_get_off
                step.kind == StepKind.WALK -> R.drawable.ic_walk
                step.kind == StepKind.TRANSFER -> R.drawable.ic_transfer
                step.kind == StepKind.TRAIN -> R.drawable.ic_train
                step.kind == StepKind.METRO -> R.drawable.ic_metro
                else -> R.drawable.ic_bus
            })
            ImageViewCompat.setImageTintList(row.stepIcon, ColorStateList.valueOf(getColor(stepColor(step.kind))))
            val railOut = if (index == guide.steps.lastIndex) null else railAfter(step)
            paintRail(row.stepRailAbove, railIn)
            paintRail(row.stepRailBelow, railOut)
            railIn = railOut
            step.target?.let { target ->
                row.stepDirections.visibility = View.VISIBLE
                row.stepDirections.contentDescription = getString(R.string.step_directions_for,
                    listOfNotNull(step.text, step.detail).joinToString(", "))
                row.stepDirections.setOnClickListener { openWalkingDirections(target, step.origin) }
            }
            binding.journeySteps.addView(row.root)
        }
        binding.sharedDirections.text = notesText(guide.notes)
        binding.sharedDirections.visibility = if (guide.notes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun stepColor(kind: StepKind) = when (kind) {
        StepKind.TRAIN -> R.color.train_blue
        StepKind.METRO -> R.color.metro_red
        else -> R.color.deep_green
    }

    /** How the thread between two steps is drawn: a ride in its line's colour, a walk thin and grey. */
    private class Rail(@ColorRes val color: Int, val widthDp: Int)

    /** Boarding starts a ride, so the thread below it is the vehicle's; anything else leads on foot. */
    private fun railAfter(step: JourneyStep): Rail =
        if (!step.getOff && step.kind in setOf(StepKind.BUS, StepKind.METRO, StepKind.TRAIN)) Rail(stepColor(step.kind), 4)
        else Rail(R.color.line, 2)

    private fun paintRail(view: View, rail: Rail?) {
        if (rail == null) {
            view.visibility = View.INVISIBLE
            return
        }
        view.visibility = View.VISIBLE
        view.setBackgroundColor(getColor(rail.color))
        view.layoutParams = view.layoutParams.apply { width = (rail.widthDp * resources.displayMetrics.density).roundToInt() }
    }

    /** Each note on its own line, behind an icon the height of the text that says what it is about. */
    private fun notesText(notes: List<JourneyNote>): CharSequence {
        val size = binding.sharedDirections.textSize
        val align = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DynamicDrawableSpan.ALIGN_CENTER
            else DynamicDrawableSpan.ALIGN_BOTTOM
        return SpannableStringBuilder().apply {
            notes.forEachIndexed { index, note ->
                if (index > 0) append("\n")
                append(" ", ImageSpan(sizedIcon(note.icon, size, R.color.deep_green), align),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("  ").append(note.text)
            }
        }
    }

    private fun sizedIcon(icon: Int, sizePx: Float, tint: Int): Drawable =
        requireNotNull(AppCompatResources.getDrawable(this, icon)).mutate().apply {
            setBounds(0, 0, sizePx.roundToInt(), sizePx.roundToInt())
            setTint(getColor(tint))
        }

    private fun appendJourneyNote(note: String) {
        val current = binding.sharedDirections.text
        binding.sharedDirections.text =
            if (current.isEmpty()) note else SpannableStringBuilder(current).append("\n").append(note)
        binding.sharedDirections.visibility = View.VISIBLE
    }

    /** Tiles still being drawn take the map theme's background, so at night they are not a light grid. */
    private fun tintLoadingTiles() = binding.map.overlayManager.tilesOverlay.run {
        setLoadingBackgroundColor(getColor(R.color.map_loading))
        setLoadingLineColor(getColor(R.color.map_loading_line))
        // Zooming out, osmdroid pieces tiles together from closer ones and fills the gaps light grey.
        MapTileProviderBase.setApproximationBackgroundColor(getColor(R.color.map_loading))
    }

    private fun setupZoomButtons() = with(binding.map) {
        // osmdroid's own buttons are plain white squares that sit over the attribution.
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        binding.zoomInButton.setOnClickListener { controller.zoomIn() }
        binding.zoomOutButton.setOnClickListener { controller.zoomOut() }
        addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?) = false
            override fun onZoom(event: ZoomEvent?): Boolean {
                binding.zoomInButton.isEnabled = canZoomIn()
                binding.zoomOutButton.isEnabled = canZoomOut()
                return false
            }
        })
        // Someone who zooms with two fingers knows the gesture: the buttons step aside, for good.
        zoomButtonsRetired = getSharedPreferences(JOURNEY_STORE, MODE_PRIVATE).getBoolean(ZOOM_BUTTONS_RETIRED, false)
        if (zoomButtonsRetired) binding.zoomControls.visibility = View.GONE
        overlays.add(object : Overlay() {
            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) retireZoomButtons()
                return false
            }
        })
    }

    private fun retireZoomButtons() {
        if (zoomButtonsRetired) return
        zoomButtonsRetired = true
        getSharedPreferences(JOURNEY_STORE, MODE_PRIVATE).edit().putBoolean(ZOOM_BUTTONS_RETIRED, true).apply()
        binding.zoomControls.animate().alpha(0f).setDuration(250)
            .withEndAction { binding.zoomControls.visibility = View.GONE }
    }

    private fun setupMap() = with(binding.map) {
        setMultiTouchControls(true)
        setupZoomButtons()
        setUseDataConnection(false)
        setTilesScaledToDpi(false)
        tintLoadingTiles()
        controller.setZoom(16.0)
        controller.setCenter(currentLocation)
        overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                if (askedSpot != null) {
                    clearAskedSpot()
                    return true
                }
                // A stop dot is small; a tap that nearly hits one is meant for it, not for clearing the course.
                courseStopNear(point)?.let { stop ->
                    showStopArrivals(stop, STOP_DOT_DP / 2)
                    return true
                }
                if (openStop != null) {
                    stopWindow?.close()
                    return true
                }
                if (highlightedVehicleId == null) return false
                clearHighlightedCourse()
                return true
            }

            override fun longPressHelper(point: GeoPoint?): Boolean {
                if (point == null) return false
                binding.map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                askAboutMapPoint(point)
                return true
            }
        }))
        lifecycleScope.launch {
            try {
                val city = withContext(Dispatchers.IO) { offlineCity }
                val night = MapTheme.isNight(this@MainActivity)
                val source = MapsForgeTileSource.createFromFiles(arrayOf(city.mapFile),
                    MapTheme.renderTheme(this@MainActivity, night),
                    MapTheme.cacheKey(city, night), "it")
                offlineMapSource = source
                val provider = MapsForgeTileProvider(SimpleRegisterReceiver(this@MainActivity), source, null)
                setTileProvider(provider)
                // A new provider brings a new tiles overlay with osmdroid's light grid again.
                tintLoadingTiles()
                setUseDataConnection(false)
                minZoomLevel = 11.0
                maxZoomLevel = 20.0
                setScrollableAreaLimitDouble(source.boundsOsmdroid)
                binding.mapStatus.visibility = View.GONE
                invalidate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Offline map could not be opened", error)
                binding.mapStatus.setText(R.string.offline_map_failed)
            }
        }
    }

    /** The search suggestions: the rider's saved places first, then the bundled city. */
private fun placeSuggestions(): ArrayAdapter<OfflinePlace> = object : ArrayAdapter<OfflinePlace>(
        this, android.R.layout.simple_dropdown_item_1line
    ) {
        private val searchFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString().orEmpty()
                // Nothing typed lists every saved place; one letter is too vague to search.
                val saved = when {
                    query.isBlank() -> savedPlaces.all()
                    query.length < 2 -> emptyList()
                    else -> savedPlaces.search(query)
                }
                val found = if (query.isBlank()) recentSuggestions() else if (query.length < 2) emptyList() else try {
                    PlaceSuggestion.collapseNearby(
                        offlineCity.search(query, currentLocation.latitude, currentLocation.longitude))
                } catch (error: Exception) {
                    Log.e(TAG, "Offline destination search failed", error)
                    emptyList()
                }
                val matches = SavedPlaces.merge(saved, found, query, MAX_SUGGESTIONS)
                return FilterResults().apply { values = matches; count = matches.size }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                @Suppress("UNCHECKED_CAST")
                val matches = results?.values as? List<OfflinePlace> ?: emptyList()
                setNotifyOnChange(false)
                clear()
                addAll(matches)
                notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?) =
                (resultValue as? OfflinePlace)?.label.orEmpty()
        }

        override fun getFilter(): Filter = searchFilter

        // Each suggestion says what kind of place it is, so a saved place, a stop and an address differ at a glance.
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let(ItemPlaceSuggestionBinding::bind)
                ?: ItemPlaceSuggestionBinding.inflate(layoutInflater, parent, false)
            val suggestion = PlaceSuggestion.of(this@MainActivity, getItem(position) ?: return row.root)
            row.suggestionIcon.setImageResource(suggestion.icon)
            ImageViewCompat.setImageTintList(row.suggestionIcon, ColorStateList.valueOf(getColor(R.color.deep_green)))
            row.suggestionName.text = suggestion.title
            row.suggestionDetail.text = suggestion.detail
            row.suggestionDetail.visibility = if (suggestion.detail.isEmpty()) View.GONE else View.VISIBLE
            return row.root
        }
    }

    private fun setupDestinationPicker() {
        val adapter = placeSuggestions()
        destinationAdapter = adapter
        binding.destinationInput.setAdapter(adapter)
        binding.destinationInput.threshold = 2
        // The list shows once filtering is done, after the adapter changes; check the bar's corners then.
        adapter.registerDataSetObserver(object : DataSetObserver() {
            override fun onChanged() { binding.searchCard.post(::joinSearchBarToSuggestions) }
            override fun onInvalidated() { binding.searchCard.post(::joinSearchBarToSuggestions) }
        })
        binding.destinationInput.setOnDismissListener { joinSearchBarToSuggestions() }
        binding.destinationInput.doAfterTextChanged {
            binding.destinationLayout.error = null
            refreshSavedPlaceButton()
            updateRouteCard()
        }
        // An empty search box offers the saved places. The clear-text icon relies on the
        // field's own focus listener, so keep it and add to it.
        val keepClearIconInSync = binding.destinationInput.onFocusChangeListener
        binding.destinationInput.setOnFocusChangeListener { view, focused ->
            keepClearIconInSync?.onFocusChange(view, focused)
            if (focused) showSavedSuggestions()
        }
        binding.destinationInput.setOnClickListener { showSavedSuggestions() }
        binding.savedPlaceButton.setOnClickListener { onSavedPlaceButton() }
        binding.savedPlaceButton.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showAllSavedPlaces()
            true
        }
        binding.destinationInput.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let(::chooseDestination)
        }
        binding.destinationInput.setOnEditorActionListener { _, _, _ ->
            findDestinationRoute()
            true
        }
    }

    /** Makes [place] the destination, as picking it from the suggestions does. */
    private fun chooseDestination(place: OfflinePlace) {
        lastGeocodedAddress = place.name to (place.latitude to place.longitude)
        lastResolvedPlace = place
        binding.destinationInput.setText(place.name, false)
        showDestination(place)
    }

    /** Press and hold the bookmark: every saved place in one list, to pick one. */
    private fun showAllSavedPlaces() {
        val places = savedPlaces.all()
        if (places.isEmpty()) {
            Snackbar.make(binding.coordinator, R.string.no_saved_places, Snackbar.LENGTH_LONG).show()
            return
        }
        binding.destinationInput.dismissDropDown()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.saved_places_title)
            .setItems(places.map { it.label }.toTypedArray()) { _, position ->
                chooseDestination(places[position].asOfflinePlace(exactMatch = true))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDestination(place: OfflinePlace) {
        clearAskedSpot()
        directDestination = GeoPoint(place.latitude, place.longitude)
        binding.map.overlays.removeAll { it is Marker || it is Polyline }
        addYouMarker()
        addExactDestinationMarker(place.latitude, place.longitude)
        binding.map.controller.setZoom(16.0)
        binding.map.controller.animateTo(directDestination)
        binding.map.invalidate()
        refreshSavedPlaceButton()
        findDestinationRoute()
    }

    // The pin on a spot the rider pressed and held, while its bubble asks whether to go there.
    private var askedSpot: Marker? = null

    /** A press that lingers is easily an accident: mark the spot and ask there before going. */
    private fun askAboutMapPoint(point: GeoPoint) {
        clearAskedSpot()
        stopWindow?.close()
        val pin = Marker(binding.map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = getString(R.string.map_pin_confirm_title)
            snippet = MapPin.format(point.latitude, point.longitude)
            icon = destinationPinIcon()
            setInfoWindow(SpotInfoWindow(binding.map,
                onGo = { askedSpot?.position?.let(::goToMapPoint) },
                onCancel = ::clearAskedSpot))
        }
        askedSpot = pin
        binding.map.overlays.add(pin)
        pin.showInfoWindow()
        binding.map.controller.animateTo(point)
        binding.map.invalidate()
    }

    private fun clearAskedSpot() {
        val pin = askedSpot ?: return
        askedSpot = null
        pin.closeInfoWindow()
        binding.map.overlays.remove(pin)
        binding.map.invalidate()
    }

    /** Press and hold on the map: go there, whether or not the city data has an address for it. */
    private fun goToMapPoint(point: GeoPoint) {
        val text = MapPin.format(point.latitude, point.longitude)
        // Reading the coordinates back is how the usual search finds the pin again, for
        // replanning and for restoring the journey next time.
        val spot = MapPin.parse(text) ?: return
        lastGeocodedAddress = text to spot
        lastResolvedPlace = null
        binding.destinationInput.dismissDropDown()
        binding.destinationInput.setText(text, false)
        showDestination(OfflinePlace(text, text, spot.first, spot.second, "pin", true))
    }

    /** Recent destinations, minus any the rider has since saved under a name. */
    private fun recentSuggestions(): List<OfflinePlace> {
        val saved = savedPlaces.all()
        return recentDestinations.all().filter { recent ->
            saved.none { it.isAt(recent.latitude, recent.longitude) || SavedPlaces.key(it.name) == SavedPlaces.key(recent.name) }
        }
    }

    /** While the suggestions show, they hang from the search bar as one card, so the bar's bottom corners square off. */
    private fun joinSearchBarToSuggestions() {
        val card = binding.searchCard
        val radius = resources.getDimension(R.dimen.search_bar_radius)
        val bottom = if (binding.destinationInput.isPopupShowing) 0f else radius
        card.shapeAppearanceModel = card.shapeAppearanceModel.toBuilder()
            .setTopLeftCornerSize(radius).setTopRightCornerSize(radius)
            .setBottomLeftCornerSize(bottom).setBottomRightCornerSize(bottom)
            .build()
    }

    /** With nothing typed, the search box offers the saved places, then recent destinations. */
    private fun showSavedSuggestions() {
        val input = binding.destinationInput
        if (!input.text.isNullOrBlank() || (savedPlaces.isEmpty() && recentDestinations.all().isEmpty())) return
        destinationAdapter.filter.filter("") {
            if (input.hasFocus() && input.text.isNullOrBlank()) input.showDropDown()
        }
    }

    /** The saved place the search box shows, whether by its name or by the spot it points to. */
    private fun currentSavedPlace(): SavedPlace? {
        val typed = binding.destinationInput.text.toString().trim()
        if (typed.isEmpty()) return null
        savedPlaces.byName(typed)?.let { return it }
        val spot = lastGeocodedAddress?.takeIf { it.first == typed }?.second ?: return null
        return savedPlaces.at(spot.first, spot.second)
    }

    private var savedButtonFilled: Boolean? = null

    private fun refreshSavedPlaceButton() {
        val saved = currentSavedPlace() != null
        if (saved == savedButtonFilled) return
        savedButtonFilled = saved
        binding.savedPlaceButton.setIconResource(
            if (saved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
        binding.savedPlaceButton.contentDescription = getString(
            if (saved) R.string.edit_saved_place else R.string.save_destination)
    }

    private fun onSavedPlaceButton() {
        currentSavedPlace()?.let { showSavedPlaceDialog(it, isNew = false); return }
        val typed = binding.destinationInput.text.toString().trim()
        if (typed.isEmpty()) {
            if (savedPlaces.isEmpty()) {
                Snackbar.make(binding.coordinator, R.string.no_saved_places, Snackbar.LENGTH_LONG).show()
            } else {
                binding.destinationInput.requestFocus()
                showSavedSuggestions()
            }
            return
        }
        lifecycleScope.launch {
            val spot = try {
                geocode(typed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Could not resolve the destination to save", error)
                null
            }
            if (spot == null) {
                binding.destinationLayout.error = getString(R.string.save_needs_known_place)
                return@launch
            }
            val found = lastResolvedPlace?.takeIf { it.name == typed && it.kind != SavedPlace.KIND }
            showSavedPlaceDialog(
                SavedPlace(UUID.randomUUID().toString(), typed, found?.label.orEmpty(),
                    spot.first, spot.second),
                isNew = true)
        }
    }

    /** Names a place, or renames or removes one already named. */
    private fun showSavedPlaceDialog(place: SavedPlace, isNew: Boolean) {
        val form = DialogSavePlaceBinding.inflate(layoutInflater)
        form.savePlaceAddress.text = place.address.ifBlank {
            getString(R.string.map_pin_location, MapPin.format(place.latitude, place.longitude))
        }
        // A pin is only known by its coordinates, which make a poor name.
        form.savePlaceName.setText(if (MapPin.parse(place.name) != null) "" else place.name)
        form.savePlaceName.selectAll()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (isNew) R.string.save_place_title else R.string.edit_saved_place)
            .setView(form.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (!isNew) builder.setNeutralButton(R.string.remove, null)
        val dialog = builder.create()

        fun save() {
            val named = place.copy(name = SavedPlaces.clean(form.savePlaceName.text.toString()))
            val problem = savedPlaces.save(named)
            if (problem != null) {
                form.savePlaceNameLayout.error = getString(when (problem) {
                    SavedPlaces.NameProblem.BLANK -> R.string.place_name_required
                    SavedPlaces.NameProblem.TAKEN -> R.string.place_name_taken
                })
                return
            }
            dialog.dismiss()
            onPlaceSaved(named)
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { save() }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                dialog.dismiss()
                removeSavedPlace(place)
            }
        }
        form.savePlaceName.setOnEditorActionListener { _, _, _ -> save(); true }
        form.savePlaceName.doAfterTextChanged { form.savePlaceNameLayout.error = null }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun onPlaceSaved(place: SavedPlace) {
        // From now on the search box goes by the new name.
        lastGeocodedAddress = place.name to (place.latitude to place.longitude)
        lastResolvedPlace = place.asOfflinePlace(exactMatch = true)
        binding.destinationInput.setText(place.name, false)
        refreshSavedPlaceButton()
        Snackbar.make(binding.coordinator, getString(R.string.place_saved, place.name),
            Snackbar.LENGTH_SHORT).show()
    }

    private fun removeSavedPlace(place: SavedPlace) {
        savedPlaces.remove(place)
        // The journey on screen keeps its destination, now as a plain pin that still replans
        // without the name.
        val pin = MapPin.format(place.latitude, place.longitude)
        val wasShownByName = SavedPlaces.key(binding.destinationInput.text.toString()) ==
            SavedPlaces.key(place.name)
        if (wasShownByName) {
            lastGeocodedAddress = pin to (place.latitude to place.longitude)
            binding.destinationInput.setText(pin, false)
        }
        refreshSavedPlaceButton()
        Snackbar.make(binding.coordinator, getString(R.string.place_removed, place.name),
            Snackbar.LENGTH_LONG).setAction(R.string.undo) {
            if (savedPlaces.save(place) != null) return@setAction
            if (binding.destinationInput.text.toString().trim() == pin) onPlaceSaved(place)
            else refreshSavedPlaceButton()
        }.show()
    }

    private fun setupSearchPresets() {
        val preferences = getSharedPreferences(JOURNEY_STORE, MODE_PRIVATE)
        includeTrains = preferences.getBoolean(INCLUDE_TRAINS, true)
        binding.includeTrainsSwitch.isChecked = includeTrains
        binding.includeTrainsSwitch.setOnCheckedChangeListener { _, checked ->
            includeTrains = checked
            preferences.edit().putBoolean(INCLUDE_TRAINS, checked).apply()
            replanForPreset()
        }
    }

    /** The few things that are not about one journey, in a side menu rather than hidden behind gestures. */
    private fun setupAppMenu() {
        binding.menuButton.setOnClickListener { binding.drawer.openDrawer(GravityCompat.START) }
        binding.sideMenu.setNavigationItemSelectedListener { item ->
            binding.drawer.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.menuSavedPlaces -> showAllSavedPlaces()
                R.id.menuHowTo -> showHints()
                R.id.menuUpdates -> checkForUpdate(asked = true)
                R.id.menuCredits -> Credits.show(this)
            }
            // Each item opens something; none stays selected in the menu.
            false
        }
    }

    /**
     * The From/To card needs a journey to describe, and goes with it. While one is open it stays laid
     * out, unseen until the sheet is pulled up, so the sheet's top never has to move.
     */
    private fun updateRouteCard() {
        // A place that was never found is no destination to show.
        val journey = binding.journeyCard.visibility == View.VISIBLE && directDestination != null &&
            !binding.destinationInput.text.isNullOrBlank()
        if (!journey) {
            binding.routeCard.visibility = View.GONE
            binding.searchCard.visibility = View.VISIBLE
            binding.searchCard.alpha = 1f
        } else if (binding.routeCard.visibility == View.GONE) {
            binding.routeCard.visibility = View.INVISIBLE
            revealRouteCard(if (journeySheet.state == BottomSheetBehavior.STATE_EXPANDED) 1f else 0f)
        }
        val start = customStart?.name ?: myLocationLabel
        val iconSize = binding.routeFrom.textSize * 1.3f
        binding.routeFrom.text = start
        binding.routeFrom.contentDescription = getString(R.string.start_change, start)
        binding.routeFrom.setCompoundDrawablesRelative(sizedIcon(
            if (customStart == null) R.drawable.ic_my_location else R.drawable.ic_place, iconSize, R.color.deep_green),
            null, null, null)
        val destination = binding.destinationInput.text.toString().substringBefore(" · ")
        binding.routeTo.text = destination
        binding.routeTo.contentDescription = getString(R.string.route_change_destination, destination)
        binding.routeTo.setCompoundDrawablesRelative(sizedIcon(R.drawable.ic_place, iconSize, R.color.warning),
            null, null, null)
    }

    private fun setupRouteCard() {
        binding.routeFrom.setOnClickListener(::showStartMenu)
        binding.routeTo.setOnClickListener { editDestination() }
        binding.swapStartButton.setOnClickListener { swapStartAndDestination() }
        binding.routeBackButton.setOnClickListener { journeySheet.state = BottomSheetBehavior.STATE_COLLAPSED }
    }

    /** Back to the search bar, with the destination ready to be typed over. */
    private fun editDestination() {
        journeySheet.state = BottomSheetBehavior.STATE_COLLAPSED
        binding.destinationInput.requestFocus()
        binding.destinationInput.selectAll()
        getSystemService(InputMethodManager::class.java)
            .showSoftInput(binding.destinationInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Where the journey can start from, in a menu under [anchor]. */
    private fun showStartMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(1, 1, 0, R.string.start_my_location).apply {
                isCheckable = true
                isChecked = customStart == null
            }
            menu.add(1, 2, 1, R.string.start_pick_saved)
            menu.add(1, 3, 2, R.string.start_pick_search)
            if (recentSuggestions().isNotEmpty()) menu.add(1, 4, 3, R.string.start_pick_recent)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> chooseStart(null)
                    2 -> pickSavedStart()
                    4 -> pickRecentStart()
                    else -> searchForStart()
                }
                true
            }
            show()
        }
    }

    /** Plans the journey again from [start], or from the rider's own position when it is null. */
    private fun chooseStart(start: CustomStart?) {
        if (start == customStart) return
        customStart = start
        updateRouteCard()
        replanForPreset()
    }

    private fun pickSavedStart() {
        val places = savedPlaces.all()
        if (places.isEmpty()) {
            Snackbar.make(binding.coordinator, R.string.no_saved_places, Snackbar.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.saved_places_title)
            .setItems(places.map { it.label }.toTypedArray()) { _, position ->
                chooseStart(CustomStart(places[position].name,
                    GeoPoint(places[position].latitude, places[position].longitude)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickRecentStart() {
        val places = recentSuggestions()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.start_pick_recent)
            .setItems(places.map { it.name }.toTypedArray()) { _, position ->
                chooseStart(CustomStart(places[position].name,
                    GeoPoint(places[position].latitude, places[position].longitude)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun searchForStart() {
        val form = DialogPickStartBinding.inflate(layoutInflater)
        val adapter = placeSuggestions()
        form.pickStartInput.setAdapter(adapter)
        form.pickStartInput.threshold = 2
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.start_pick_search)
            .setView(form.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun pick(place: OfflinePlace) {
            dialog.dismiss()
            chooseStart(CustomStart(place.name, GeoPoint(place.latitude, place.longitude)))
        }
        form.pickStartInput.setOnItemClickListener { _, _, position, _ -> adapter.getItem(position)?.let(::pick) }
        form.pickStartInput.setOnEditorActionListener { _, _, _ ->
            if (adapter.count > 0) adapter.getItem(0)?.let(::pick)
            true
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        // Nothing typed offers the saved places and recent destinations, as the search box does.
        form.pickStartInput.setOnClickListener {
            if (form.pickStartInput.text.isNullOrBlank()) adapter.filter.filter("") { form.pickStartInput.showDropDown() }
        }
    }

    /** The destination becomes the start and the start the destination, then the journey is planned again. */
    private fun swapStartAndDestination() {
        val point = directDestination ?: return
        val typed = binding.destinationInput.text.toString().trim()
        val destination = StartSwap.Place(typed.substringBefore(" · "), point.latitude, point.longitude,
            isMyLocation = customStart != null && typed == myLocationLabel)
        val start = customStart?.let { StartSwap.Place(it.name, it.point.latitude, it.point.longitude) }
        lifecycleScope.launch {
            // Only a swap away from My location needs a fix, to make it the destination.
            val here = if (start != null) null else {
                val fix = obtainLocation()
                if (fix == null) {
                    showLocationHint()
                    return@launch
                }
                StartSwap.Place(myLocationLabel, fix.latitude, fix.longitude, isMyLocation = true)
            }
            val (newStart, newDestination) = StartSwap.swap(start, destination, here) ?: return@launch
            customStart = newStart?.let { CustomStart(it.name, GeoPoint(it.latitude, it.longitude)) }
            val spot = newDestination.latitude to newDestination.longitude
            lastGeocodedAddress = newDestination.name to spot
            lastResolvedPlace = null
            binding.destinationInput.dismissDropDown()
            binding.destinationInput.setText(newDestination.name, false)
            showDestination(OfflinePlace(newDestination.name, newDestination.name,
                spot.first, spot.second, "pin", true))
        }
    }

    private fun replanForPreset() {
        if (!binding.destinationInput.text.isNullOrBlank() &&
            (directDestination != null || planningJob?.isActive == true)) findDestinationRoute()
    }

    private fun findDestinationRoute() {
        val destination = binding.destinationInput.text.toString().trim()
        val searchTrains = includeTrains
        val preference = walkingPreference
        if (destination.isBlank()) {
            binding.destinationLayout.error = getString(R.string.enter_destination)
            binding.destinationInput.requestFocus()
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(binding.destinationInput, InputMethodManager.SHOW_IMPLICIT)
            return
        }
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        planningJob?.cancel()
        planningJob = lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            binding.searchProgress.visibility = View.VISIBLE
            journeyOptions = emptyList()
            showOptionsRow(false)
            journeyChoice = null
            trainJourneyChoice = null
            mixedJourneyChoice = null
            walkingFromOrigin = null
            walkingFromDestination = null
            selectedRoute = null
            selectedPattern = null
            highlightedVehicleId = null
            directDestination = lastGeocodedAddress?.takeIf { it.first == destination }?.second
                ?.let { GeoPoint(it.first, it.second) }
            showJourneyMessage(getString(R.string.finding_address))
            binding.journeyTitle.setText(R.string.planning_journey)
            renderVehicles()
            try {
                coroutineScope {
                    val chosenStart = customStart
                    val originTask = async {
                        timed("location") {
                            chosenStart?.let { Location("custom").apply {
                                latitude = it.point.latitude
                                longitude = it.point.longitude
                            } } ?: obtainLocation()
                        }
                    }
                    val addressTask = async { timed("geocode") { geocode(destination) } }
                    val vehiclesTask = async {
                        timed("vehicles") {
                            try {
                                Result.success(inService(repository.vehicles()))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Result.failure<List<LiveVehicle>>(error)
                            }
                        }
                    }
                    val metroAlertsTask = async {
                        try {
                            repository.metroAlerts()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w(TAG, "Metro alerts unavailable", error)
                            emptyList()
                        }
                    }
                    val address = addressTask.await()
                    if (address == null) {
                        originTask.cancel()
                        vehiclesTask.cancel()
                        metroAlertsTask.cancel()
                        showJourneyMessage(getString(R.string.address_not_found))
                        binding.destinationInput.showDropDown()
                        joinSearchBarToSuggestions()
                        return@coroutineScope
                    }
                    directDestination = GeoPoint(address.first, address.second)
                    refreshSavedPlaceButton()
                    renderVehicles()
                    binding.map.controller.animateTo(directDestination)
                    val origin = originTask.await()
                    if (origin == null) {
                        vehiclesTask.cancel()
                        metroAlertsTask.cancel()
                        showLocationHint()
                        return@coroutineScope
                    }
                    // One origin snapshot is used for both planners and their walking legs.
                    if (chosenStart == null) currentLocation = GeoPoint(origin.latitude, origin.longitude)
                    val walkingTask = async(Dispatchers.Default) {
                        timed("walking") {
                            val city = offlineCity
                            city.walkingDistancesFrom(origin.latitude, origin.longitude) to
                                city.walkingDistancesFrom(address.first, address.second)
                        }
                    }
                    val planningTime = TrainIndex.now()
                    val trainTask = async(Dispatchers.Default) {
                        timed("trains") {
                            // The metro is always on offer; the trains only when the rider wants them.
                            val scheduled = if (searchTrains) trainIndex else trainIndex.metroOnly
                            val (fromOrigin, fromDestination) = walkingTask.await()
                            scheduled.planScheduledJourney(
                                origin.latitude, origin.longitude, address.first, address.second,
                                now = planningTime,
                                walkFromOrigin = fromOrigin::estimatedMetresTo,
                                walkFromDestination = fromDestination::estimatedMetresTo,
                                walkingPreference = preference
                            )
                        }
                    }
                    val vehicleResult = vehiclesTask.await()
                    val journeyVehicles = vehicleResult.getOrDefault(emptyList())
                    liveVehicles = journeyVehicles
                    if (vehicleResult.isSuccess) updateLiveStatus()
                    else setLiveStatus(getString(R.string.feed_unavailable), R.color.warning)
                    var shown = false
                    var hasMatchedVehicles = false
                    val (fromOrigin, fromDestination) = walkingTask.await()
                    walkingFromOrigin = fromOrigin
                    walkingFromDestination = fromDestination
                    val walkSeconds = directWalkingDistance(address.first, address.second) /
                        WALKING_METRES_PER_SECOND
                    val options = withContext(Dispatchers.Default) {
                        timed("journeys") {
                            hasMatchedVehicles = journeyVehicles.any { transitIndex.matchDirection(it) != null }
                            journeyPlanner.plan(JourneyQuery(
                                origin.latitude, origin.longitude, address.first, address.second,
                                journeyVehicles,
                                walkFromOrigin = fromOrigin::estimatedMetresTo,
                                walkFromDestination = fromDestination::estimatedMetresTo,
                                walkBetweenStops = { startLat, startLon, endLat, endLon ->
                                    offlineCity.shortWalkMetres(startLat, startLon, endLat, endLon)
                                        ?: Float.POSITIVE_INFINITY
                                },
                                walkingPreference = preference,
                                trainsAt = planningTime,
                                includeTrains = searchTrains
                            ), onDirectJourney = { direct ->
                                withContext(Dispatchers.Main) {
                                    val trainChoice = trainTask.await()
                                        ?.takeIf { worthTaking(it.estimatedTotalSeconds, walkSeconds) }
                                    val bus = direct?.takeIf { worthTaking(it.estimatedTotalSeconds, walkSeconds) }
                                    if (bus != null || trainChoice != null) {
                                        displayJourney(destination, address, bus, trainChoice, frame = true)
                                        appendJourneyNote(getString(R.string.checking_transfers))
                                        shown = true
                                    }
                                }
                            })
                        }
                    }
                    metroAlerts = metroAlertsTask.await()
                    val plannedTrain = trainTask.await()
                    val trainChoice = plannedTrain?.takeIf { worthTaking(it.estimatedTotalSeconds, walkSeconds) }
                    val worthOptions = options.filter { worthTaking(it.totalSeconds, walkSeconds) }
                    val busChoice = worthOptions.firstOrNull { it.bus != null }?.bus
                    val mixedChoice = worthOptions.firstOrNull { it.mixed != null }?.mixed
                    if (busChoice == null && trainChoice == null && mixedChoice == null &&
                        (plannedTrain != null || options.isNotEmpty())) {
                        showWalkingOnly(destination, address)
                    } else if (busChoice == null && trainChoice == null && mixedChoice == null) {
                        showJourneyMessage(getString(
                            if (vehicleResult.isFailure) R.string.journey_feed_unavailable
                            else if (liveVehicles.isEmpty()) R.string.no_live_vehicles_now
                            else if (!hasMatchedVehicles) R.string.live_directions_unavailable
                            else R.string.no_direct_route
                        ), null, MessageAction(R.string.retry) { refreshJourney() })
                    } else {
                        val changed = mixedChoice != mixedJourneyChoice ||
                            (busChoice != journeyChoice && trainJourneyChoice == null)
                        showJourneyOptions(destination, address, worthOptions, trainChoice, frame = !shown || changed)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Journey planning failed", error)
                showJourneyMessage(getString(R.string.journey_failed), null,
                    MessageAction(R.string.retry) { refreshJourney() })
            } finally {
                if (currentCoroutineContext()[Job] == planningJob) {
                    binding.searchProgress.visibility = View.GONE
                    automaticReplanPending = false
                }
                Log.d(TAG, "Journey total: ${SystemClock.elapsedRealtime() - started} ms")
            }
        }
    }

    private suspend fun <T> timed(label: String, block: suspend () -> T): T {
        val started = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            Log.d(TAG, "$label: ${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    private fun displayJourney(
        destination: String,
        address: Pair<Double, Double>,
        busChoice: JourneyChoice?,
        trainChoice: ScheduledTrainJourney?,
        frame: Boolean
    ) = displayJourney(destination, address, busChoice, trainChoice, null, frame)

    /**
     * Shows the best journey among [options] (or the train, if that is better), with a chip for
     * each alternative the planner offers beside it.
     */
    private fun showJourneyOptions(
        destination: String,
        address: Pair<Double, Double>,
        options: List<JourneyOption>,
        trainChoice: ScheduledTrainJourney?,
        frame: Boolean
    ) {
        journeyOptions = options
        plannedDestination = destination to address
        displayJourney(destination, address, options.firstOrNull { it.bus != null }?.bus, trainChoice,
            options.firstOrNull { it.mixed != null }?.mixed, frame)
        selectedOption = options.indexOfFirst { option ->
            if (mixedJourneyChoice != null) option.mixed == mixedJourneyChoice
            else trainJourneyChoice == null && option.bus != null && option.bus == journeyChoice
        }
        renderJourneyOptionChips()
    }

    /** The rider picked another of the planner's options: show it, whatever the train would do. */
    private fun selectJourneyOption(index: Int) {
        val option = journeyOptions.getOrNull(index) ?: return
        val (destination, address) = plannedDestination ?: return
        if (index == selectedOption) return
        displayJourney(destination, address, option.bus, null, option.mixed, frame = true, remember = false)
        selectedOption = index
        renderJourneyOptionChips()
    }

    /** The other journeys on offer and the label that names them come and go together. */
    private fun showOptionsRow(show: Boolean) {
        binding.journeyOptions.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun renderJourneyOptionChips() {
        val group = binding.journeyOptions
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        // Not while a train alone is showing (selectedOption is -1 then), nor for a single option.
        val show = journeyOptions.size > 1 && selectedOption >= 0
        showOptionsRow(show)
        if (!show) return
        journeyOptions.forEachIndexed { index, option ->
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = getString(R.string.option_label, getString(when (option.reason) {
                    OptionReason.BEST -> R.string.option_best
                    OptionReason.FEWEST_CHANGES -> R.string.option_fewest_changes
                    OptionReason.FASTEST -> R.string.option_fastest
                    OptionReason.LEAST_WALKING -> R.string.option_least_walking
                }), minutes(option.totalSeconds))
                isCheckable = true
                isChecked = index == selectedOption
            }
            group.addView(chip)
            chip.setOnClickListener { selectJourneyOption(index) }
        }
    }

    private fun displayJourney(
        destination: String,
        address: Pair<Double, Double>,
        busChoice: JourneyChoice?,
        trainChoice: ScheduledTrainJourney?,
        mixedChoice: MixedJourney?,
        frame: Boolean
    ) = displayJourney(destination, address, busChoice, trainChoice, mixedChoice, frame, remember = true)

    private fun displayJourney(
        destination: String,
        address: Pair<Double, Double>,
        busChoice: JourneyChoice?,
        trainChoice: ScheduledTrainJourney?,
        mixedChoice: MixedJourney?,
        frame: Boolean,
        remember: Boolean
    ) {
        binding.destinationLayout.error = null
        binding.locationSettingsButton.visibility = View.GONE
        // Where the rider came from is not a place to remember going to.
        if (remember && destination.trim() != myLocationLabel) {
            recentDestinations.add(destination, address.first, address.second)
        }
        val busScore = busChoice?.let {
            walkingPreference.score(it.estimatedTotalSeconds, it.totalWalkingMetres)
        } ?: Float.POSITIVE_INFINITY
        val trainScore = trainChoice?.let {
            walkingPreference.score(it.estimatedTotalSeconds, it.totalWalkingMetres)
        } ?: Float.POSITIVE_INFINITY
        val mixedScore = mixedChoice?.let {
            walkingPreference.score(it.estimatedTotalSeconds, it.totalWalkingMetres)
        } ?: Float.POSITIVE_INFINITY
        val useMixed = mixedChoice != null && mixedScore < minOf(busScore, trainScore)
        val useTrain = !useMixed && trainChoice != null && trainScore < busScore
        mixedJourneyChoice = if (useMixed) mixedChoice else null
        missingBusRefreshes = 0
        journeyChoice = if (useMixed) mixedChoice?.bus else if (useTrain) null else busChoice
        trainJourneyChoice = if (useMixed) mixedChoice?.train else if (useTrain) trainChoice else null
        val transitSeconds = mixedJourneyChoice?.estimatedTotalSeconds
            ?: trainJourneyChoice?.estimatedTotalSeconds
            ?: journeyChoice!!.estimatedTotalSeconds
        walkingMode = false
        directDestination = GeoPoint(address.first, address.second)
        directWalkingMetres = directWalkingDistance(address.first, address.second)
        directWalkingSeconds = directWalkingMetres / WALKING_METRES_PER_SECOND
        val choice = journeyChoice
        selectedRoute = choice?.pattern?.route
        selectedPattern = choice?.pattern
        highlightedVehicleId = null
        val wasShowingSteps = binding.journeyCard.visibility == View.VISIBLE &&
            binding.journeySteps.childCount > 0
        val guide = mixedJourneyChoice?.let { JourneySteps.forMixed(this, it, directDestination) }
            ?: trainJourneyChoice?.let { JourneySteps.forTrain(this, it, directDestination) }
            ?: JourneySteps.forBus(this, requireNotNull(choice))
        walkingIsFaster = directWalkingSeconds < transitSeconds
        // The timetable cannot know the metro is shut; GTT's notices can.
        val metroNotes = if (trainJourneyChoice?.isMetro == true) {
            metroAlerts.map { JourneyNote(R.drawable.ic_metro, getString(R.string.metro_alert_note, it)) }
        } else emptyList()
        transitGuide = guide.copy(notes = metroNotes + guide.notes + listOfNotNull(
            customStart?.let { JourneyNote(R.drawable.ic_alert_off, getString(R.string.planned_from_note)) }
                ?: if ((originLocation?.accuracy ?: 0f) > 200f) {
                    JourneyNote(R.drawable.ic_my_location, getString(R.string.location_approximate))
                } else null
        ))
        binding.journeyTitle.text = getString(R.string.transit_summary, minutes(transitSeconds))
        showSummary(transitSeconds)
        renderJourneyGuide(transitGuide!!)
        updateModeSwitch()
        binding.alertButton.visibility =
            if (choice != null && trainJourneyChoice == null && customStart == null) View.VISIBLE else View.GONE
        updateAlertButton()
        // A journey already pulled open stays open through live updates; a new one starts at the peek.
        showJourneySheet(if (wasShowingSteps) null else BottomSheetBehavior.STATE_COLLAPSED)
        // Quicker on foot: the journey says so and walks, with the ride a tap away. A ride the rider
        // chose anyway stays through live updates.
        if (walkingIsFaster && rideAnywayTo != directDestination) {
            selectWalkingMode(frame)
            return
        }
        renderVehicles()
        if (frame) frameCurrentJourney()
    }

    private suspend fun geocode(query: String): Pair<Double, Double>? {
        lastGeocodedAddress?.takeIf { it.first == query }?.let { return it.second }
        // The rider's own names come first, and need no bundled data to resolve.
        savedPlaces.byName(query)?.let { saved ->
            lastResolvedPlace = saved.asOfflinePlace(exactMatch = true)
            return (saved.latitude to saved.longitude).also { lastGeocodedAddress = query to it }
        }
        MapPin.parse(query)?.let { return it.also { spot -> lastGeocodedAddress = query to spot } }
        val matches = withContext(Dispatchers.IO) {
            offlineCity.search(query, currentLocation.latitude, currentLocation.longitude)
        }
        val place = matches.firstOrNull { it.exactMatch } ?: matches.singleOrNull()
        if (place == null) return null
        lastResolvedPlace = place
        return (place.latitude to place.longitude).also { lastGeocodedAddress = query to it }
    }

    private fun startRefreshing(forceRefresh: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            var forceNextFetch = forceRefresh
            // Polling stops in the background (BusAlertService does its own) and fetches at once on return.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    while (isActive) {
                        delay(FRESHNESS_TICK_MILLIS)
                        if (liveStatusFetchedAt != null) updateLiveStatus()
                    }
                }
                while (isActive) {
                    fetchVehicles(forceNextFetch)
                    forceNextFetch = false
                    delay(15_000)
                }
            }
        }
    }

    /**
     * The buses in service among [vehicles]. The bus index is several megabytes of timetable: reading
     * it on the main thread held the splash screen for seconds, so it is read, and used, off it.
     */
    private suspend fun inService(vehicles: List<LiveVehicle>) =
        withContext(Dispatchers.Default) { transitIndex.inService(vehicles) }

    /** Every feed shows how far each bus got since the last one; the planner's speeds learn from that. */
    private fun learnBusSpeeds(vehicles: List<LiveVehicle>) {
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching { transitIndex.trackVehicles(vehicles) }
            BusSpeedStore.save(applicationContext)
        }
    }

    private suspend fun fetchVehicles(forceRefresh: Boolean) {
        // The routine refresh keeps the label as it is: "Connecting…" every 15 s would read as trouble.
        // Only a first fetch, or one the rider asked for, says it is under way.
        if (forceRefresh || liveVehicles.isEmpty()) {
            liveStatusFetchedAt = null
            setLiveStatus(getString(R.string.connecting), R.color.ink)
        }
        runCatching { inService(repository.vehicles(forceRefresh)) }
            .onSuccess {
                liveVehicles = it
                liveStatusFetchedAt = SystemClock.elapsedRealtime()
                updateLiveStatus()
                warnIfDataOutOfDate(it)
                learnBusSpeeds(it)
                if (activeBusNeedsReplan(it)) {
                    requestAutomaticReplan()
                } else {
                    renderVehicles()
                }
                refreshStopArrivals()
            }
            .onFailure {
                if (it is CancellationException) return
                Log.e(TAG, "Unable to refresh GTT live positions", it)
                // One failed refresh does not empty the map: the last buses stay, fading as they age, until too old.
                liveVehicles = liveVehicles.filter { vehicle ->
                    vehicle.ageSeconds <= GttRepository.MAX_POSITION_AGE_SECONDS }
                liveStatusFetchedAt = null
                renderVehicles()
                refreshStopArrivals()
                setLiveStatus(getString(R.string.feed_unavailable), R.color.warning)
            }
    }

    /** Tells the rider once per app version that the bundled bus data no longer matches the feed. */
    private fun warnIfDataOutOfDate(vehicles: List<LiveVehicle>) {
        if (!transitIndex.looksOutOfDate(vehicles)) return
        val preferences = getSharedPreferences(JOURNEY_STORE, MODE_PRIVATE)
        if (preferences.getInt(STALE_NOTICE_VERSION, 0) == BuildConfig.VERSION_CODE) return
        preferences.edit().putInt(STALE_NOTICE_VERSION, BuildConfig.VERSION_CODE).apply()
        Snackbar.make(binding.coordinator, R.string.data_out_of_date, Snackbar.LENGTH_INDEFINITE)
            .setAction(android.R.string.ok) {}.show()
    }

    // A late bus is already faded on the map; the label only counts them.
    private fun updateLiveStatus() {
        val ago = liveStatusFetchedAt?.let { freshnessSuffix((SystemClock.elapsedRealtime() - it) / 1000) }.orEmpty()
        val status = if (liveVehicles.isEmpty()) getString(R.string.no_bus_positions)
            else getString(R.string.live_bus_count, liveVehicles.size)
        setLiveStatus(status + ago, if (liveVehicles.isEmpty()) R.color.warning else R.color.live_green)
    }

    /** " · updated 45 s ago"; quiet while the 15-second refresh keeps up, so it shows only when one is overdue. */
    private fun freshnessSuffix(seconds: Long): String = when {
        seconds < FRESHNESS_QUIET_SECONDS -> ""
        seconds < 60 -> " · " + getString(R.string.updated_seconds_ago, seconds.toInt())
        else -> " · " + getString(R.string.updated_minutes_ago, (seconds / 60).toInt())
    }

    private fun setLiveStatus(status: String, color: Int) {
        binding.liveStatus.text = status
        binding.liveStatus.setTextColor(getColor(color))
        // The refresh icon is always there, so the label reads as the button it is: a tap fetches the buses again.
        binding.liveStatus.setCompoundDrawablesRelative(null, null,
            sizedIcon(R.drawable.ic_refresh, binding.liveStatus.textSize * 1.2f, color), null)
        binding.liveStatus.contentDescription = getString(R.string.live_status_retry, status)
    }

    private fun activeBusNeedsReplan(vehicles: List<LiveVehicle>): Boolean {
        val journey = journeyChoice ?: return false
        if (walkingMode || mixedJourneyChoice?.busFirst == false ||
            automaticReplanPending || planningJob?.isActive == true) return false
        val vehicle = vehicles.firstOrNull { it.id == journey.vehicle.id }
        if (vehicle == null) {
            // One refresh without the bus is usually the feed hiccuping, not the bus leaving.
            return ++missingBusRefreshes >= MISSING_REFRESHES_BEFORE_REPLAN
        }
        missingBusRefreshes = 0
        val pattern = transitIndex.matchDirection(vehicle) ?: return true
        // A bus planned to turn around is still on its last run, not past the stop; give it a few
        // minutes beyond the timetable before deciding it went to the depot instead.
        journey.turnaround?.let {
            if (pattern.id == it.previousPatternId &&
                !it.overdue(System.currentTimeMillis(), TURNAROUND_GRACE_MILLIS)) return false
        }
        // Another variant of the same line can serve the stop just as well.
        return if (pattern.id == journey.pattern.id)
            !transitIndex.isStopIndexAhead(vehicle, pattern, journey.boardStopIndex)
        else !transitIndex.isBoardingStopAhead(vehicle, pattern, journey.boardAt.id)
    }

    private fun requestAutomaticReplan() {
        if (automaticReplanPending) return
        automaticReplanPending = true
        binding.sharedDirections.text = getString(R.string.bus_passed_replanning)
        binding.sharedDirections.visibility = View.VISIBLE
        findDestinationRoute()
    }

    private fun renderVehicles() {
        binding.map.overlays.removeAll { it is Marker || it is Polyline }
        val visible = visibleVehicles()
        // Underneath the journey line and every marker.
        drawHighlightedCourse(visible)
        // A highlighted course stands alone: the other buses are hidden until it is cleared.
        val markers = highlightedVehicleId?.let { id -> visible.filter { it.id == id } } ?: visible
        drawJourneyLine()
        if (journeyChoice == null && trainJourneyChoice == null) {
            directDestination?.let { addExactDestinationMarker(it.latitude, it.longitude) }
        }
        addYouMarker()
        // The same pin, so its bubble stays open through the redraw.
        askedSpot?.let { binding.map.overlays.add(it) }

        // The course's stop dots stay under the buses; only journey markers rise above them.
        val journeyMarkers = binding.map.overlays.filterIsInstance<Marker>().filter { it !in courseOverlays }
        val selectedIds = setOfNotNull(journeyChoice?.vehicle?.id, journeyChoice?.secondLeg?.vehicle?.id)

        markers.forEach { vehicle ->
            val vehicleRoute = normalizeRoute(vehicle.routeId)
            val marker = Marker(binding.map).apply {
                position = GeoPoint(vehicle.latitude, vehicle.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = getString(R.string.vehicle_title, vehicleRoute)
                snippet = getString(R.string.distance_away, distanceTo(vehicle).roundToInt()) +
                    " · " + getString(R.string.bus_position_age, (vehicle.ageSeconds + 59) / 60)
                alpha = if (vehicle.ageSeconds > 120) 0.65f else 1f
                relatedObject = vehicle
                setInfoWindow(markerBubble)
                icon = busDirectionMarker(vehicle.id in selectedIds, transitIndex.displayHeading(vehicle), vehicleRoute)
                setOnMarkerClickListener { clickedMarker, mapView ->
                    clearAskedSpot()
                    // Tapping the bus whose course is showing leaves the course again.
                    if (highlightedVehicleId == vehicle.id) {
                        clearHighlightedCourse()
                        return@setOnMarkerClickListener true
                    }
                    val pattern = transitIndex.matchDirection(vehicle)
                    val layover = transitIndex.layoverOf(vehicle)
                    val direction = when {
                        layover != null -> null
                        pattern == null -> getString(R.string.bus_direction_unavailable)
                        else -> getString(R.string.bus_destination, TransitIndex.readableCase(pattern.headsign))
                    }
                    // Waiting at the terminus, where it will go next and when is all there is to say.
                    val details = layover?.let {
                        val minutes = (it.departsInSeconds / 60f).roundToInt()
                        if (minutes < 1) getString(R.string.at_terminus_leaving)
                            else getString(R.string.at_terminus_leaves_in, minutes)
                    } ?: (getString(R.string.distance_away, distanceTo(vehicle).roundToInt()) +
                        " · " + getString(R.string.bus_position_age, (vehicle.ageSeconds + 59) / 60))
                    // Where it is going reads first, then how far and how fresh.
                    clickedMarker.subDescription = direction?.let(TextUtils::htmlEncode).orEmpty()
                    clickedMarker.snippet = TextUtils.htmlEncode(details)
                    highlightCourse(vehicle, pattern)
                    clickedMarker.showInfoWindow()
                    mapView.controller.animateTo(clickedMarker.position)
                    true
                }
            }
            binding.map.overlays.add(marker)
        }
        binding.map.overlays.removeAll(journeyMarkers.toSet())
        binding.map.overlays.addAll(journeyMarkers)
        binding.map.invalidate()
    }

    /** Opens the bubble of buses heading to [stop], over the point [offsetDp] above where it sits. */
    private fun showStopArrivals(stop: TransitStop, offsetDp: Int) {
        // One question or answer over the map at a time.
        clearAskedSpot()
        org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(binding.map)
        val window = stopWindow ?: StopArrivalsWindow(binding.map) {
            openStop = null
            stopJob?.cancel()
        }.also { stopWindow = it }
        openStop = stop
        window.render(TransitIndex.cleanStopName(stop.name), null)
        window.open(stop, GeoPoint(stop.latitude, stop.longitude), 0,
            -(offsetDp * resources.displayMetrics.density).roundToInt())
        refreshStopArrivals()
    }

    /** Redraws the open stop bubble from the latest buses; a no-op when none is open. */
    private fun refreshStopArrivals() {
        val stop = openStop ?: return
        val window = stopWindow ?: return
        stopJob?.cancel()
        stopJob = lifecycleScope.launch {
            val vehicles = liveVehicles
            val tracked = withContext(Dispatchers.Default) { transitIndex.trackVehicles(vehicles) }
            if (openStop == stop) {
                window.render(TransitIndex.cleanStopName(stop.name), StopArrivals.upcoming(tracked, stop.id))
            }
        }
    }

    private fun visibleVehicles(): List<LiveVehicle> {
        val selectedIds = setOfNotNull(journeyChoice?.vehicle?.id, journeyChoice?.secondLeg?.vehicle?.id)
        return when {
            walkingMode || (trainJourneyChoice != null && mixedJourneyChoice == null) -> emptyList()
            journeyChoice != null -> liveVehicles.filter { it.id in selectedIds }
            selectedRoute != null -> liveVehicles.filter { normalizeRoute(it.routeId) == selectedRoute }
            else -> liveVehicles
        }
    }

    private fun highlightCourse(vehicle: LiveVehicle, pattern: TransitPattern?) {
        removeCourseOverlays()
        highlightedVehicleId = vehicle.id.takeIf { pattern != null }
        if (pattern == null) return
        binding.map.overlays.removeAll { overlay ->
            val other = (overlay as? Marker)?.relatedObject as? LiveVehicle
            other != null && other.id != vehicle.id
        }
        // Slot the course under every journey line and marker already on the map.
        val at = binding.map.overlays.indexOfFirst { it is Marker || it is Polyline }
            .let { if (it < 0) binding.map.overlays.size else it }
        courseOverlays = buildCourse(vehicle, pattern)
        binding.map.overlays.addAll(at, courseOverlays)
        binding.map.invalidate()
        hintAtStopsOnce()
    }

    private fun clearHighlightedCourse() {
        highlightedVehicleId = null
        removeCourseOverlays()
        org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(binding.map)
        // Bring back the buses hidden while the course was highlighted.
        renderVehicles()
    }

    private fun removeCourseOverlays() {
        binding.map.overlays.removeAll(courseOverlays.toSet())
        courseOverlays = emptyList()
    }

    private fun drawHighlightedCourse(visible: List<LiveVehicle>) {
        val vehicle = visible.firstOrNull { it.id == highlightedVehicleId }
        val pattern = vehicle?.let(transitIndex::matchDirection)
        if (vehicle == null || pattern == null) {
            highlightedVehicleId = null
            courseOverlays = emptyList()
            return
        }
        courseOverlays = buildCourse(vehicle, pattern)
        binding.map.overlays.addAll(courseOverlays)
    }

    /**
     * The bus's whole course along its roads: the part still ahead solid, the part already driven
     * faded, and a small dot at every stop.
     */
    private fun buildCourse(vehicle: LiveVehicle, pattern: TransitPattern): List<Overlay> {
        val density = resources.displayMetrics.density
        val course = transitIndex.courseOf(vehicle, pattern) ?: return emptyList()
        val colour = getColor(R.color.course_magenta)
        val faded = ColorUtils.setAlphaComponent(colour, 110)

        fun line(points: List<GeoPoint>, color: Int, widthDp: Float) = mapLine().apply {
            setPoints(points)
            outlinePaint.color = color
            outlinePaint.strokeWidth = widthDp * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }

        fun stopDot(stop: TransitStop, ring: Int) = Marker(binding.map).apply {
            position = GeoPoint(stop.latitude, stop.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = TransitIndex.cleanStopName(stop.name)
            relatedObject = stop
            setOnMarkerClickListener { _, _ ->
                showStopArrivals(stop, STOP_DOT_DP / 2)
                true
            }
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.map_face))
                setStroke((1.5f * density).roundToInt(), ring)
                val size = (STOP_DOT_DP * density).roundToInt()
                setSize(size, size)
            }
        }
        return buildList {
            if (course.passed.size >= 2) add(line(course.passed, faded, 3.5f))
            add(line(course.ahead, getColor(R.color.map_casing), 8f))
            add(line(course.ahead, colour, 5f))
            // Dots sit above the line but, like it, below every bus and journey marker.
            course.passedStops.forEach { add(stopDot(it, faded)) }
            course.aheadStops.forEach { add(stopDot(it, colour)) }
        }
    }

    /** The highlighted course's stop closest to [point] on screen, if it is within a fingertip's reach. */
    private fun courseStopNear(point: GeoPoint?): TransitStop? {
        if (point == null) return null
        val projection = binding.map.projection
        val tap = projection.toPixels(point, null)
        val reach = STOP_TAP_REACH_DP * resources.displayMetrics.density
        return courseOverlays.filterIsInstance<Marker>()
            .mapNotNull { dot ->
                val stop = dot.relatedObject as? TransitStop ?: return@mapNotNull null
                val at = projection.toPixels(dot.position, null)
                stop to hypot((at.x - tap.x).toFloat(), (at.y - tap.y).toFloat())
            }
            .filter { it.second <= reach }
            .minByOrNull { it.second }?.first
    }

    private fun drawJourneyLine() {
        if (walkingMode) {
            val destination = directDestination ?: return
            addWalkingConnector(startPoint, destination)
            addExactDestinationMarker(destination.latitude, destination.longitude)
            return
        }
        mixedJourneyChoice?.let { mixed ->
            val station = if (mixed.busFirst) mixed.train.boardAt else mixed.train.destination
            val stationPoint = GeoPoint(station.latitude, station.longitude)
            val destination = directDestination ?: stationPoint
            if (mixed.busFirst) {
                drawBusJourneyLine(mixed.bus, startPoint, stationPoint, false)
                drawTrainJourneyLine(mixed.train, stationPoint, destination, true)
            } else {
                drawTrainJourneyLine(mixed.train, startPoint, stationPoint, false)
                drawBusJourneyLine(mixed.bus, stationPoint, destination, true)
            }
            return
        }
        trainJourneyChoice?.let { train ->
            drawTrainJourneyLine(train, startPoint,
                directDestination ?: GeoPoint(train.destination.latitude, train.destination.longitude), true)
            return
        }
        val choice = journeyChoice ?: return
        drawBusJourneyLine(choice, startPoint,
            GeoPoint(choice.finalLatitude, choice.finalLongitude), true)
    }

    private fun drawBusJourneyLine(choice: JourneyChoice, origin: GeoPoint,
                                   destination: GeoPoint, showFinalDestination: Boolean) {
        val points = journeyPoints(choice)
        addWalkingConnector(
            origin,
            GeoPoint(choice.boardAt.latitude, choice.boardAt.longitude)
        )
        addWalkingConnector(
            GeoPoint(choice.destination.latitude, choice.destination.longitude),
            destination
        )
        if (points.size >= 2) {
            // A white casing keeps the route leg readable over streets and labels.
            binding.map.overlays.add(mapLine().apply {
                setPoints(points)
                outlinePaint.color = getColor(R.color.map_casing)
                outlinePaint.strokeWidth = 6f * resources.displayMetrics.density
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            })
            binding.map.overlays.add(mapLine().apply {
                setPoints(points)
                outlinePaint.color = getColor(R.color.route_orange)
                outlinePaint.strokeWidth = 3.5f * resources.displayMetrics.density
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            })

            addJourneyStopMarker(
                choice.boardAt,
                getString(R.string.board_here),
                getColor(R.color.deep_green)
            )
            choice.transferAt?.let {
                addJourneyStopMarker(
                    it,
                    getString(R.string.intermediate_destination),
                    getColor(R.color.route_orange)
                )
            }
        }

        val secondPoints = secondLegPoints(choice)
        if (secondPoints.size >= 2) {
            choice.secondLeg?.let { leg ->
                choice.transferAt?.let { transfer ->
                    addWalkingConnector(GeoPoint(transfer.latitude, transfer.longitude),
                        GeoPoint(leg.boardAt.latitude, leg.boardAt.longitude))
                }
            }
            for ((colour, width) in listOf(getColor(R.color.map_casing) to 6f, getColor(R.color.deep_green) to 3.5f)) {
                binding.map.overlays.add(mapLine().apply {
                    setPoints(secondPoints)
                    outlinePaint.color = colour
                    outlinePaint.strokeWidth = width * resources.displayMetrics.density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                })
            }
        }

        addJourneyStopMarker(
            choice.destination,
            getString(R.string.destination_stop),
            getColor(R.color.deep_green),
            sizeDp = 18
        )
        if (showFinalDestination) addExactDestinationMarker(destination.latitude, destination.longitude)
    }

    private fun drawTrainJourneyLine(train: ScheduledTrainJourney, origin: GeoPoint,
                                     destination: GeoPoint, showFinalDestination: Boolean) {
        val points = trainPoints(train)
        addWalkingConnector(origin, GeoPoint(train.boardAt.latitude, train.boardAt.longitude))
        addWalkingConnector(
            GeoPoint(train.destination.latitude, train.destination.longitude),
            destination
        )
        val colour = getColor(if (train.isMetro) R.color.metro_red else R.color.train_blue)
        if (points.size >= 2) {
            binding.map.overlays.add(mapLine().apply {
                setPoints(points)
                outlinePaint.color = getColor(R.color.map_casing)
                outlinePaint.strokeWidth = 6f * resources.displayMetrics.density
                outlinePaint.strokeCap = Paint.Cap.ROUND
            })
            binding.map.overlays.add(mapLine().apply {
                setPoints(points)
                outlinePaint.color = colour
                outlinePaint.strokeWidth = 3.5f * resources.displayMetrics.density
                outlinePaint.strokeCap = Paint.Cap.ROUND
            })
        }
        addJourneyStopMarker(train.boardAt.asTransitStop(),
            getString(if (train.isMetro) R.string.board_metro_here else R.string.board_train_here), colour, busStop = false)
        addJourneyStopMarker(train.destination.asTransitStop(),
            getString(if (train.isMetro) R.string.exit_metro_here else R.string.exit_train_here), colour, 18, busStop = false)
        if (showFinalDestination) addExactDestinationMarker(destination.latitude, destination.longitude)
    }

    private fun addWalkingConnector(from: GeoPoint, to: GeoPoint) {
        walkingPath(from, to)?.takeIf { it.size >= 2 }?.let(::addWalkingPath)
    }

    private fun walkingPath(from: GeoPoint, to: GeoPoint): List<GeoPoint>? {
        val route = when {
            from.latitude == startPoint.latitude &&
                from.longitude == startPoint.longitude ->
                walkingFromOrigin?.routeTo(to.latitude, to.longitude)
            to.latitude == directDestination?.latitude &&
                to.longitude == directDestination?.longitude ->
                walkingFromDestination?.routeTo(from.latitude, from.longitude)
                    ?.let { it.copy(points = it.points.asReversed()) }
            else -> offlineCity.shortWalkRoute(from.latitude, from.longitude,
                to.latitude, to.longitude)
        } ?: return null
        return route.points.map { GeoPoint(it.latitude, it.longitude) }
    }

    private fun addWalkingPath(points: List<GeoPoint>) {
        val density = resources.displayMetrics.density
        val dash = DashPathEffect(floatArrayOf(9f * density, 7f * density), 0f)
        binding.map.overlays.add(mapLine().apply {
            setPoints(points)
            outlinePaint.color = getColor(R.color.map_casing)
            outlinePaint.strokeWidth = 5f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.pathEffect = dash
        })
        binding.map.overlays.add(mapLine().apply {
            setPoints(points)
            outlinePaint.color = getColor(R.color.deep_green)
            outlinePaint.strokeWidth = 2.5f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.pathEffect = dash
        })
    }

    /** The first bus's ride, along its roads like a highlighted course. */
    private fun journeyPoints(choice: JourneyChoice): List<GeoPoint> =
        transitIndex.pathBetween(choice.pattern, choice.boardStopIndex, choice.boardStopIndex + choice.stopCount)

    private fun secondLegPoints(choice: JourneyChoice): List<GeoPoint> {
        val leg = choice.secondLeg ?: return emptyList()
        val stops = leg.pattern.stops
        // A line that loops calls at a stop twice: board where the ride ends at the right stop.
        val start = stops.indices.firstOrNull {
            stops[it].id == leg.boardAt.id && stops.getOrNull(it + leg.stopCount)?.id == leg.exitAt.id
        } ?: return emptyList()
        return transitIndex.pathBetween(leg.pattern, start, start + leg.stopCount)
    }

    /** A ride on rails: the metro follows its tunnel; trains, whose tracks the app lacks, go station to station. */
    private fun trainPoints(train: ScheduledTrainJourney): List<GeoPoint> =
        train.takeIf { it.isMetro }?.let {
            transitIndex.pathAlongRoute(it.number, it.boardAt.id, it.destination.id)
        } ?: train.stops.subList(train.boardIndex, train.destinationIndex + 1)
            .map { GeoPoint(it.latitude, it.longitude) }

    private fun addJourneyStopMarker(
        stop: TransitStop,
        label: String,
        color: Int,
        sizeDp: Int = 14,
        busStop: Boolean = true
    ) {
        binding.map.overlays.add(Marker(binding.map).apply {
            position = GeoPoint(stop.latitude, stop.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = label
            snippet = TransitIndex.cleanStopName(stop.name)
            setInfoWindow(markerBubble)
            // Buses can be listed only for a bus stop; a station keeps its plain label.
            if (busStop) setOnMarkerClickListener { _, _ ->
                showStopArrivals(stop, sizeDp)
                true
            }
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke((2 * resources.displayMetrics.density).roundToInt(), getColor(R.color.map_casing))
                val size = (sizeDp * resources.displayMetrics.density).roundToInt()
                setSize(size, size)
            }
        })
    }

    /**
     * A line drawn on the map. Left alone, osmdroid opens a bubble with no words in it where a
     * line is tapped; here the tap passes through to whatever lies under it, or the map.
     */
    private fun mapLine() = Polyline(binding.map).apply {
        setOnClickListener { _, _, _ -> false }
    }

    private fun destinationPinIcon() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(getColor(R.color.lime))
        setStroke((4 * resources.displayMetrics.density).roundToInt(), getColor(R.color.deep_green))
        val size = (28 * resources.displayMetrics.density).roundToInt()
        setSize(size, size)
    }

    private fun addExactDestinationMarker(latitude: Double, longitude: Double) {
        binding.map.overlays.add(Marker(binding.map).apply {
            position = GeoPoint(latitude, longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = getString(R.string.final_destination)
            setInfoWindow(markerBubble)
            snippet = binding.destinationInput.text.toString()
            icon = destinationPinIcon()
        })
    }

    private fun frameCurrentJourney() {
        val destination = directDestination ?: return
        val walkingPoints = if (walkingMode) {
            walkingPath(startPoint, destination).orEmpty()
        } else if (mixedJourneyChoice != null) {
            emptyList()
        } else {
            trainJourneyChoice?.let { train ->
                walkingPath(startPoint, GeoPoint(train.boardAt.latitude, train.boardAt.longitude)).orEmpty() +
                    walkingPath(GeoPoint(train.destination.latitude, train.destination.longitude), destination).orEmpty()
            } ?: journeyChoice?.let { choice ->
                walkingPath(startPoint, GeoPoint(choice.boardAt.latitude, choice.boardAt.longitude)).orEmpty() +
                    walkingPath(GeoPoint(choice.destination.latitude, choice.destination.longitude), destination).orEmpty() +
                    (choice.secondLeg?.let { leg ->
                        choice.transferAt?.let { transfer ->
                            walkingPath(GeoPoint(transfer.latitude, transfer.longitude),
                                GeoPoint(leg.boardAt.latitude, leg.boardAt.longitude))
                        }
                    }.orEmpty())
            }.orEmpty()
        }
        val transitPoints = if (walkingMode) emptyList() else {
            mixedJourneyChoice?.let { mixed ->
                journeyPoints(mixed.bus) + trainPoints(mixed.train)
            } ?: trainJourneyChoice?.let(::trainPoints) ?: journeyChoice?.let { choice ->
                journeyPoints(choice) + secondLegPoints(choice)
            }.orEmpty()
        }
        val points = transitPoints + walkingPoints + listOf(startPoint, destination)
        binding.topControls.doOnLayout {
            binding.map.controller.stopAnimation(false)
            val padding = (32 * resources.displayMetrics.density).roundToInt()
            val top = binding.topControls.height
            val bottom = journeyInset()
            val box = BoundingBox.fromGeoPoints(points)
            val width = (binding.map.width - 2 * padding).coerceAtLeast(1)
            val height = (binding.map.height - top - bottom - 2 * padding).coerceAtLeast(1)
            val zoom = org.osmdroid.views.MapView.getTileSystem().getBoundingBoxZoom(box, width, height)
                .coerceIn(binding.map.minZoomLevel, 18.0)
            updateMapInsets()
            binding.map.controller.setZoom(kotlin.math.floor(zoom))
            binding.map.controller.setCenter(box.centerWithDateLine)
        }
    }

    private fun showJourneyMessage(message: String) = showJourneyMessage(message, null)

    /** A message in the sheet, with the one button that gets past it when there is one. */
    private fun showJourneyMessage(message: String, @DrawableRes icon: Int?, action: MessageAction? = null) {
        hideTravelModeChoices()
        transitGuide = null
        binding.journeySteps.removeAllViews()
        binding.journeyTitle.setText(R.string.your_journey)
        binding.journeySummary.visibility = View.GONE
        messageAction = action
        binding.locationSettingsButton.visibility = if (action == null) View.GONE else View.VISIBLE
        action?.let { binding.locationSettingsButton.setText(it.label) }
        binding.destinationLayout.error = null
        binding.sharedDirections.text = icon?.let { notesText(listOf(JourneyNote(it, message))) } ?: message
        binding.sharedDirections.visibility = View.VISIBLE
        // A message is short, so show all of it rather than a clipped peek.
        showJourneySheet(BottomSheetBehavior.STATE_EXPANDED)
    }

    private fun requestAlert() {
        trainJourneyChoice?.let { train ->
            showJourneyMessage(getString(
                if (train.isMetro) R.string.metro_alert_unavailable else R.string.train_alert_unavailable))
            return
        }
        if (selectedPattern == null) {
            binding.sharedDirections.text = getString(R.string.alert_requires_direction)
            binding.sharedDirections.visibility = View.VISIBLE
            return
        }
        chooseAlertDistance {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                showNotificationsBlocked()
            } else {
                enableAlert()
            }
        }
    }

    private fun showNotificationsBlocked() {
        binding.sharedDirections.text = getString(R.string.notifications_blocked)
        binding.sharedDirections.visibility = View.VISIBLE
    }

    /** Whether the journey on screen is the one an alert is running for. */
    private fun isAlertActive(): Boolean {
        val pattern = selectedPattern ?: return false
        return AlertPreferences.load(this)?.patternId == pattern.id
    }

    private fun stopAlert() {
        AlertPreferences.clear(this)
        stopService(Intent(this, BusAlertService::class.java))
        updateAlertButton()
    }

    // The alert can end on its own, or from its notification, while the app is away.
    private fun updateAlertButton() {
        binding.alertButton.setText(if (isAlertActive()) R.string.stop_alert else R.string.alert_me)
    }

    /** Asks how far away the bus should be when it notifies; the last choice is preselected. */
    private fun chooseAlertDistance(onChosen: () -> Unit) {
        val options = AlertPreferences.DISTANCES
        var selected = options.indexOf(AlertPreferences.distance(this))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.alert_distance_title)
            .setSingleChoiceItems(
                options.map { getString(R.string.alert_distance_option, it) }.toTypedArray(), selected
            ) { _, which -> selected = which }
            .setPositiveButton(R.string.alert_me) { _, _ ->
                AlertPreferences.setDistance(this, options[selected])
                onChosen()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun enableAlert() {
        if (!hasLocationPermission()) {
            requestLocation()
            return
        }
        planningJob?.cancel()
        val pattern = selectedPattern ?: return
        val stop = journeyChoice?.boardAt ?: pattern.stops.minByOrNull {
            val result = FloatArray(1)
            Location.distanceBetween(
                currentLocation.latitude, currentLocation.longitude,
                it.latitude, it.longitude, result
            )
            result[0]
        } ?: return
        AlertPreferences.save(
            this,
            BusAlert(
                route = pattern.route,
                routeId = pattern.routeId,
                patternId = pattern.id,
                headsign = pattern.headsign,
                vehicleId = journeyChoice?.vehicle?.id,
                stopId = stop.id,
                stopName = TransitIndex.cleanStopName(stop.name),
                latitude = stop.latitude,
                longitude = stop.longitude,
                thresholdMetres = AlertPreferences.distance(this),
                finalAddress = binding.destinationInput.text.toString().trim()
                    .takeIf { it.isNotEmpty() },
                finalLatitude = journeyChoice?.finalLatitude,
                finalLongitude = journeyChoice?.finalLongitude,
                transferName = journeyChoice?.transferAt?.let {
                    TransitIndex.cleanStopName(it.name)
                },
                transferLatitude = journeyChoice?.transferAt?.latitude,
                transferLongitude = journeyChoice?.transferAt?.longitude
            )
        )
        ContextCompat.startForegroundService(this, Intent(this, BusAlertService::class.java))
        updateAlertButton()
    }

    private fun addYouMarker() {
        customStart?.let { start ->
            binding.map.overlays.add(Marker(binding.map).apply {
                position = start.point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = start.name
                setInfoWindow(markerBubble)
                icon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(R.color.deep_green))
                    setStroke((3 * resources.displayMetrics.density).roundToInt(), getColor(R.color.map_casing))
                    val size = (20 * resources.displayMetrics.density).roundToInt()
                    setSize(size, size)
                }
            })
            return
        }
        val location = originLocation ?: return
        binding.map.overlays.add(Marker(binding.map).apply {
            position = GeoPoint(location.latitude, location.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = getString(R.string.you_are_here)
            setInfoWindow(markerBubble)
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_my_location)
        })
    }

    private fun busDirectionMarker(closest: Boolean, bearing: Float?, route: String): BitmapDrawable {
        val bearingBucket = bearing?.let {
            (((it + 11.25f) / 22.5f).toInt() % 16)
        } ?: -1
        return busIconCache.getOrPut(Triple(closest, bearingBucket, route)) {
            createBusDirectionMarker(
                closest,
                bearingBucket.takeIf { it >= 0 }?.times(22.5f), route
            )
        }
    }

    private fun createBusDirectionMarker(closest: Boolean, bearing: Float?, route: String): BitmapDrawable {
        val density = resources.displayMetrics.density
        val lines = busLabelLines(route)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = busLabelTextDp(closest, lines) * density
        paint.textAlign = Paint.Align.CENTER
        // A long name makes the marker bigger, not the writing smaller.
        val ring = 2f * density
        val labelDiameter = 2f * (busLabelRadius(lines.map(paint::measureText), paint.textSize) +
            ring + 0.5f * density)
        val busDiameter = max((if (closest) 30f else 21f) * density, labelDiameter)
        val busRadius = busDiameter / 2f
        // The bus drawn as a teardrop: its circle runs out to a point the way it is going, like a
        // compass needle, and stays a plain circle when the way is unknown.
        val nose = max(busRadius * 0.55f, 6f * density)
        val extent = busRadius + nose + ring
        val bitmapSize = (extent * 2f).roundToInt()
        val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = bitmapSize / 2f
        val color = if (closest) getColor(R.color.route_orange) else getColor(R.color.deep_green)

        val outline = Path().apply { addCircle(center, center, busRadius - ring / 2f, Path.Direction.CW) }
        bearing?.let {
            val angle = Math.toRadians(it.toDouble() - 90.0)
            // The sides leave the circle where they meet it smoothly (tangents from the tip).
            val tipDistance = busRadius - ring / 2f + nose
            val spread = kotlin.math.acos(((busRadius - ring / 2f) / tipDistance).toDouble())
            fun at(radians: Double, distance: Float) = Pair(
                center + kotlin.math.cos(radians).toFloat() * distance,
                center + kotlin.math.sin(radians).toFloat() * distance)
            val (tipX, tipY) = at(angle, tipDistance)
            val (leftX, leftY) = at(angle - spread, busRadius - ring / 2f)
            val (rightX, rightY) = at(angle + spread, busRadius - ring / 2f)
            outline.op(Path().apply {
                moveTo(tipX, tipY)
                lineTo(leftX, leftY)
                lineTo(center, center)
                lineTo(rightX, rightY)
                close()
            }, Path.Op.UNION)
        }

        paint.style = Paint.Style.FILL
        paint.color = if (closest) color else getColor(R.color.map_face)
        canvas.drawPath(outline, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ring
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = if (closest) getColor(R.color.map_on_accent) else color
        canvas.drawPath(outline, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (closest) getColor(R.color.map_on_accent) else color
        lines.forEachIndexed { index, line ->
            val offset = (index - (lines.size - 1) / 2f) * paint.textSize
            canvas.drawText(line, center, center + offset - (paint.ascent() + paint.descent()) / 2, paint)
        }
        return BitmapDrawable(resources, bitmap)
    }

    private fun distanceTo(vehicle: LiveVehicle): Float {
        val output = FloatArray(1)
        Location.distanceBetween(
            startPoint.latitude, startPoint.longitude,
            vehicle.latitude, vehicle.longitude, output
        )
        return output[0]
    }

    private fun deviceLocationEnabled() =
        LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))

    private fun openLocationSettings() {
        locationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        lifecycleScope.launch {
            binding.locationButton.isEnabled = false
            try {
                val location = obtainLocation()
                if (location == null) {
                    showLocationHint()
                } else if (planningJob?.isActive != true) {
                    currentLocation = GeoPoint(location.latitude, location.longitude)
                    binding.map.controller.setZoom(16.0)
                    binding.map.controller.animateTo(currentLocation)
                    renderVehicles()
                }
            } finally {
                binding.locationButton.isEnabled = true
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun obtainLocation(): Location? {
        if (!deviceLocationEnabled()) {
            Log.i(TAG, "Device location is switched off")
            return null
        }
        if (!hasLocationPermission()) {
            val pending = permissionRequest ?: CompletableDeferred<Boolean>().also {
                permissionRequest = it
                locationPermission.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
            if (!pending.await()) return null
        }
        originLocation?.takeIf {
            SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..30_000_000_000L
        }?.let { return it }
        val pending = locationRequest?.takeIf { it.isActive } ?: lifecycleScope.async {
            val location = withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val cancellation = CancellationTokenSource()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    val request = CurrentLocationRequest.Builder()
                        .setMaxUpdateAgeMillis(30_000)
                        .setDurationMillis(10_000)
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .build()
                    locationClient.getCurrentLocation(request, cancellation.token)
                        .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                        .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
                }
            }
            location?.takeIf {
                it.hasAccuracy() && it.latitude.isFinite() && it.longitude.isFinite() &&
                    SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..30_000_000_000L
            }?.also { originLocation = it }
        }.also { locationRequest = it }
        return pending.await()
    }

    private fun showLocationHint() {
        val hasDestination = !binding.destinationInput.text.isNullOrBlank()
        // With no journey asked for, a journey panel holding only a complaint is the wrong place:
        // the map stays as it is and a bar at the bottom says what is wrong.
        if (!hasDestination && binding.journeyCard.visibility != View.VISIBLE) {
            val (message, action) = when {
                !deviceLocationEnabled() ->
                    R.string.device_location_off to MessageAction(R.string.turn_on_location) { openLocationSettings() }
                !hasLocationPermission() ->
                    R.string.location_permission_required to MessageAction(R.string.allow_location) { askForLocationAgain() }
                else -> R.string.location_unavailable to MessageAction(R.string.retry) { requestLocation() }
            }
            Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_LONG)
                .setAction(action.label) { action() }
                .show()
            return
        }
        when {
            !deviceLocationEnabled() -> showJourneyMessage(getString(R.string.device_location_off),
                R.drawable.ic_my_location, MessageAction(R.string.turn_on_location) { openLocationSettings() })
            !hasLocationPermission() -> showJourneyMessage(getString(R.string.location_permission_required),
                R.drawable.ic_my_location, MessageAction(R.string.allow_location) { askForLocationAgain() })
            // No fix yet, often indoors: a journey can still start from somewhere chosen.
            else -> showJourneyMessage(getString(R.string.location_unavailable), R.drawable.ic_my_location,
                if (hasDestination) MessageAction(R.string.choose_start) {
                    showStartMenu(binding.locationSettingsButton)
                } else null)
        }
    }

    /** Asks again while Android still lets the app ask; once it does not, the app's settings page is the way. */
    private fun askForLocationAgain() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (binding.destinationInput.text.isNullOrBlank()) requestLocation() else findDestinationRoute()
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        }
    }

    private fun consumeSharedDirections(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val route = ROUTE_PATTERN.find(shared)?.groupValues?.get(1) ?: return
        planningJob?.cancel()
        journeyChoice = null
        trainJourneyChoice = null
        mixedJourneyChoice = null
        directDestination = null
        selectedRoute = route
        selectedPattern = null
        showJourneyMessage(getString(R.string.shared_route_found, route))
        renderVehicles()
    }

    /** A home-screen shortcut for a saved place: plan a journey there. */
    private fun consumeGoToPlace(intent: Intent?) {
        if (intent?.action != PlaceShortcuts.ACTION_GO_TO_PLACE) return
        intent.action = null
        val name = intent.getStringExtra(PlaceShortcuts.EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: return
        if (!intent.hasExtra(PlaceShortcuts.EXTRA_LATITUDE)) return
        val latitude = intent.getDoubleExtra(PlaceShortcuts.EXTRA_LATITUDE, 0.0)
        val longitude = intent.getDoubleExtra(PlaceShortcuts.EXTRA_LONGITUDE, 0.0)
        // Follow the saved place if it was moved since the shortcut was made.
        val place = savedPlaces.byName(name)?.asOfflinePlace(exactMatch = true)
            ?: OfflinePlace(name, name, latitude, longitude, SavedPlace.KIND, true)
        chooseDestination(place)
    }

    private fun geoTarget(intent: Intent?): GeoTarget? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.toString()?.let(GeoUri::parse) else null

    /** A `geo:` link from another app: plan a journey there, as a long press on the map would. */
    private fun consumeGeoLink(intent: Intent?) {
        val target = geoTarget(intent) ?: return
        intent!!.action = null
        when (target) {
            is GeoTarget.Point -> goToMapPoint(GeoPoint(target.latitude, target.longitude))
            is GeoTarget.Search -> {
                binding.destinationInput.setText(target.query)
                findDestinationRoute()
            }
        }
    }

    private fun consumeReplan(intent: Intent?) {
        if (intent?.action != BusAlertService.ACTION_REPLAN) return
        val finalAddress = intent.getStringExtra(BusAlertService.EXTRA_FINAL_ADDRESS)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        intent.action = null
        AlertPreferences.clear(this)
        stopService(Intent(this, BusAlertService::class.java))
        customStart = null
        binding.destinationInput.setText(finalAddress)
        journeyChoice = null
        trainJourneyChoice = null
        mixedJourneyChoice = null
        hideTravelModeChoices()
        binding.sharedDirections.text = getString(R.string.replanning_from_here)
        binding.sharedDirections.visibility = View.VISIBLE
        findDestinationRoute()
    }

    // Lines that differ only by a suffix ("13" and "13+", "10" and "10N") must stay apart, so take
    // the name from the feed; stripping letters is only the guess for an unknown route.
    private fun normalizeRoute(route: String) = transitIndex.routeName(route)
        ?: route.removePrefix("gtt:").replace(TRAILING_LETTERS, "")

    private fun selectWalkingMode(frame: Boolean = true) {
        val destination = directDestination ?: return
        // Not cancelling planning here: a journey that turns out quicker on foot lands here from within it.
        if (journeyChoice == null && trainJourneyChoice == null) return
        walkingMode = true
        showOptionsRow(false)
        binding.journeyTitle.text = getString(R.string.walk_summary, minutes(directWalkingSeconds))
        showSummary(directWalkingSeconds)
        updateModeSwitch()
        renderJourneyGuide(JourneySteps.forWalking(this, directWalkingMetres.roundToInt(), destination))
        binding.alertButton.visibility = View.GONE
        renderVehicles()
        if (frame) frameCurrentJourney()
    }

    /**
     * Every ride found takes far longer than walking there, such as tomorrow's first train while
     * the buses are out of reach: offer only the walk.
     */
    private fun showWalkingOnly(destination: String, address: Pair<Double, Double>) {
        hideTravelModeChoices()
        if (destination.trim() != myLocationLabel) {
            recentDestinations.add(destination, address.first, address.second)
        }
        journeyChoice = null
        trainJourneyChoice = null
        mixedJourneyChoice = null
        transitGuide = null
        selectedRoute = null
        selectedPattern = null
        highlightedVehicleId = null
        binding.destinationLayout.error = null
        binding.locationSettingsButton.visibility = View.GONE
        val target = GeoPoint(address.first, address.second)
        directDestination = target
        directWalkingMetres = directWalkingDistance(address.first, address.second)
        directWalkingSeconds = directWalkingMetres / WALKING_METRES_PER_SECOND
        walkingMode = true
        binding.journeyTitle.text = getString(R.string.walk_summary, minutes(directWalkingSeconds))
        showSummary(directWalkingSeconds)
        val guide = JourneySteps.forWalking(this, directWalkingMetres.roundToInt(), target)
        renderJourneyGuide(guide.copy(notes =
            listOf(JourneyNote(R.drawable.ic_bolt, getString(R.string.no_ride_beats_walking))) + guide.notes))
        showJourneySheet(BottomSheetBehavior.STATE_COLLAPSED)
        renderVehicles()
        frameCurrentJourney()
    }

    /** A ride is worth offering beside walking unless it takes far longer than the walk. */
    private fun worthTaking(totalSeconds: Float, walkSeconds: Float) =
        totalSeconds <= maxOf(walkSeconds * WORTH_WALK_FACTOR, walkSeconds + WORTH_WALK_SLACK_SECONDS)

    private fun selectBusMode() {
        if (journeyChoice == null && trainJourneyChoice == null) return
        walkingMode = false
        renderJourneyOptionChips()
        val transitSeconds = transitSeconds()
        binding.journeyTitle.text = getString(R.string.transit_summary, minutes(transitSeconds))
        showSummary(transitSeconds)
        updateModeSwitch()
        transitGuide?.let(::renderJourneyGuide)
        binding.alertButton.visibility =
            if (trainJourneyChoice == null && customStart == null) View.VISIBLE else View.GONE
        updateAlertButton()
        renderVehicles()
        frameCurrentJourney()
    }

    /** Under the title: when the rider gets there. When to set off rides on the first step, just below. */
    private fun showSummary(totalSeconds: Float) {
        val arrival = java.time.LocalTime.now().plusSeconds(totalSeconds.toLong())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        binding.journeySummary.text = getString(R.string.journey_arrive, arrival)
        binding.journeySummary.visibility = View.VISIBLE
    }

    private fun transitSeconds() = mixedJourneyChoice?.estimatedTotalSeconds
        ?: trainJourneyChoice?.estimatedTotalSeconds
        ?: journeyChoice?.estimatedTotalSeconds ?: 0f

    /**
     * Walking is offered only when it beats the ride: the journey then walks, and this button offers
     * the ride anyway (or, once riding, the walk back). When the ride is quicker there is no choice to make.
     */
    private fun updateModeSwitch() {
        val button = binding.modeSwitchButton
        if (!walkingIsFaster || (journeyChoice == null && trainJourneyChoice == null)) {
            button.visibility = View.GONE
            return
        }
        button.text = if (walkingMode) getString(R.string.ride_anyway, minutes(transitSeconds()))
            else getString(R.string.walk_instead, minutes(directWalkingSeconds))
        button.setIconResource(if (walkingMode) R.drawable.ic_bus else R.drawable.ic_walk)
        button.visibility = View.VISIBLE
    }

    private fun hideTravelModeChoices() {
        walkingMode = false
        journeyOptions = emptyList()
        showOptionsRow(false)
        walkingIsFaster = false
        binding.modeSwitchButton.visibility = View.GONE
        binding.alertButton.visibility = View.GONE
    }

    private fun directWalkingDistance(latitude: Double, longitude: Double): Float {
        walkingFromOrigin?.let { return it.estimatedMetresTo(latitude, longitude) }
        val result = FloatArray(1)
        Location.distanceBetween(
            startPoint.latitude,
            startPoint.longitude,
            latitude,
            longitude,
            result
        )
        // Street walking is normally longer than the straight map distance.
        return result[0] * WALKING_STREET_FACTOR
    }

    /** Walking directions in Maps; an [origin] sets where a leg starts, otherwise Maps starts from the rider. */
    private fun openWalkingDirections(destination: GeoPoint, origin: GeoPoint?) {
        val from = origin ?: startPoint
        val webDirections = Uri.parse(
            "https://www.google.com/maps/dir/?api=1" +
                "&origin=${from.latitude},${from.longitude}" +
                "&destination=${destination.latitude},${destination.longitude}" +
                "&travelmode=walking"
        )
        // Navigation always starts at the rider's own position, so a leg that starts elsewhere
        // goes through the directions page, which takes an origin.
        val first = if (origin == null && customStart == null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(
                "google.navigation:q=${destination.latitude},${destination.longitude}&mode=w"))
        } else Intent(Intent.ACTION_VIEW, webDirections)
        try {
            startActivity(first.setPackage(GOOGLE_MAPS_PACKAGE))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, webDirections))
            } catch (_: ActivityNotFoundException) {
                Snackbar.make(binding.coordinator, R.string.directions_unavailable, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun minutes(seconds: Float) = ceilMinutes(seconds)

    private fun TrainStop.asTransitStop() = TransitStop(id, name, latitude, longitude)

    private fun applySystemBarInsets() {
        // The content keeps clear of the system bars; the side menu takes them on its own.
        ViewCompat.setOnApplyWindowInsetsListener(binding.appContent) { view, windowInsets ->
            val bars: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            windowInsets
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        updateAlertButton()
        // Back from allowing installs: carry on with the update the rider started.
        pendingUpdate?.takeIf { packageManager.canRequestPackageInstalls() && it.exists() }?.let(::installUpdate)
    }

    override fun onPause() {
        BusSpeedStore.save(applicationContext, force = true)
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.map.onDetach()
        offlineMapSource?.dispose()
        offlineMapSource = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MatoLiveBus"
        const val JOURNEY_STORE = "active_journey"
        const val MAX_SUGGESTIONS = 12
        const val STALE_NOTICE_VERSION = "stale_notice_version"
        const val HINTS_SEEN = "hints_seen"
        const val COURSE_HINT_SEEN = "course_hint_seen"
        const val ZOOM_BUTTONS_RETIRED = "zoom_buttons_retired"
        const val INCLUDE_TRAINS = "include_trains"
        const val WALKING_METRES_PER_SECOND = 1.2f
        /** A ride is not offered when it takes more than 1.5 times the walk and 15 minutes more. */
        private const val WORTH_WALK_FACTOR = 1.5f
        private const val WORTH_WALK_SLACK_SECONDS = 15 * 60f
        const val WALKING_STREET_FACTOR = 1.2f
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        const val MISSING_REFRESHES_BEFORE_REPLAN = 3
        const val FRESHNESS_TICK_MILLIS = 5_000L
        const val FRESHNESS_QUIET_SECONDS = 30
        const val STOP_DOT_DP = 9
        // Half a 48dp touch target: a tap this close to a stop dot opens that stop.
        const val STOP_TAP_REACH_DP = 24
        const val TURNAROUND_GRACE_MILLIS = 5 * 60_000L
        val TRAILING_LETTERS = Regex("[A-Za-z]+$")
        val ROUTE_PATTERN = Regex("""(?i)(?:bus|linea|line|route)\s*#?\s*(\d{1,3})""")
    }
}
