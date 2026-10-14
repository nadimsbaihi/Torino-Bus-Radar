package it.mato.livebus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.Manifest
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.annotation.SuppressLint
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.withTimeoutOrNull
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class BusAlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = GttRepository()
    private lateinit var transitIndex: TransitIndex
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var pollingJob: Job? = null
    private val previousDistances = mutableMapOf<String, Float>()
    // One heads-up per journey leg: the bus to catch is announced once, not every bus behind it.
    private var approachNotifiedAt: Long? = null
    // Vehicle IDs are reused all day, so "has passed" is per trip and forgotten after a while.
    private val passedVehicles = mutableMapOf<String, Long>()
    private var transferNotified = false
    // Once on the bus only the transfer is still worth watching.
    private var boarded = false
    private var lastStatus = ""

    override fun onCreate() {
        super.onCreate()
        transitIndex = TransitIndex.load(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        val alert = AlertPreferences.load(this)
        if (alert == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        lastStatus = getString(R.string.watching_stop, alert.stopName)
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            ongoingNotification(alert, lastStatus),
            if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        previousDistances.clear()
        approachNotifiedAt = null
        boarded = false
        transferNotified = false
        pollingJob?.cancel()
        pollingJob = scope.launch { poll(alert) }
        return START_STICKY
    }

    /** Ends the alert for good: nothing is left to restart it, and its notification goes away. */
    private fun finish() {
        AlertPreferences.clear(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun poll(initialAlert: BusAlert) {
        var alert = initialAlert
        var targetPattern = transitIndex.patterns.firstOrNull { it.id == alert.patternId }
        var needsFullReplan = false
        var nextDelay = POLL_MILLIS
        while (scope.isActive) {
            nextDelay = POLL_MILLIS
            try {
                if (checkTransferArrival(alert)) {
                    finish()
                    return
                }
                // With no transfer ahead, the job ends once the bus has been announced and had time
                // to come; a rider who missed it is caught below when it passes the stop.
                val announcedAt = approachNotifiedAt
                if (announcedAt != null && alert.transferLatitude == null &&
                    SystemClock.elapsedRealtime() - announcedAt > AFTER_APPROACH_MILLIS
                ) {
                    finish()
                    return
                }
                if (boarded) {
                    delay(POLL_MILLIS)
                    continue
                }
                val allVehicles = transitIndex.inService(repository.vehicles())
                if (needsFullReplan) {
                    val replanned = replanCompleteJourney(alert, allVehicles)
                    if (replanned == null) {
                        delay(POLL_MILLIS)
                        continue
                    }
                    alert = replanned
                    targetPattern = transitIndex.patterns.firstOrNull {
                        it.id == alert.patternId
                    }
                    needsFullReplan = false
                }

                val currentTargetPattern = targetPattern
                val stopId = alert.stopId
                val directionVehicles = allVehicles
                    .asSequence()
                    .filter { it.routeId == alert.routeId }
                    .mapNotNull { vehicle ->
                        val pattern = transitIndex.matchDirection(vehicle)
                            ?: return@mapNotNull null
                        // Same direction is what matters; a short-turn variant with a different
                        // headsign still counts as long as it serves the stop being watched.
                        if (currentTargetPattern != null &&
                            pattern.directionId != currentTargetPattern.directionId
                        ) return@mapNotNull null
                        if (stopId != null && pattern.stops.none { it.id == stopId }) return@mapNotNull null
                        vehicle to pattern
                    }
                    .toList()

                val selected = alert.vehicleId?.let { selectedId ->
                    directionVehicles.firstOrNull { (vehicle, _) -> vehicle.id == selectedId }
                }
                val selectedPassed = stopId != null && selected != null &&
                    !transitIndex.isBoardingStopAhead(
                        selected.first, selected.second, stopId
                    )
                if (selectedPassed) {
                    passedVehicles[passedKey(selected.first)] = SystemClock.elapsedRealtime()
                    if (isOnBoard(selected.first, alert)) {
                        if (alert.transferLatitude == null) {
                            finish()
                            return
                        }
                        boarded = true
                        updateStatus(alert, getString(R.string.on_board_status, alert.transferName))
                        delay(POLL_MILLIS)
                        continue
                    }
                    needsFullReplan = true
                    val replanned = replanCompleteJourney(alert, allVehicles)
                    if (replanned != null) {
                        alert = replanned
                        targetPattern = transitIndex.patterns.firstOrNull {
                            it.id == alert.patternId
                        }
                        needsFullReplan = false
                    } else {
                        val waiting = alert.copy(vehicleId = null)
                        AlertPreferences.save(this, waiting)
                        alert = waiting
                        notifyJourneyReplanUnavailable(alert)
                        updateStatus(alert, getString(R.string.watching_stop, alert.stopName))
                        delay(POLL_MILLIS)
                        continue
                    }
                }

                val watchedVehicles = alert.vehicleId?.let { watchedId ->
                    directionVehicles.filter { it.first.id == watchedId }
                } ?: directionVehicles
                var closest = Float.MAX_VALUE
                // The alert may just have been replanned onto another stop.
                val watchedStopId = alert.stopId
                watchedVehicles.forEach { (vehicle, pattern) ->
                    // Along the route, not as the crow flies: a bus on a parallel street is not
                    // approaching, and one that has already passed the stop is not either.
                    val distance = if (watchedStopId == null) distanceToStop(vehicle, alert)
                        else transitIndex.routeMetresToStop(vehicle, pattern, watchedStopId)
                    if (distance == null) {
                        previousDistances.remove(vehicle.id)
                        return@forEach
                    }
                    closest = minOf(closest, distance)
                    val previous = previousDistances.put(vehicle.id, distance)
                    val approaching = previous == null || distance < previous - 8
                    if (distance <= alert.thresholdMetres && approaching && approachNotifiedAt == null) {
                        approachNotifiedAt = SystemClock.elapsedRealtime()
                        notifyApproaching(alert, distance)
                    }
                }
                updateStatus(alert, when {
                    closest == Float.MAX_VALUE -> getString(R.string.watching_stop, alert.stopName)
                    approachNotifiedAt != null ->
                        getString(R.string.bus_near_status, formatDistance(closest), alert.stopName)
                    else -> getString(R.string.bus_distance_status, formatDistance(closest), alert.stopName)
                })
                previousDistances.keys.retainAll(watchedVehicles.mapTo(mutableSetOf()) { it.first.id })
                nextDelay = if (closest <= NEAR_METRES) NEAR_POLL_MILLIS else POLL_MILLIS
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Background bus alert refresh failed", error)
            }
            delay(nextDelay)
        }
    }

    private suspend fun replanCompleteJourney(
        alert: BusAlert,
        vehicles: List<LiveVehicle>
    ): BusAlert? {
        val finalLatitude = alert.finalLatitude ?: return null
        val finalLongitude = alert.finalLongitude ?: return null
        val location = lastKnownLocation() ?: return null
        val city = (application as MatoApplication).offlineCity
        val fromOrigin = city.walkingDistancesFrom(location.latitude, location.longitude)
        val fromDestination = city.walkingDistancesFrom(finalLatitude, finalLongitude)
        val choice = transitIndex.planLiveJourney(
            location.latitude,
            location.longitude,
            finalLatitude,
            finalLongitude,
            vehicles.filterNot(::hasPassed),
            walkFromOrigin = fromOrigin::estimatedMetresTo,
            walkFromDestination = fromDestination::estimatedMetresTo,
            walkBetweenStops = { startLat, startLon, endLat, endLon ->
                city.shortWalkMetres(startLat, startLon, endLat, endLon)
                    ?: Float.POSITIVE_INFINITY
            }
        ) ?: return null
        val updated = BusAlert(
            route = choice.pattern.route,
            routeId = choice.pattern.routeId,
            patternId = choice.pattern.id,
            headsign = choice.pattern.headsign,
            vehicleId = choice.vehicle.id,
            stopId = choice.boardAt.id,
            stopName = TransitIndex.cleanStopName(choice.boardAt.name),
            latitude = choice.boardAt.latitude,
            longitude = choice.boardAt.longitude,
            thresholdMetres = alert.thresholdMetres,
            finalAddress = alert.finalAddress,
            finalLatitude = finalLatitude,
            finalLongitude = finalLongitude,
            transferName = choice.transferAt?.let { TransitIndex.cleanStopName(it.name) },
            transferLatitude = choice.transferAt?.latitude,
            transferLongitude = choice.transferAt?.longitude,
            startedAt = alert.startedAt
        )
        AlertPreferences.save(this, updated)
        previousDistances.clear()
        approachNotifiedAt = null
        notifyJourneyReplanned(updated)
        updateStatus(updated, getString(R.string.watching_stop, updated.stopName))
        return updated
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnownLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val cancellation = CancellationTokenSource()
                continuation.invokeOnCancellation { cancellation.cancel() }
                val request = CurrentLocationRequest.Builder()
                    .setMaxUpdateAgeMillis(30_000)
                    .setDurationMillis(10_000)
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .build()
                locationClient.getCurrentLocation(request, cancellation.token)
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) continuation.resume(location?.takeIf {
                            SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..30_000_000_000L
                        })
                    }
                    .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
            }
        }
    }

    /** True once the rider has been told they reached the transfer, which is the alert's last job. */
    private suspend fun checkTransferArrival(alert: BusAlert): Boolean {
        val transferLat = alert.transferLatitude ?: return false
        val transferLon = alert.transferLongitude ?: return false
        if (transferNotified) return true
        val location = lastKnownLocation() ?: return false
        val result = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude, transferLat, transferLon, result
        )
        if (location.hasAccuracy() && location.accuracy <= TRANSFER_RADIUS_METRES &&
            result[0] <= TRANSFER_RADIUS_METRES) {
            transferNotified = true
            notifyReplan(alert)
        }
        return transferNotified
    }

    /**
     * Whether a bus that just passed the boarding stop took the rider with it: someone on board is
     * nearer the bus than the stop, someone who missed it is still standing at the stop.
     */
    private suspend fun isOnBoard(vehicle: LiveVehicle, alert: BusAlert): Boolean {
        val location = lastKnownLocation() ?: return false
        val toBus = FloatArray(1)
        val toStop = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, vehicle.latitude, vehicle.longitude, toBus)
        Location.distanceBetween(location.latitude, location.longitude, alert.latitude, alert.longitude, toStop)
        return toStop[0] > ON_BOARD_MIN_METRES && toBus[0] < toStop[0]
    }

    private fun formatDistance(metres: Float): String =
        if (metres < 1_000f) getString(R.string.distance_metres, (metres / 10).roundToInt() * 10)
        else getString(R.string.distance_kilometres, metres / 1_000f)

    private fun passedKey(vehicle: LiveVehicle) = "${vehicle.id}|${vehicle.tripId.orEmpty()}"

    private fun hasPassed(vehicle: LiveVehicle): Boolean {
        val key = passedKey(vehicle)
        val since = passedVehicles[key] ?: return false
        if (SystemClock.elapsedRealtime() - since <= PASSED_MEMORY_MILLIS) return true
        passedVehicles.remove(key)
        return false
    }

    private fun distanceToStop(vehicle: LiveVehicle, alert: BusAlert): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            vehicle.latitude, vehicle.longitude,
            alert.latitude, alert.longitude, result
        )
        return result[0]
    }

    /** Keeps the one live notification current, as navigation apps do, without buzzing for it. */
    private fun updateStatus(alert: BusAlert, status: String) {
        if (status == lastStatus) return
        lastStatus = status
        getSystemService(NotificationManager::class.java).notify(ONGOING_ID, ongoingNotification(alert, status))
    }

    private fun ongoingNotification(alert: BusAlert, status: String) =
        NotificationCompat.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(getString(R.string.watching_bus, alert.route, alert.headsign))
            .setContentText(status)
            .setContentIntent(openAppIntent())
            .addAction(R.drawable.ic_close, getString(R.string.alert_stop_action), stopIntent())
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, BusAlertService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notifyApproaching(alert: BusAlert, distance: Float) {
        val notification = NotificationCompat.Builder(this, APPROACH_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(getString(R.string.bus_approaching, alert.route))
            .setContentText(
                getString(
                    R.string.bus_approaching_detail,
                    alert.headsign,
                    distance.roundToInt(),
                    alert.stopName
                )
            )
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Old news once the bus has come; don't leave it in the shade.
            .setTimeoutAfter(AFTER_APPROACH_MILLIS)
            .build()
        getSystemService(NotificationManager::class.java).notify(APPROACH_ID, notification)
    }

    private fun notifyJourneyReplanned(alert: BusAlert) {
        val notification = NotificationCompat.Builder(this, APPROACH_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(getString(R.string.journey_recalculated))
            .setContentText(
                getString(
                    R.string.new_journey_detail,
                    alert.route,
                    alert.stopName
                )
            )
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(REPLACEMENT_ID, notification)
    }

    private fun notifyJourneyReplanUnavailable(alert: BusAlert) {
        val notification = NotificationCompat.Builder(this, APPROACH_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(getString(R.string.selected_bus_passed))
            .setContentText(getString(R.string.recalculating_until_available))
            .setContentIntent(openAppIntent(alert.finalAddress))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(REPLACEMENT_ID, notification)
    }

    private fun notifyReplan(alert: BusAlert) {
        val notification = NotificationCompat.Builder(this, APPROACH_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(getString(R.string.transfer_reached, alert.transferName))
            .setContentText(getString(R.string.tap_to_replan))
            .setContentIntent(openAppIntent(alert.finalAddress))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(REPLAN_ID, notification)
    }

    private fun openAppIntent(replanAddress: String? = null): PendingIntent = PendingIntent.getActivity(
        this,
        if (replanAddress == null) 0 else 1,
        Intent(this, MainActivity::class.java).apply {
            if (replanAddress != null) {
                action = ACTION_REPLAN
                putExtra(EXTRA_FINAL_ADDRESS, replanAddress)
            }
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL,
                getString(R.string.monitor_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                APPROACH_CHANNEL,
                getString(R.string.approach_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BusAlertService"
        private const val MONITOR_CHANNEL = "bus_monitor"
        private const val APPROACH_CHANNEL = "bus_approach"
        private const val ONGOING_ID = 210
        private const val APPROACH_ID = 211
        private const val REPLAN_ID = 212
        private const val REPLACEMENT_ID = 213
        private const val TRANSFER_RADIUS_METRES = 250f
        private const val POLL_MILLIS = 30_000L
        // A bus this close can reach the stop between two slow polls; look sooner.
        private const val NEAR_POLL_MILLIS = 15_000L
        private const val NEAR_METRES = 1_500f
        private const val PASSED_MEMORY_MILLIS = 20 * 60_000L
        private const val AFTER_APPROACH_MILLIS = 10 * 60_000L
        // Closer than this to the stop, GPS can't tell the rider from someone still waiting.
        private const val ON_BOARD_MIN_METRES = 60f
        const val ACTION_STOP = "it.mato.livebus.STOP_ALERT"
        const val ACTION_REPLAN = "it.mato.livebus.REPLAN"
        const val EXTRA_FINAL_ADDRESS = "final_address"
    }
}
