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
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.annotation.SuppressLint
import androidx.core.widget.doAfterTextChanged
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
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
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.Insets
import androidx.core.view.doOnLayout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import it.mato.livebus.databinding.ActivityMainBinding
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
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.PopupMenu
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import java.time.LocalDateTime

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
    private var liveVehicles: List<LiveVehicle> = emptyList()
    private var refreshJob: Job? = null
    private var selectedRoute: String? = null
    private var selectedPattern: TransitPattern? = null
    private var journeyChoice: JourneyChoice? = null
    private var trainJourneyChoice: ScheduledTrainJourney? = null
    private var mixedJourneyChoice: MixedJourney? = null
    private var walkingMode = false
    private var directWalkingMetres = 0f
    private var directWalkingSeconds = 0f
    private var walkingFromOrigin: WalkGraph.Distances? = null
    private var walkingFromDestination: WalkGraph.Distances? = null
    private var directDestination: GeoPoint? = null
    private var busJourneyDescription: CharSequence? = null
    private var automaticReplanPending = false
    private var includeTrains = true
    private var walkingPreference = WalkingPreference.BALANCED
    private val busIconCache = mutableMapOf<Triple<Boolean, Int, String>, BitmapDrawable>()

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
    ) { granted -> if (granted) enableAlert() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handlingReplan = intent.action == BusAlertService.ACTION_REPLAN
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.requestFocus()
        applySystemBarInsets()
        ViewCompat.setAccessibilityHeading(binding.journeyTitle, true)

        setupMap()
        binding.topControls.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            binding.map.setMapCenterOffset(0, (bottom - top) / 2)
        }
        setupDestinationPicker()
        setupSearchPresets()
        binding.locationButton.setOnClickListener {
            if (deviceLocationEnabled()) requestLocation() else openLocationSettings()
        }
        binding.locationSettingsButton.setOnClickListener { openLocationSettings() }
        binding.findRouteButton.setOnClickListener { findDestinationRoute() }
        binding.liveStatus.setOnClickListener { startRefreshing(forceRefresh = true) }
        binding.alertButton.setOnClickListener { requestAlert() }
        binding.walkButton.setOnClickListener { selectWalkingMode() }
        binding.busAnywayButton.setOnClickListener { selectBusMode() }
        binding.boardStopDirectionsButton.setOnClickListener {
            val stop = if (mixedJourneyChoice?.busFirst == false) {
                trainJourneyChoice?.boardAt?.let { GeoPoint(it.latitude, it.longitude) }
            } else {
                journeyChoice?.boardAt?.let { GeoPoint(it.latitude, it.longitude) }
                    ?: trainJourneyChoice?.boardAt?.let { GeoPoint(it.latitude, it.longitude) }
            }
            stop?.let { openWalkingDirections(it) }
        }
        binding.sharedDirections.movementMethod = ScrollingMovementMethod()
        binding.closeJourneyButton.setOnClickListener {
            planningJob?.cancel()
            journeyChoice = null
            trainJourneyChoice = null
            mixedJourneyChoice = null
            walkingFromOrigin = null
            walkingFromDestination = null
            selectedRoute = null
            selectedPattern = null
            directDestination = null
            hideTravelModeChoices()
            binding.journeyCard.visibility = View.GONE
            renderVehicles()
        }
        consumeSharedDirections(intent)
        requestLocation()
        startRefreshing()
        consumeReplan(intent)
        if (savedInstanceState == null && !handlingReplan) restoreLastJourney()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSharedDirections(intent)
        consumeReplan(intent)
    }

    private fun setupMap() = with(binding.map) {
        setMultiTouchControls(true)
        setUseDataConnection(false)
        setTilesScaledToDpi(false)
        controller.setZoom(16.0)
        controller.setCenter(currentLocation)
        lifecycleScope.launch {
            try {
                val city = withContext(Dispatchers.IO) { offlineCity }
                val source = MapsForgeTileSource.createFromFiles(arrayOf(city.mapFile),
                    AssetsRenderTheme(assets, "offline/", "map-theme.xml"),
                    city.mapCacheKey + "-simple-v3", "it")
                offlineMapSource = source
                val provider = MapsForgeTileProvider(SimpleRegisterReceiver(this@MainActivity), source, null)
                setTileProvider(provider)
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

    private fun setupDestinationPicker() {
        val adapter = object : ArrayAdapter<OfflinePlace>(
            this, android.R.layout.simple_dropdown_item_1line
        ) {
            private val searchFilter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val query = constraint?.toString().orEmpty()
                    val matches = if (query.length < 2) emptyList() else try {
                        offlineCity.search(query)
                    } catch (error: Exception) {
                        Log.e(TAG, "Offline destination search failed", error)
                        emptyList()
                    }
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
        }
        binding.destinationInput.setAdapter(adapter)
        binding.destinationInput.threshold = 2
        binding.destinationInput.doAfterTextChanged {
            binding.destinationLayout.error = null
        }
        binding.destinationInput.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { place ->
                lastGeocodedAddress = place.name to (place.latitude to place.longitude)
                binding.destinationInput.setText(place.name, false)
                showDestination(place)
            }
        }
        binding.destinationInput.setOnEditorActionListener { _, _, _ ->
            findDestinationRoute()
            true
        }
    }

    private fun showDestination(place: OfflinePlace) {
        directDestination = GeoPoint(place.latitude, place.longitude)
        binding.map.overlays.removeAll { it is Marker || it is Polyline }
        addYouMarker()
        addExactDestinationMarker(place.latitude, place.longitude)
        binding.map.controller.setZoom(16.0)
        binding.map.controller.animateTo(directDestination)
        binding.map.invalidate()
        findDestinationRoute()
    }

    private fun setupSearchPresets() {
        val preferences = getSharedPreferences(JOURNEY_STORE, MODE_PRIVATE)
        includeTrains = preferences.getBoolean(INCLUDE_TRAINS, true)
        walkingPreference = WalkingPreference.entries.firstOrNull {
            it.name == preferences.getString(WALKING_PREFERENCE, null)
        } ?: WalkingPreference.BALANCED
        binding.includeTrainsSwitch.isChecked = includeTrains
        updateWalkingPresetButton()
        binding.includeTrainsSwitch.setOnCheckedChangeListener { _, checked ->
            includeTrains = checked
            preferences.edit().putBoolean(INCLUDE_TRAINS, checked).apply()
            replanForPreset()
        }
        binding.walkingPresetButton.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                WalkingPreference.entries.forEach { option ->
                    menu.add(1, option.ordinal + 1, option.ordinal, getString(when (option) {
                        WalkingPreference.LESS -> R.string.walking_option_less
                        WalkingPreference.BALANCED -> R.string.walking_option_balanced
                        WalkingPreference.MORE -> R.string.walking_option_more
                    })).apply {
                        isCheckable = true
                        isChecked = option == walkingPreference
                    }
                }
                menu.setGroupCheckable(1, true, true)
                setOnMenuItemClickListener { item ->
                    val selected = WalkingPreference.entries[item.itemId - 1]
                    if (selected != walkingPreference) {
                        walkingPreference = selected
                        preferences.edit().putString(WALKING_PREFERENCE, selected.name).apply()
                        updateWalkingPresetButton()
                        replanForPreset()
                    }
                    true
                }
                show()
            }
        }
    }

    private fun updateWalkingPresetButton() {
        binding.walkingPresetButton.setText(when (walkingPreference) {
            WalkingPreference.LESS -> R.string.walking_preset_less
            WalkingPreference.BALANCED -> R.string.walking_preset_balanced
            WalkingPreference.MORE -> R.string.walking_preset_more
        })
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
            binding.findRouteButton.isEnabled = false
            binding.searchProgress.visibility = View.VISIBLE
            journeyChoice = null
            trainJourneyChoice = null
            mixedJourneyChoice = null
            walkingFromOrigin = null
            walkingFromDestination = null
            selectedRoute = null
            selectedPattern = null
            directDestination = lastGeocodedAddress?.takeIf { it.first == destination }?.second
                ?.let { GeoPoint(it.first, it.second) }
            showJourneyMessage(getString(R.string.finding_address))
            binding.journeyTitle.setText(R.string.planning_journey)
            renderVehicles()
            try {
                coroutineScope {
                    val originTask = async { timed("location") { obtainLocation() } }
                    val addressTask = async { timed("geocode") { geocode(destination) } }
                    val vehiclesTask = async {
                        timed("vehicles") {
                            try {
                                Result.success(repository.vehicles())
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Result.failure<List<LiveVehicle>>(error)
                            }
                        }
                    }
                    val address = addressTask.await()
                    if (address == null) {
                        originTask.cancel()
                        vehiclesTask.cancel()
                        showJourneyMessage(getString(R.string.address_not_found))
                        binding.destinationInput.showDropDown()
                        return@coroutineScope
                    }
                    directDestination = GeoPoint(address.first, address.second)
                    renderVehicles()
                    binding.map.controller.animateTo(directDestination)
                    val origin = originTask.await()
                    if (origin == null) {
                        vehiclesTask.cancel()
                        showLocationHint()
                        return@coroutineScope
                    }
                    // One origin snapshot is used for both planners and their walking legs.
                    currentLocation = GeoPoint(origin.latitude, origin.longitude)
                    val walkingTask = async(Dispatchers.Default) {
                        timed("walking") {
                            val city = offlineCity
                            city.walkingDistancesFrom(origin.latitude, origin.longitude) to
                                city.walkingDistancesFrom(address.first, address.second)
                        }
                    }
                    val planningTime = LocalDateTime.now()
                    val trainTask = if (searchTrains) async(Dispatchers.Default) {
                        timed("trains") {
                            val (fromOrigin, fromDestination) = walkingTask.await()
                            trainIndex.planScheduledJourney(
                                origin.latitude, origin.longitude, address.first, address.second,
                                now = planningTime,
                                walkFromOrigin = fromOrigin::estimatedMetresTo,
                                walkFromDestination = fromDestination::estimatedMetresTo,
                                walkingPreference = preference
                            )
                        }
                    } else null
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
                    val busChoice = withContext(Dispatchers.Default) {
                        timed("buses") {
                            hasMatchedVehicles = journeyVehicles.any { transitIndex.matchDirection(it) != null }
                            transitIndex.planLiveJourney(
                                origin.latitude, origin.longitude, address.first, address.second,
                                journeyVehicles,
                                walkFromOrigin = fromOrigin::estimatedMetresTo,
                                walkFromDestination = fromDestination::estimatedMetresTo,
                                walkBetweenStops = { startLat, startLon, endLat, endLon ->
                                    offlineCity.shortWalkMetres(startLat, startLon, endLat, endLon)
                                        ?: Float.POSITIVE_INFINITY
                                },
                                walkingPreference = preference,
                                onDirectJourney = { direct ->
                                    withContext(Dispatchers.Main) {
                                        val trainChoice = trainTask?.await()
                                        if (direct != null || trainChoice != null) {
                                            displayJourney(destination, address, direct, trainChoice, frame = true)
                                            binding.sharedDirections.append("\n" + getString(R.string.checking_transfers))
                                            shown = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                    val trainChoice = trainTask?.await()
                    val mixedChoice = if (searchTrains) withContext(Dispatchers.Default) {
                        timed("bus-train connections") {
                            journeyPlanner.planMixedJourney(origin.latitude, origin.longitude,
                                address.first, address.second, journeyVehicles, planningTime,
                                fromOrigin::estimatedMetresTo,
                                fromDestination::estimatedMetresTo,
                                walkBetweenStops = { fromLat, fromLon, toLat, toLon ->
                                    offlineCity.shortWalkMetres(fromLat, fromLon, toLat, toLon)
                                        ?: Float.POSITIVE_INFINITY
                                },
                                walkingPreference = preference)
                        }
                    } else null
                    if (busChoice == null && trainChoice == null && mixedChoice == null) {
                        showJourneyMessage(getString(
                            if (vehicleResult.isFailure) R.string.journey_feed_unavailable
                            else if (liveVehicles.isEmpty()) R.string.no_live_vehicles_now
                            else if (!hasMatchedVehicles) R.string.live_directions_unavailable
                            else R.string.no_direct_route
                        ))
                    } else {
                        val changed = mixedChoice != mixedJourneyChoice ||
                            (busChoice != journeyChoice && trainJourneyChoice == null)
                        displayJourney(destination, address, busChoice, trainChoice,
                            mixedChoice, frame = !shown || changed)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Journey planning failed", error)
                showJourneyMessage(getString(R.string.journey_failed))
            } finally {
                if (currentCoroutineContext()[Job] == planningJob) {
                    binding.findRouteButton.isEnabled = true
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

    private fun displayJourney(
        destination: String,
        address: Pair<Double, Double>,
        busChoice: JourneyChoice?,
        trainChoice: ScheduledTrainJourney?,
        mixedChoice: MixedJourney?,
        frame: Boolean
    ) {
        binding.destinationLayout.error = null
        binding.locationSettingsButton.visibility = View.GONE
        saveLastDestination(destination.trim())
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
        busJourneyDescription = mixedJourneyChoice?.let { mixed ->
            val bus = mixed.bus
            val train = mixed.train
            if (mixed.busFirst) getString(R.string.bus_then_train_journey,
                bus.pattern.route,
                TransitIndex.cleanStopName(bus.boardAt.name),
                TransitIndex.cleanStopName(bus.destination.name),
                bus.destinationWalkMetres.roundToInt(),
                TransitIndex.cleanStopName(train.boardAt.name),
                train.category, train.number, formatTime(train.departureSeconds),
                TransitIndex.cleanStopName(train.destination.name), formatTime(train.arrivalSeconds),
                train.destinationWalkMetres.roundToInt())
            else getString(R.string.train_then_bus_journey,
                train.category, train.number,
                TransitIndex.cleanStopName(train.boardAt.name), formatTime(train.departureSeconds),
                TransitIndex.cleanStopName(train.destination.name), formatTime(train.arrivalSeconds),
                bus.walkingMetres.roundToInt(), TransitIndex.cleanStopName(bus.boardAt.name),
                bus.pattern.route, TransitIndex.cleanStopName(bus.destination.name),
                bus.destinationWalkMetres.roundToInt())
        } ?: trainJourneyChoice?.let { train ->
            getString(
                R.string.train_journey_found,
                train.category,
                train.number,
                train.headsign,
                TransitIndex.cleanStopName(train.boardAt.name),
                formatTime(train.departureSeconds),
                TransitIndex.cleanStopName(train.destination.name),
                formatTime(train.arrivalSeconds),
                train.walkingMetres.roundToInt(),
                train.destinationWalkMetres.roundToInt()
            )
        } ?: run {
            val bus = requireNotNull(choice)
            val lines = bus.pattern.route + (bus.secondLeg?.let { " → " + it.pattern.route } ?: "")
            val exit = bus.transferAt ?: bus.destination
            val description = getString(R.string.journey_compact,
                lines, minutes(bus.boardingEtaSeconds), bus.walkingMetres.roundToInt(),
                TransitIndex.cleanStopName(bus.boardAt.name), TransitIndex.cleanStopName(exit.name)) +
                if (bus.secondLeg != null) "\n" + getString(R.string.journey_transfer_compact,
                    bus.secondLeg.pattern.route, TransitIndex.cleanStopName(bus.secondLeg.exitAt.name))
                else "\n" + getString(R.string.journey_last_walk, bus.destinationWalkMetres.roundToInt())
            val age = maxOf(bus.vehicle.ageSeconds, bus.secondLeg?.vehicle?.ageSeconds ?: 0)
            val note = listOfNotNull(
                if (bus.directionEstimated) getString(R.string.direction_estimated_short) else null,
                if (age > 120) getString(R.string.bus_position_age, (age + 59) / 60) else null
            ).joinToString(" · ")
            SpannableString(description + if (note.isEmpty()) "" else "\n$note").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, description.indexOf('\n'), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        binding.journeyTitle.text = getString(R.string.transit_summary, minutes(transitSeconds))
        binding.walkButton.text = getString(R.string.walk_option, minutes(directWalkingSeconds))
        binding.walkButton.contentDescription = getString(R.string.walk_there) + ", " +
            getString(R.string.walk_summary, minutes(directWalkingSeconds))
        binding.busAnywayButton.text = getString(R.string.transit_option, minutes(transitSeconds))
        binding.travelModeChoices.visibility = View.VISIBLE
        binding.boardStopDirectionsButton.visibility = View.VISIBLE
        binding.sharedDirections.text = busJourneyDescription
        binding.sharedDirections.visibility = View.VISIBLE
        if (walkingPreference != WalkingPreference.LESS && directWalkingSeconds < transitSeconds) {
            binding.sharedDirections.text = getString(
                R.string.walking_is_faster,
                minutes(directWalkingSeconds),
                directWalkingMetres.roundToInt(),
                minutes(transitSeconds)
            )
        }
        binding.alertButton.visibility = if (choice != null && trainJourneyChoice == null) View.VISIBLE else View.GONE
        if ((originLocation?.accuracy ?: 0f) > 200f) {
            binding.sharedDirections.append("\n" + getString(R.string.location_approximate))
        }
        binding.journeyCard.visibility = View.VISIBLE
        renderVehicles()
        if (frame) frameCurrentJourney()
    }

    private suspend fun geocode(query: String): Pair<Double, Double>? {
        lastGeocodedAddress?.takeIf { it.first == query }?.let { return it.second }
        val matches = withContext(Dispatchers.IO) { offlineCity.search(query) }
        val place = matches.firstOrNull { it.exactMatch } ?: matches.singleOrNull()
        if (place == null) return null
        return (place.latitude to place.longitude).also { lastGeocodedAddress = query to it }
    }

    private fun startRefreshing(forceRefresh: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            var forceNextFetch = forceRefresh
            while (isActive) {
                fetchVehicles(forceNextFetch)
                forceNextFetch = false
                delay(15_000)
            }
        }
    }

    private suspend fun fetchVehicles(forceRefresh: Boolean) {
        setLiveStatus(getString(R.string.connecting), R.color.ink)
        runCatching { repository.vehicles(forceRefresh) }
            .onSuccess {
                liveVehicles = it
                updateLiveStatus()
                if (activeBusNeedsReplan(it)) {
                    requestAutomaticReplan()
                } else {
                    renderVehicles()
                }
            }
            .onFailure {
                if (it is CancellationException) return
                Log.e(TAG, "Unable to refresh GTT live positions", it)
                liveVehicles = emptyList()
                renderVehicles()
                setLiveStatus(getString(R.string.feed_unavailable), R.color.warning)
            }
    }

    private fun updateLiveStatus() {
        val oldest = liveVehicles.maxOfOrNull { it.ageSeconds } ?: 0
        val status = when {
            liveVehicles.isEmpty() -> getString(R.string.no_bus_positions)
            oldest > 120 -> getString(R.string.delayed_bus_positions, liveVehicles.size, (oldest + 59) / 60)
            else -> getString(R.string.live_bus_count, liveVehicles.size)
        }
        setLiveStatus(status,
            if (liveVehicles.isEmpty() || oldest > 120) R.color.warning else R.color.live_green)
    }

    private fun setLiveStatus(status: String, color: Int) {
        binding.liveStatus.text = status
        binding.liveStatus.setTextColor(getColor(color))
        binding.liveStatus.contentDescription = getString(R.string.live_status_retry, status)
    }

    private fun activeBusNeedsReplan(vehicles: List<LiveVehicle>): Boolean {
        val journey = journeyChoice ?: return false
        if (walkingMode || mixedJourneyChoice?.busFirst == false ||
            automaticReplanPending || planningJob?.isActive == true) return false
        val vehicle = vehicles.firstOrNull { it.id == journey.vehicle.id } ?: return true
        val pattern = transitIndex.matchDirection(vehicle)
        return pattern == null || pattern.id != journey.pattern.id ||
            !transitIndex.isStopIndexAhead(vehicle, pattern, journey.boardStopIndex)
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
        drawJourneyLine()
        if (journeyChoice == null && trainJourneyChoice == null) {
            directDestination?.let { addExactDestinationMarker(it.latitude, it.longitude) }
        }
        addYouMarker()

        val journeyMarkers = binding.map.overlays.filterIsInstance<Marker>()
        val selectedIds = setOfNotNull(journeyChoice?.vehicle?.id, journeyChoice?.secondLeg?.vehicle?.id)
        val markers = when {
            walkingMode || (trainJourneyChoice != null && mixedJourneyChoice == null) -> emptyList()
            journeyChoice != null -> liveVehicles.filter { it.id in selectedIds }
            selectedRoute != null -> liveVehicles.filter { normalizeRoute(it.routeId) == selectedRoute }
            else -> liveVehicles
        }

        markers.forEach { vehicle ->
            val vehicleRoute = normalizeRoute(vehicle.routeId)
            val marker = Marker(binding.map).apply {
                position = GeoPoint(vehicle.latitude, vehicle.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = getString(R.string.vehicle_title, vehicleRoute)
                snippet = getString(R.string.distance_away, distanceTo(vehicle).roundToInt()) +
                    " · " + getString(R.string.bus_position_age, (vehicle.ageSeconds + 59) / 60)
                alpha = if (vehicle.ageSeconds > 120) 0.65f else 1f
                icon = busDirectionMarker(vehicle.id in selectedIds, vehicle.bearing, vehicleRoute)
                setOnMarkerClickListener { clickedMarker, mapView ->
                    val pattern = transitIndex.matchDirection(vehicle)
                    val direction = if (pattern == null) {
                        getString(R.string.bus_direction_unavailable)
                    } else {
                        getString(R.string.bus_destination, pattern.headsign) +
                            if (vehicle.tripId !in pattern.tripIds) {
                                " · " + getString(R.string.direction_estimated_short)
                            } else ""
                    }
                    clickedMarker.subDescription = TextUtils.htmlEncode(direction)
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

    private fun drawJourneyLine() {
        if (walkingMode) {
            val destination = directDestination ?: return
            addWalkingConnector(currentLocation, destination)
            addExactDestinationMarker(destination.latitude, destination.longitude)
            return
        }
        mixedJourneyChoice?.let { mixed ->
            val station = if (mixed.busFirst) mixed.train.boardAt else mixed.train.destination
            val stationPoint = GeoPoint(station.latitude, station.longitude)
            val destination = directDestination ?: stationPoint
            if (mixed.busFirst) {
                drawBusJourneyLine(mixed.bus, currentLocation, stationPoint, false)
                drawTrainJourneyLine(mixed.train, stationPoint, destination, true)
            } else {
                drawTrainJourneyLine(mixed.train, currentLocation, stationPoint, false)
                drawBusJourneyLine(mixed.bus, stationPoint, destination, true)
            }
            return
        }
        trainJourneyChoice?.let { train ->
            drawTrainJourneyLine(train, currentLocation,
                directDestination ?: GeoPoint(train.destination.latitude, train.destination.longitude), true)
            return
        }
        val choice = journeyChoice ?: return
        drawBusJourneyLine(choice, currentLocation,
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
            binding.map.overlays.add(Polyline(binding.map).apply {
                setPoints(points)
                outlinePaint.color = Color.WHITE
                outlinePaint.strokeWidth = 6f * resources.displayMetrics.density
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            })
            binding.map.overlays.add(Polyline(binding.map).apply {
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
            for ((colour, width) in listOf(Color.WHITE to 6f, getColor(R.color.deep_green) to 3.5f)) {
                binding.map.overlays.add(Polyline(binding.map).apply {
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
        val points = train.stops.subList(train.boardIndex, train.destinationIndex + 1)
            .map { GeoPoint(it.latitude, it.longitude) }
        addWalkingConnector(origin, GeoPoint(train.boardAt.latitude, train.boardAt.longitude))
        addWalkingConnector(
            GeoPoint(train.destination.latitude, train.destination.longitude),
            destination
        )
        if (points.size >= 2) {
            binding.map.overlays.add(Polyline(binding.map).apply {
                setPoints(points)
                outlinePaint.color = Color.WHITE
                outlinePaint.strokeWidth = 6f * resources.displayMetrics.density
                outlinePaint.strokeCap = Paint.Cap.ROUND
            })
            binding.map.overlays.add(Polyline(binding.map).apply {
                setPoints(points)
                outlinePaint.color = getColor(R.color.train_blue)
                outlinePaint.strokeWidth = 3.5f * resources.displayMetrics.density
                outlinePaint.strokeCap = Paint.Cap.ROUND
            })
        }
        addJourneyStopMarker(train.boardAt.asTransitStop(), getString(R.string.board_train_here), getColor(R.color.train_blue))
        addJourneyStopMarker(train.destination.asTransitStop(), getString(R.string.exit_train_here), getColor(R.color.train_blue), 18)
        if (showFinalDestination) addExactDestinationMarker(destination.latitude, destination.longitude)
    }

    private fun addWalkingConnector(from: GeoPoint, to: GeoPoint) {
        walkingPath(from, to)?.takeIf { it.size >= 2 }?.let(::addWalkingPath)
    }

    private fun walkingPath(from: GeoPoint, to: GeoPoint): List<GeoPoint>? {
        val route = when {
            from.latitude == currentLocation.latitude &&
                from.longitude == currentLocation.longitude ->
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
        binding.map.overlays.add(Polyline(binding.map).apply {
            setPoints(points)
            outlinePaint.color = Color.WHITE
            outlinePaint.strokeWidth = 5f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.pathEffect = dash
        })
        binding.map.overlays.add(Polyline(binding.map).apply {
            setPoints(points)
            outlinePaint.color = getColor(R.color.deep_green)
            outlinePaint.strokeWidth = 2.5f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.pathEffect = dash
        })
    }

    private fun journeyPoints(choice: JourneyChoice): List<GeoPoint> {
        val stops = choice.pattern.stops
        val start = stops.indexOfFirst { it.id == choice.boardAt.id }
        val endStop = choice.transferAt ?: choice.destination
        val end = stops.indexOfFirst { it.id == endStop.id }
        if (start < 0 || end < start) return emptyList()
        return stops.subList(start, end + 1).map { GeoPoint(it.latitude, it.longitude) }
    }

    private fun secondLegPoints(choice: JourneyChoice): List<GeoPoint> {
        val leg = choice.secondLeg ?: return emptyList()
        val start = leg.pattern.stops.indexOfFirst { it.id == leg.boardAt.id }
        if (start < 0) return emptyList()
        val end = (start + leg.stopCount).coerceAtMost(leg.pattern.stops.lastIndex)
        return leg.pattern.stops.subList(start, end + 1).map { GeoPoint(it.latitude, it.longitude) }
    }

    private fun addJourneyStopMarker(
        stop: TransitStop,
        label: String,
        color: Int,
        sizeDp: Int = 14
    ) {
        binding.map.overlays.add(Marker(binding.map).apply {
            position = GeoPoint(stop.latitude, stop.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = label
            snippet = TransitIndex.cleanStopName(stop.name)
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke((2 * resources.displayMetrics.density).roundToInt(), Color.WHITE)
                val size = (sizeDp * resources.displayMetrics.density).roundToInt()
                setSize(size, size)
            }
        })
    }

    private fun addExactDestinationMarker(latitude: Double, longitude: Double) {
        binding.map.overlays.add(Marker(binding.map).apply {
            position = GeoPoint(latitude, longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = getString(R.string.final_destination)
            snippet = binding.destinationInput.text.toString()
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.lime))
                setStroke(
                    (4 * resources.displayMetrics.density).roundToInt(),
                    getColor(R.color.deep_green)
                )
                val size = (28 * resources.displayMetrics.density).roundToInt()
                setSize(size, size)
            }
        })
    }

    private fun frameCurrentJourney() {
        val destination = directDestination ?: return
        val walkingPoints = if (walkingMode) {
            walkingPath(currentLocation, destination).orEmpty()
        } else if (mixedJourneyChoice != null) {
            emptyList()
        } else {
            trainJourneyChoice?.let { train ->
                walkingPath(currentLocation, GeoPoint(train.boardAt.latitude, train.boardAt.longitude)).orEmpty() +
                    walkingPath(GeoPoint(train.destination.latitude, train.destination.longitude), destination).orEmpty()
            } ?: journeyChoice?.let { choice ->
                walkingPath(currentLocation, GeoPoint(choice.boardAt.latitude, choice.boardAt.longitude)).orEmpty() +
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
                journeyPoints(mixed.bus) +
                    mixed.train.stops.subList(mixed.train.boardIndex, mixed.train.destinationIndex + 1)
                        .map { GeoPoint(it.latitude, it.longitude) }
            } ?: trainJourneyChoice?.let { train ->
                train.stops.subList(train.boardIndex, train.destinationIndex + 1)
                    .map { GeoPoint(it.latitude, it.longitude) }
            } ?: journeyChoice?.let { choice ->
                journeyPoints(choice) + secondLegPoints(choice)
            }.orEmpty()
        }
        val points = transitPoints + walkingPoints + listOf(currentLocation, destination)
        binding.topControls.doOnLayout {
            binding.map.controller.stopAnimation(false)
            val padding = (32 * resources.displayMetrics.density).roundToInt()
            val top = binding.topControls.height
            val box = BoundingBox.fromGeoPoints(points)
            val width = (binding.map.width - 2 * padding).coerceAtLeast(1)
            val height = (binding.map.height - top - 2 * padding).coerceAtLeast(1)
            val zoom = org.osmdroid.views.MapView.getTileSystem().getBoundingBoxZoom(box, width, height)
                .coerceIn(binding.map.minZoomLevel, 18.0)
            binding.map.setMapCenterOffset(0, top / 2)
            binding.map.controller.setZoom(kotlin.math.floor(zoom))
            binding.map.controller.setCenter(box.centerWithDateLine)
        }
    }

    private fun showJourneyMessage(message: String) {
        hideTravelModeChoices()
        binding.journeyTitle.setText(R.string.your_journey)
        binding.locationSettingsButton.visibility = View.GONE
        binding.destinationLayout.error = null
        binding.sharedDirections.text = message
        binding.sharedDirections.visibility = View.VISIBLE
        binding.journeyCard.visibility = View.VISIBLE
    }

    private fun requestAlert() {
        if (trainJourneyChoice != null) {
            showJourneyMessage(getString(R.string.train_alert_unavailable))
            return
        }
        if (selectedPattern == null) {
            binding.sharedDirections.text = getString(R.string.alert_requires_direction)
            binding.sharedDirections.visibility = View.VISIBLE
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enableAlert()
        }
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
        binding.alertButton.text = getString(R.string.alert_enabled)
    }

    private fun addYouMarker() {
        val location = originLocation ?: return
        binding.map.overlays.add(Marker(binding.map).apply {
            position = GeoPoint(location.latitude, location.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = getString(R.string.you_are_here)
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
        val busDiameter = (if (closest) 30f else 21f) * density
        val arrowDiameter = (if (closest) 8f else 5f) * density
        val busRadius = busDiameter / 2f
        val arrowRadius = arrowDiameter / 2f
        val gap = 2f * density
        val extent = busRadius + gap + arrowDiameter
        val bitmapSize = (extent * 2f).roundToInt()
        val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = bitmapSize / 2f
        val color = if (closest) getColor(R.color.route_orange) else getColor(R.color.deep_green)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.style = Paint.Style.FILL
        paint.color = if (closest) color else Color.WHITE
        canvas.drawCircle(center, center, busRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = if (closest) Color.WHITE else color
        canvas.drawCircle(center, center, busRadius - paint.strokeWidth / 2f, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (closest) Color.WHITE else color
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = (if (closest) 12f else if (route.length > 2) 8f else 10f) * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(route, center, center - (paint.ascent() + paint.descent()) / 2, paint)

        bearing?.let {
            val angle = Math.toRadians(it.toDouble() - 90.0)
            val directionX = kotlin.math.cos(angle).toFloat()
            val directionY = kotlin.math.sin(angle).toFloat()
            val perpendicularX = -directionY
            val perpendicularY = directionX
            val separation = busRadius + gap + arrowRadius
            val arrowX = center + directionX * separation
            val arrowY = center + directionY * separation

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(arrowX, arrowY, arrowRadius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = color
            canvas.drawCircle(arrowX, arrowY, arrowRadius - paint.strokeWidth / 2f, paint)

            val tip = arrowRadius * 0.72f
            val back = arrowRadius * 0.48f
            val halfBase = arrowRadius * 0.48f
            val arrowPath = Path().apply {
                moveTo(arrowX + directionX * tip, arrowY + directionY * tip)
                lineTo(
                    arrowX - directionX * back + perpendicularX * halfBase,
                    arrowY - directionY * back + perpendicularY * halfBase
                )
                lineTo(
                    arrowX - directionX * back - perpendicularX * halfBase,
                    arrowY - directionY * back - perpendicularY * halfBase
                )
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawPath(arrowPath, paint)
        }
        return BitmapDrawable(resources, bitmap)
    }

    private fun distanceTo(vehicle: LiveVehicle): Float {
        val output = FloatArray(1)
        Location.distanceBetween(
            currentLocation.latitude, currentLocation.longitude,
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
        val enabled = deviceLocationEnabled()
        showJourneyMessage(getString(
            if (!enabled) R.string.device_location_off
            else if (!hasLocationPermission()) R.string.location_permission_required
            else R.string.location_unavailable
        ))
        binding.locationSettingsButton.visibility = if (enabled) View.GONE else View.VISIBLE
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

    private fun consumeReplan(intent: Intent?) {
        if (intent?.action != BusAlertService.ACTION_REPLAN) return
        val finalAddress = intent.getStringExtra(BusAlertService.EXTRA_FINAL_ADDRESS)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        intent.action = null
        AlertPreferences.clear(this)
        stopService(Intent(this, BusAlertService::class.java))
        binding.destinationInput.setText(finalAddress)
        journeyChoice = null
        trainJourneyChoice = null
        mixedJourneyChoice = null
        hideTravelModeChoices()
        binding.sharedDirections.text = getString(R.string.replanning_from_here)
        binding.sharedDirections.visibility = View.VISIBLE
        findDestinationRoute()
    }

    private fun saveLastDestination(destination: String) {
        getSharedPreferences(JOURNEY_STORE, MODE_PRIVATE)
            .edit()
            .putString(LAST_DESTINATION, destination)
            .apply()
    }

    private fun restoreLastJourney() {
        val destination = getSharedPreferences(JOURNEY_STORE, MODE_PRIVATE)
            .getString(LAST_DESTINATION, null)
            ?.takeIf { it.isNotBlank() }
            ?: return
        binding.destinationInput.setText(destination.substringBefore(" · "))
        binding.sharedDirections.text = getString(R.string.restoring_journey)
        binding.sharedDirections.visibility = View.VISIBLE
        binding.journeyCard.visibility = View.VISIBLE
        findDestinationRoute()
    }

    private fun normalizeRoute(route: String) =
        route.removePrefix("gtt:").replace(Regex("[A-Za-z]+$"), "")

    private fun selectWalkingMode() {
        if (journeyChoice == null && trainJourneyChoice == null) return
        planningJob?.cancel()
        walkingMode = true
        binding.boardStopDirectionsButton.visibility = View.GONE
        binding.journeyTitle.text = getString(R.string.walk_summary, minutes(directWalkingSeconds))
        binding.sharedDirections.text = getString(
            R.string.walking_selected,
            directWalkingMetres.roundToInt(),
            minutes(directWalkingSeconds)
        )
        binding.alertButton.visibility = View.GONE
        renderVehicles()
        frameCurrentJourney()
        directDestination?.let { openWalkingDirections(it) }
    }

    private fun selectBusMode() {
        if (journeyChoice == null && trainJourneyChoice == null) return
        planningJob?.cancel()
        walkingMode = false
        binding.boardStopDirectionsButton.visibility = View.VISIBLE
        val transitSeconds = mixedJourneyChoice?.estimatedTotalSeconds
            ?: trainJourneyChoice?.estimatedTotalSeconds
            ?: journeyChoice!!.estimatedTotalSeconds
        binding.journeyTitle.text = getString(R.string.transit_summary, minutes(transitSeconds))
        binding.sharedDirections.text = busJourneyDescription
        binding.alertButton.visibility = if (trainJourneyChoice == null) View.VISIBLE else View.GONE
        renderVehicles()
        frameCurrentJourney()
    }

    private fun hideTravelModeChoices() {
        walkingMode = false
        binding.travelModeChoices.visibility = View.GONE
        binding.boardStopDirectionsButton.visibility = View.GONE
        binding.alertButton.visibility = View.GONE
    }

    private fun directWalkingDistance(latitude: Double, longitude: Double): Float {
        walkingFromOrigin?.let { return it.estimatedMetresTo(latitude, longitude) }
        val result = FloatArray(1)
        Location.distanceBetween(
            currentLocation.latitude,
            currentLocation.longitude,
            latitude,
            longitude,
            result
        )
        // Street walking is normally longer than the straight map distance.
        return result[0] * WALKING_STREET_FACTOR
    }

    private fun openWalkingDirections(destination: GeoPoint) {
        val navigation = Uri.parse(
            "google.navigation:q=${destination.latitude},${destination.longitude}&mode=w"
        )
        try {
            startActivity(Intent(Intent.ACTION_VIEW, navigation).apply {
                setPackage(GOOGLE_MAPS_PACKAGE)
            })
        } catch (_: ActivityNotFoundException) {
            val webDirections = Uri.parse(
                "https://www.google.com/maps/dir/?api=1" +
                    "&origin=${currentLocation.latitude},${currentLocation.longitude}" +
                    "&destination=${destination.latitude},${destination.longitude}" +
                    "&travelmode=walking"
            )
            startActivity(Intent(Intent.ACTION_VIEW, webDirections))
        }
    }

    private fun minutes(seconds: Float) = (seconds / 60f).roundToInt().coerceAtLeast(1)

    private fun formatTime(seconds: Int): String = "%02d:%02d".format(
        (seconds / 3600) % 24,
        (seconds / 60) % 60
    )

    private fun TrainStop.asTransitStop() = TransitStop(id, name, latitude, longitude)

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            windowInsets
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
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
        const val LAST_DESTINATION = "last_destination"
        const val INCLUDE_TRAINS = "include_trains"
        const val WALKING_PREFERENCE = "walking_preference"
        const val WALKING_METRES_PER_SECOND = 1.35f
        const val WALKING_STREET_FACTOR = 1.2f
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        val ROUTE_PATTERN = Regex("""(?i)(?:bus|linea|line|route)\s*#?\s*(\d{1,3})""")
    }
}
