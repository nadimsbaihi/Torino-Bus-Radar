package it.mato.livebus

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val repository = GttRepository()
    private val transitIndex by lazy { TransitIndex.load(this) }
    private val trainIndex by lazy { TrainIndex.load(this) }
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var currentLocation = GeoPoint(45.0703, 7.6869)
    private var liveVehicles: List<LiveVehicle> = emptyList()
    private var refreshJob: Job? = null
    private var selectedRoute: String? = null
    private var selectedPattern: TransitPattern? = null
    private var journeyChoice: JourneyChoice? = null
    private var trainJourneyChoice: ScheduledTrainJourney? = null
    private var walkingMode = false
    private var directWalkingMetres = 0f
    private var directWalkingSeconds = 0f
    private var directDestination: GeoPoint? = null
    private var busJourneyDescription: CharSequence? = null
    private var automaticReplanPending = false
    private val busIconCache = mutableMapOf<Pair<Boolean, Int>, BitmapDrawable>()

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) loadLocation()
        else showLocationHint()
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

        setupMap()
        setupPlannerSheet()
        setupRoutePicker()
        setupDestinationPicker()
        binding.locationButton.setOnClickListener { requestLocation() }
        binding.refreshButton.setOnClickListener { refreshNow() }
        binding.findRouteButton.setOnClickListener { findDestinationRoute() }
        binding.alertButton.setOnClickListener { requestAlert() }
        binding.walkButton.setOnClickListener { selectWalkingMode() }
        binding.busAnywayButton.setOnClickListener { selectBusMode() }
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
        setTileSource(if (isDarkMode()) DARK_TILE_SOURCE else LIGHT_TILE_SOURCE)
        controller.setZoom(14.5)
        controller.setCenter(currentLocation)
    }

    private fun setupPlannerSheet() {
        BottomSheetBehavior.from(binding.plannerSheet).apply {
            peekHeight = (132 * resources.displayMetrics.density).roundToInt()
            isHideable = false
            binding.plannerSheet.post { state = BottomSheetBehavior.STATE_COLLAPSED }
        }
    }

    private fun setupRoutePicker() {
        val routes = listOf("10", "4", "13", "18", "55", "56", "58", "68")
        val labels = listOf(getString(R.string.all_routes)) + routes
        binding.routeInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        )
        binding.routeInput.setText(selectedRoute ?: getString(R.string.all_routes), false)
        binding.routeInput.setOnItemClickListener { _, _, position, _ ->
            selectedRoute = if (position == 0) null else routes[position - 1]
            journeyChoice = null
            trainJourneyChoice = null
            hideTravelModeChoices()
            binding.sharedDirections.visibility = View.GONE
            setupDirectionPicker()
            renderVehicles()
        }
        setupDirectionPicker()
    }

    private fun setupDestinationPicker() {
        binding.destinationInput.setOnEditorActionListener { _, _, _ ->
            findDestinationRoute()
            true
        }
    }

    private fun setupDirectionPicker(preferredPatternId: String? = null) {
        val route = selectedRoute
        val routePatterns = if (route == null) emptyList() else transitIndex.patternsForRoute(route)
        val options = routePatterns
            .groupBy { it.directionId to it.headsign }
            .map { (_, variants) ->
                variants.firstOrNull { it.id == preferredPatternId } ?: variants.first()
            }
        val labels = listOf(getString(R.string.all_directions)) + options.map { it.headsign }
        binding.directionInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        )
        val preferred = options.firstOrNull { it.id == preferredPatternId }
        selectedPattern = preferred
        binding.directionInput.setText(
            preferred?.headsign ?: getString(R.string.all_directions),
            false
        )
        binding.directionInput.setOnItemClickListener { _, _, position, _ ->
            selectedPattern = if (position == 0) null else options[position - 1]
            journeyChoice = null
            trainJourneyChoice = null
            hideTravelModeChoices()
            renderVehicles()
        }
    }

    private fun findDestinationRoute() {
        val destination = binding.destinationInput.text.toString()
        if (destination.isBlank()) return
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        binding.findRouteButton.isEnabled = false
        lifecycleScope.launch {
            val address = geocode(destination)
            val freshVehicleResult = runCatching { repository.vehicles() }
                .onSuccess {
                    liveVehicles = it
                    binding.liveStatus.text = getString(R.string.live_updated)
                    binding.liveStatus.setTextColor(getColor(R.color.live_green))
                    renderVehicles()
                }
                .onFailure {
                    Log.e(TAG, "Unable to get fresh positions for journey planning", it)
                }
            val journeyVehicles = freshVehicleResult.getOrElse { liveVehicles }
            val busChoice = address?.let {
                withContext(Dispatchers.Default) {
                    transitIndex.planLiveJourney(
                        currentLocation.latitude,
                        currentLocation.longitude,
                        it.first,
                        it.second,
                        journeyVehicles
                    )
                }
            }
            val trainChoice = address?.let {
                withContext(Dispatchers.Default) {
                    trainIndex.planScheduledJourney(
                        currentLocation.latitude,
                        currentLocation.longitude,
                        it.first,
                        it.second
                    )
                }
            }
            binding.findRouteButton.isEnabled = true
            automaticReplanPending = false
            if (address == null) {
                showJourneyMessage(getString(R.string.address_not_found))
                return@launch
            }
            if (journeyVehicles.isEmpty() && trainChoice == null) {
                showJourneyMessage(
                    getString(
                        if (freshVehicleResult.isSuccess) R.string.no_live_vehicles_now
                        else R.string.journey_feed_unavailable
                    )
                )
                return@launch
            }
            if (busChoice == null && trainChoice == null) {
                showJourneyMessage(getString(R.string.no_direct_route))
                return@launch
            }
            binding.destinationLayout.error = null
            saveLastDestination(destination.trim())
            val useTrain = trainChoice != null &&
                (busChoice == null || trainChoice.estimatedTotalSeconds < busChoice.estimatedTotalSeconds)
            journeyChoice = if (useTrain) null else busChoice
            trainJourneyChoice = if (useTrain) trainChoice else null
            val transitSeconds = trainJourneyChoice?.estimatedTotalSeconds
                ?: journeyChoice!!.estimatedTotalSeconds
            walkingMode = false
            directDestination = GeoPoint(address.first, address.second)
            directWalkingMetres = directWalkingDistance(address.first, address.second)
            directWalkingSeconds = directWalkingMetres / WALKING_METRES_PER_SECOND
            val choice = journeyChoice
            if (choice != null) {
                selectedRoute = choice.pattern.route
                binding.routeInput.setText(selectedRoute, false)
                setupDirectionPicker(choice.pattern.id)
            } else {
                selectedRoute = null
                selectedPattern = null
                binding.routeInput.setText(getString(R.string.all_routes), false)
                setupDirectionPicker()
            }
            busJourneyDescription = trainJourneyChoice?.let { train ->
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
                bus.secondLeg?.let { second ->
                    getString(
                        R.string.transfer_journey_found,
                        bus.pattern.route,
                        bus.pattern.headsign,
                        bus.vehicleDistanceMetres.roundToInt(),
                        TransitIndex.cleanStopName(bus.boardAt.name),
                        TransitIndex.cleanStopName(bus.transferAt?.name ?: second.boardAt.name),
                        bus.walkingMetres.roundToInt(),
                        (bus.boardingEtaSeconds / 60f).roundToInt().coerceAtLeast(1)
                    )
                } ?: getString(
                    R.string.journey_found,
                    bus.pattern.route,
                    bus.pattern.headsign,
                    bus.vehicleDistanceMetres.roundToInt(),
                    TransitIndex.cleanStopName(bus.boardAt.name),
                    bus.walkingMetres.roundToInt(),
                    (bus.boardingEtaSeconds / 60f).roundToInt().coerceAtLeast(1),
                    bus.stopCount,
                    TransitIndex.cleanStopName(bus.destination.name),
                    bus.destinationWalkMetres.roundToInt()
                )
            }
            binding.sharedDirections.text = busJourneyDescription
            binding.sharedDirections.visibility = View.VISIBLE
            if (directWalkingSeconds < transitSeconds) {
                binding.sharedDirections.text = getString(
                    R.string.walking_is_faster,
                    minutes(directWalkingSeconds),
                    directWalkingMetres.roundToInt(),
                    minutes(transitSeconds)
                )
                binding.travelModeChoices.visibility = View.VISIBLE
            } else {
                hideTravelModeChoices()
            }
            showJourneySheet()
            renderVehicles()
            frameCurrentJourney()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun geocode(query: String): Pair<Double, Double>? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(this, Locale.ITALY)
        return if (Build.VERSION.SDK_INT >= 33) {
            geocodeAsync(geocoder, query) ?: query
                .takeUnless { it.contains("Torino", ignoreCase = true) }
                ?.let { geocodeAsync(geocoder, "$it, Torino") }
        } else {
            withContext(Dispatchers.IO) {
                geocoder.getFromLocationName(query, 1)?.firstOrNull()?.let {
                    it.latitude to it.longitude
                } ?: query
                    .takeUnless { it.contains("Torino", ignoreCase = true) }
                    ?.let { fallback ->
                        geocoder.getFromLocationName("$fallback, Torino", 1)
                            ?.firstOrNull()
                            ?.let { it.latitude to it.longitude }
                    }
            }
        }
    }

    private suspend fun geocodeAsync(
        geocoder: Geocoder,
        query: String
    ): Pair<Double, Double>? = suspendCancellableCoroutine { continuation ->
        geocoder.getFromLocationName(query, 1) { results ->
            continuation.resume(results.firstOrNull()?.let { it.latitude to it.longitude })
        }
    }

    private fun startRefreshing() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                fetchVehicles()
                delay(15_000)
            }
        }
    }

    private fun refreshNow() {
        refreshJob?.cancel()
        startRefreshing()
    }

    private suspend fun fetchVehicles() {
        binding.liveStatus.text = getString(R.string.connecting)
        runCatching { repository.vehicles() }
            .onSuccess {
                liveVehicles = it
                binding.liveStatus.text = getString(R.string.live_updated)
                binding.liveStatus.setTextColor(getColor(R.color.live_green))
                if (activeBusNeedsReplan(it)) {
                    requestAutomaticReplan()
                } else {
                    renderVehicles()
                }
            }
            .onFailure {
                if (it is CancellationException) return
                Log.e(TAG, "Unable to refresh GTT live positions", it)
                binding.liveStatus.text = getString(R.string.feed_unavailable)
                binding.liveStatus.setTextColor(getColor(R.color.warning))
            }
    }

    private fun activeBusNeedsReplan(vehicles: List<LiveVehicle>): Boolean {
        val journey = journeyChoice ?: return false
        if (walkingMode || automaticReplanPending) return false
        val vehicle = vehicles.firstOrNull { it.id == journey.vehicle.id } ?: return true
        val pattern = transitIndex.exactTripPattern(vehicle)
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
        addYouMarker()

        if (trainJourneyChoice != null) {
            binding.closestCard.visibility = View.GONE
            binding.vehicleCount.text = getString(R.string.scheduled_train_no_live_vehicles)
            binding.map.invalidate()
            return
        }

        val matches = liveVehicles
            .filter { vehicle ->
                selectedRoute?.let { normalizeRoute(vehicle.routeId) == it } ?: true
            }
            .filter { vehicle ->
                selectedPattern?.let { selected ->
                    transitIndex.matchDirection(vehicle)?.let { actual ->
                        actual.directionId == selected.directionId &&
                            actual.headsign == selected.headsign
                    } == true
                } ?: true
            }
            .filter { vehicle ->
                journeyChoice?.let { journey ->
                    val pattern = transitIndex.exactTripPattern(vehicle)
                    pattern?.id == journey.pattern.id &&
                        transitIndex.isStopIndexAhead(vehicle, pattern, journey.boardStopIndex)
                } ?: true
            }
            .sortedBy { distanceTo(it) }

        val isLocalOverview = selectedRoute == null && selectedPattern == null
        val markers = if (isLocalOverview) matches.take(MAX_OVERVIEW_VEHICLES) else matches

        markers.forEachIndexed { index, vehicle ->
            val vehicleRoute = normalizeRoute(vehicle.routeId)
            val marker = Marker(binding.map).apply {
                position = GeoPoint(vehicle.latitude, vehicle.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = selectedPattern?.let {
                    "${getString(R.string.vehicle_title, vehicleRoute)} → ${it.headsign}"
                } ?: getString(R.string.vehicle_title, vehicleRoute)
                snippet = getString(R.string.distance_away, distanceTo(vehicle).roundToInt())
                icon = busDirectionMarker(index == 0, vehicle.bearing)
            }
            binding.map.overlays.add(marker)
        }

        val nearest = matches.firstOrNull()
        binding.closestCard.visibility = if (nearest == null) View.GONE else View.VISIBLE
        nearest?.let {
            val metres = distanceTo(it).roundToInt()
            binding.closestRoute.text = normalizeRoute(it.routeId)
            binding.closestDistance.text = getString(R.string.distance_away, metres)
            binding.closestEta.text = getString(R.string.estimated_minutes, (metres / 220).coerceAtLeast(1))
        }
        binding.vehicleCount.text = selectedRoute?.let { route ->
            resources.getQuantityString(
                R.plurals.live_vehicle_count, matches.size, matches.size, route
            )
        } ?: if (matches.size > markers.size) {
            getString(R.string.live_vehicle_overview_count, markers.size, matches.size)
        } else {
            resources.getQuantityString(
                R.plurals.live_vehicle_all_count, matches.size, matches.size
            )
        }
        binding.map.invalidate()
    }

    private fun drawJourneyLine() {
        trainJourneyChoice?.let { train ->
            drawTrainJourneyLine(train)
            return
        }
        val choice = journeyChoice ?: return
        if (walkingMode) {
            val destination = directDestination ?: return
            addWalkingConnector(currentLocation, destination)
            addExactDestinationMarker(destination.latitude, destination.longitude)
            return
        }
        val points = journeyPoints(choice)
        addWalkingConnector(
            currentLocation,
            GeoPoint(choice.boardAt.latitude, choice.boardAt.longitude)
        )
        addWalkingConnector(
            GeoPoint(choice.destination.latitude, choice.destination.longitude),
            GeoPoint(choice.finalLatitude, choice.finalLongitude)
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

        addJourneyStopMarker(
            choice.destination,
            getString(R.string.destination_stop),
            getColor(R.color.deep_green),
            sizeDp = 18
        )
        addExactDestinationMarker(choice.finalLatitude, choice.finalLongitude)
    }

    private fun drawTrainJourneyLine(train: ScheduledTrainJourney) {
        val points = train.stops.subList(train.boardIndex, train.destinationIndex + 1)
            .map { GeoPoint(it.latitude, it.longitude) }
        addWalkingConnector(currentLocation, GeoPoint(train.boardAt.latitude, train.boardAt.longitude))
        addWalkingConnector(
            GeoPoint(train.destination.latitude, train.destination.longitude),
            directDestination ?: GeoPoint(train.destination.latitude, train.destination.longitude)
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
        directDestination?.let { addExactDestinationMarker(it.latitude, it.longitude) }
    }

    private fun addWalkingConnector(from: GeoPoint, to: GeoPoint) {
        addWalkingPath(listOf(from, to))
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

    private fun frameJourney(choice: JourneyChoice) {
        if (walkingMode) {
            val destination = directDestination ?: return
            binding.map.post {
                binding.map.zoomToBoundingBox(
                    BoundingBox.fromGeoPoints(listOf(currentLocation, destination)),
                    true,
                    180
                )
            }
            return
        }
        val points = journeyPoints(choice) + listOf(
            currentLocation,
            GeoPoint(choice.destination.latitude, choice.destination.longitude),
            GeoPoint(choice.finalLatitude, choice.finalLongitude)
        )
        if (points.size < 2) return
        binding.map.post {
            binding.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 180)
        }
    }

    private fun frameCurrentJourney() {
        trainJourneyChoice?.let { train ->
            val points = train.stops.subList(train.boardIndex, train.destinationIndex + 1)
                .map { GeoPoint(it.latitude, it.longitude) } + listOf(currentLocation) +
                listOfNotNull(directDestination)
            binding.map.post {
                binding.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 180)
            }
            return
        }
        journeyChoice?.let(::frameJourney)
    }

    private fun showJourneySheet() {
        BottomSheetBehavior.from(binding.plannerSheet).apply {
            peekHeight = (230 * resources.displayMetrics.density).roundToInt()
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun showJourneyMessage(message: String) {
        hideTravelModeChoices()
        binding.destinationLayout.error = null
        binding.sharedDirections.text = message
        binding.sharedDirections.visibility = View.VISIBLE
        showJourneySheet()
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
        binding.map.overlays.add(Marker(binding.map).apply {
            position = currentLocation
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = getString(R.string.you_are_here)
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_my_location)
        })
    }

    private fun busDirectionMarker(closest: Boolean, bearing: Float?): BitmapDrawable {
        val bearingBucket = bearing?.let {
            (((it + 11.25f) / 22.5f).toInt() % 16)
        } ?: -1
        return busIconCache.getOrPut(closest to bearingBucket) {
            createBusDirectionMarker(
                closest,
                bearingBucket.takeIf { it >= 0 }?.times(22.5f)
            )
        }
    }

    private fun createBusDirectionMarker(closest: Boolean, bearing: Float?): BitmapDrawable {
        val density = resources.displayMetrics.density
        val busDiameter = (if (closest) 28f else 24f) * density
        val arrowDiameter = (if (closest) 12f else 10f) * density
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
        paint.color = color
        canvas.drawCircle(center, center, busRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, busRadius - paint.strokeWidth / 2f, paint)

        val busInset = 5f * density
        val bus = ContextCompat.getDrawable(this, R.drawable.ic_bus_notification)!!
            .mutate()
            .also { DrawableCompat.setTint(it, Color.WHITE) }
        bus.setBounds(
            (center - busRadius + busInset).roundToInt(),
            (center - busRadius + busInset).roundToInt(),
            (center + busRadius - busInset).roundToInt(),
            (center + busRadius - busInset).roundToInt()
        )
        bus.draw(canvas)

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

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) loadLocation()
        else locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun loadLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        binding.locationButton.isEnabled = false
        locationClient.lastLocation
            .addOnSuccessListener { location ->
                location?.let {
                    currentLocation = GeoPoint(it.latitude, it.longitude)
                    binding.map.controller.animateTo(currentLocation)
                    renderVehicles()
                } ?: showLocationHint()
            }
            .addOnCompleteListener { binding.locationButton.isEnabled = true }
    }

    private fun showLocationHint() {
        binding.liveStatus.text = getString(R.string.location_hint)
    }

    private fun consumeSharedDirections(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val route = ROUTE_PATTERN.find(shared)?.groupValues?.get(1) ?: return
        selectedRoute = route
        binding.routeInput.setText(route, false)
        setupDirectionPicker()
        binding.sharedDirections.text = getString(R.string.shared_route_found, route)
        binding.sharedDirections.visibility = View.VISIBLE
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
        hideTravelModeChoices()
        binding.sharedDirections.text = getString(R.string.replanning_from_here)
        binding.sharedDirections.visibility = View.VISIBLE
        lifecycleScope.launch {
            loadLocation()
            delay(1_000)
            fetchVehicles()
            findDestinationRoute()
        }
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
        binding.destinationInput.setText(destination)
        binding.sharedDirections.text = getString(R.string.restoring_journey)
        binding.sharedDirections.visibility = View.VISIBLE
        showJourneySheet()
        lifecycleScope.launch {
            delay(1_500)
            findDestinationRoute()
        }
    }

    private fun normalizeRoute(route: String) =
        route.removePrefix("gtt:").replace(Regex("[A-Za-z]+$"), "")

    private fun selectWalkingMode() {
        if (journeyChoice == null && trainJourneyChoice == null) return
        walkingMode = true
        binding.sharedDirections.text = getString(
            R.string.walking_selected,
            directWalkingMetres.roundToInt(),
            minutes(directWalkingSeconds)
        )
        binding.alertButton.visibility = View.GONE
        renderVehicles()
        frameCurrentJourney()
        openWalkingDirections()
    }

    private fun selectBusMode() {
        if (journeyChoice == null && trainJourneyChoice == null) return
        walkingMode = false
        binding.sharedDirections.text = busJourneyDescription
        binding.alertButton.visibility = if (trainJourneyChoice == null) View.VISIBLE else View.GONE
        renderVehicles()
        frameCurrentJourney()
    }

    private fun hideTravelModeChoices() {
        walkingMode = false
        binding.travelModeChoices.visibility = View.GONE
        binding.alertButton.visibility = View.VISIBLE
    }

    private fun directWalkingDistance(latitude: Double, longitude: Double): Float {
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

    private fun openWalkingDirections() {
        val destination = directDestination ?: return
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
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }
    }

    private fun isDarkMode() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    private companion object {
        const val TAG = "MatoLiveBus"
        const val JOURNEY_STORE = "active_journey"
        const val LAST_DESTINATION = "last_destination"
        const val WALKING_METRES_PER_SECOND = 1.35f
        const val WALKING_STREET_FACTOR = 1.2f
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        const val MAX_OVERVIEW_VEHICLES = 35
        val LIGHT_TILE_SOURCE = XYTileSource(
            "CartoLight", 0, 20, 256, ".png",
            arrayOf("https://a.basemaps.cartocdn.com/light_all/", "https://b.basemaps.cartocdn.com/light_all/", "https://c.basemaps.cartocdn.com/light_all/"),
            "© OpenStreetMap contributors © CARTO"
        )
        val DARK_TILE_SOURCE = XYTileSource(
            "CartoDark", 0, 20, 256, ".png",
            arrayOf("https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/", "https://c.basemaps.cartocdn.com/dark_all/"),
            "© OpenStreetMap contributors © CARTO"
        )
        val ROUTE_PATTERN = Regex("""(?i)(?:bus|linea|line|route)\s*#?\s*(\d{1,3})""")
    }
}
