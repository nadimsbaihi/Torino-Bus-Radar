package it.mato.livebus

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Tells the rider when to set off for their bus, so they reach the stop with the spare time they
 * asked for, then follows that bus until it has taken them. A bus that leaves without them hands
 * over to the next one of the line, and the reminder starts again for it.
 */
class BusAlertService : Service() {
    private enum class Phase {
        // The rider has not been told to go yet; the bus to go for can still change.
        WAITING,
        // Told to go (or already at the stop): only this bus is followed.
        HEADING,
        // The bus has just passed the stop: is the rider on it?
        CHECKING
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = GttRepository.shared
    private lateinit var transitIndex: TransitIndex
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var pollingJob: Job? = null
    // Vehicle IDs are reused all day, so "has passed" is forgotten after a while.
    private val passedVehicles = mutableMapOf<String, Long>()
    private var lastStatus = ""

    // The last walk worked out, kept while the rider has not moved much since.
    private var walkFrom: Location? = null
    private var walkMetres = 0f

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
        lastStatus = getString(R.string.status_looking, alert.route, alert.stopName)
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            ongoingNotification(alert, lastStatus),
            if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        walkFrom = null
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

    private suspend fun poll(alert: BusAlert) {
        val direction = transitIndex.patterns.firstOrNull { it.id == alert.patternId }?.directionId
        var phase = Phase.WAITING
        var targetId: String? = alert.vehicleId
        var lastTargetEta = Float.MAX_VALUE
        var lostPolls = 0
        var boarding = BoardingCheck()
        while (scope.isActive) {
            var nextDelay = POLL_MILLIS
            try {
                if (System.currentTimeMillis() - alert.startedAt > AlertPreferences.MAX_AGE_MILLIS) {
                    finish()
                    return
                }
                val vehicles = transitIndex.inService(repository.vehicles())
                val tracked = transitIndex.trackVehicles(vehicles)
                BusSpeedStore.save(applicationContext)
                val onLine = tracked.filter {
                    it.pattern.routeId == alert.routeId && (direction == null || it.pattern.directionId == direction)
                }
                // A bus can be listed twice (its trip now and the one after turning round): the
                // soonest it reaches the stop is what counts.
                val arrivals = onLine
                    .mapNotNull { entry -> etaAt(entry, alert.stopId)?.let { entry.vehicle.id to it } }
                    .groupBy({ it.first }, { it.second })
                    .map { (id, etas) -> DepartureTiming.Arrival(id, etas.min()) }
                    .filterNot { hasPassed(it.vehicleId) }

                if (phase == Phase.CHECKING) {
                    val id = targetId
                    val bus = vehicles.firstOrNull { it.id == id }
                    val location = currentLocation(Priority.PRIORITY_HIGH_ACCURACY, FRESH_FIX_MILLIS)
                    val verdict = boarding.record(
                        location?.let { metresBetween(it, alert.latitude, alert.longitude) },
                        if (location != null && bus != null) metresBetween(location, bus.latitude, bus.longitude) else null
                    )
                    when (verdict) {
                        BoardingCheck.Verdict.ON_BOARD -> {
                            finish()
                            return
                        }
                        BoardingCheck.Verdict.MISSED -> {
                            notifyMissed(alert, arrivals.minByOrNull { it.etaSeconds })
                            phase = Phase.WAITING
                            targetId = null
                            lastTargetEta = Float.MAX_VALUE
                        }
                        BoardingCheck.Verdict.UNSURE -> {
                            updateStatus(alert, getString(R.string.status_checking, alert.stopName))
                            delay(NEAR_POLL_MILLIS)
                            continue
                        }
                    }
                }

                val followedId = targetId
                val target = arrivals.firstOrNull { it.vehicleId == followedId }
                if (phase == Phase.HEADING && target == null && followedId != null) {
                    val stillOnLine = onLine.any { it.vehicle.id == followedId }
                    if (stillOnLine && lastTargetEta <= PASS_WINDOW_SECONDS) {
                        // It was due and the stop is now behind it: it has come.
                        passedVehicles[followedId] = SystemClock.elapsedRealtime()
                        phase = Phase.CHECKING
                        boarding = BoardingCheck()
                        updateStatus(alert, getString(R.string.status_checking, alert.stopName))
                        delay(NEAR_POLL_MILLIS)
                        continue
                    }
                    // Out of the feed for a moment, or matched to the wrong trip: give it a few polls.
                    if (++lostPolls < LOST_POLLS_BEFORE_SWITCH) {
                        delay(POLL_MILLIS)
                        continue
                    }
                }
                lostPolls = 0

                when (phase) {
                    Phase.WAITING -> {
                        val walk = walkToStop(alert)
                        if (walk == null) {
                            updateStatus(alert, getString(R.string.status_locating))
                            delay(POLL_MILLIS)
                            continue
                        }
                        val bus = DepartureTiming.chooseBus(arrivals, targetId, walk, alert.spareMinutes)
                        if (bus == null) {
                            targetId = null
                            updateStatus(alert, getString(R.string.status_looking, alert.route, alert.stopName))
                        } else {
                            targetId = bus.vehicleId
                            lastTargetEta = bus.etaSeconds
                            val leaveIn = DepartureTiming.leaveInSeconds(bus.etaSeconds, walk, alert.spareMinutes)
                            if (walk < DepartureTiming.AT_STOP_METRES) {
                                // Already at the stop: nothing to set off for, just the bus to wait for.
                                phase = Phase.HEADING
                                updateStatus(alert, busStatus(alert, bus.etaSeconds))
                            } else if (leaveIn <= FIRE_EARLY_SECONDS) {
                                notifyLeave(alert, bus.etaSeconds, walk)
                                phase = Phase.HEADING
                                updateStatus(alert, busStatus(alert, bus.etaSeconds))
                            } else {
                                updateStatus(alert, getString(R.string.status_leave_in,
                                    ceilMinutes(leaveIn), alert.stopName, ceilMinutes(bus.etaSeconds)))
                                if (leaveIn < NEAR_SECONDS) nextDelay = NEAR_POLL_MILLIS
                            }
                        }
                    }
                    Phase.HEADING -> {
                        // The bus followed was lost for good: follow the soonest one instead.
                        val bus = target ?: arrivals.minByOrNull { it.etaSeconds }
                        targetId = bus?.vehicleId
                        if (bus == null) {
                            updateStatus(alert, getString(R.string.status_looking, alert.route, alert.stopName))
                        } else {
                            lastTargetEta = bus.etaSeconds
                            updateStatus(alert, busStatus(alert, bus.etaSeconds))
                            if (bus.etaSeconds < NEAR_SECONDS) nextDelay = NEAR_POLL_MILLIS
                        }
                    }
                    Phase.CHECKING -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Background bus alert refresh failed", error)
            }
            delay(nextDelay)
        }
    }

    /** Seconds until [entry] reaches [stopId], or null when the stop is not ahead of it. */
    private fun etaAt(entry: TrackedVehicle, stopId: String): Float? {
        val stops = entry.pattern.stops
        for (index in (entry.progress + 1).coerceAtLeast(0) until stops.size) {
            if (stops[index].id == stopId) return entry.arrivalSeconds[index].coerceAtLeast(0f)
        }
        return null
    }

    /** Metres the rider has to walk to the stop from where they are now, or null without a fix. */
    private suspend fun walkToStop(alert: BusAlert): Float? {
        val location = currentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, WALK_FIX_MILLIS)
            ?: return walkFrom?.let { walkMetres }
        val previous = walkFrom
        if (previous != null && previous.distanceTo(location) < WALK_RECOMPUTE_METRES) return walkMetres
        val city = (application as MatoApplication).offlineCity
        walkMetres = city.shortWalkMetres(location.latitude, location.longitude, alert.latitude, alert.longitude)
            ?: metresBetween(location, alert.latitude, alert.longitude) * STREET_FACTOR
        walkFrom = location
        return walkMetres
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(priority: Int, maxAgeMillis: Long): Location? {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val cancellation = CancellationTokenSource()
                continuation.invokeOnCancellation { cancellation.cancel() }
                val request = CurrentLocationRequest.Builder()
                    .setMaxUpdateAgeMillis(maxAgeMillis)
                    .setDurationMillis(10_000)
                    .setPriority(priority)
                    .build()
                locationClient.getCurrentLocation(request, cancellation.token)
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) continuation.resume(location?.takeIf {
                            SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in
                                0..maxAgeMillis * 1_000_000L
                        })
                    }
                    .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
            }
        }
    }

    private fun metresBetween(location: Location, latitude: Double, longitude: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, latitude, longitude, result)
        return result[0]
    }

    private fun hasPassed(vehicleId: String): Boolean {
        val since = passedVehicles[vehicleId] ?: return false
        if (SystemClock.elapsedRealtime() - since <= PASSED_MEMORY_MILLIS) return true
        passedVehicles.remove(vehicleId)
        return false
    }

    private fun busStatus(alert: BusAlert, etaSeconds: Float) =
        if (etaSeconds < 60f) getString(R.string.status_bus_due, alert.stopName)
        else getString(R.string.status_bus_in, alert.stopName, ceilMinutes(etaSeconds))

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

    private fun notifyLeave(alert: BusAlert, etaSeconds: Float, walkMetres: Float) {
        val notification = NotificationCompat.Builder(this, LEAVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(getString(R.string.leave_now_title, alert.route))
            .setContentText(getString(R.string.leave_now_detail, alert.stopName,
                ceilMinutes(etaSeconds), ceilMinutes(DepartureTiming.walkSeconds(walkMetres))))
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Old news once the bus has come; don't leave it in the shade.
            .setTimeoutAfter((etaSeconds * 1000).toLong() + LEAVE_NOTICE_EXTRA_MILLIS)
            .build()
        getSystemService(NotificationManager::class.java).notify(LEAVE_ID, notification)
    }

    private fun notifyMissed(alert: BusAlert, next: DepartureTiming.Arrival?) {
        val notification = NotificationCompat.Builder(this, LEAVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(getString(R.string.missed_title, alert.route))
            .setContentText(next?.let {
                getString(R.string.missed_next, alert.stopName, ceilMinutes(it.etaSeconds))
            } ?: getString(R.string.missed_none))
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(MISSED_ID, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
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
                LEAVE_CHANNEL,
                getString(R.string.leave_channel),
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
        // The id of the old approach channel, so riders keep the sound they set for it.
        private const val LEAVE_CHANNEL = "bus_approach"
        private const val ONGOING_ID = 210
        private const val LEAVE_ID = 211
        private const val MISSED_ID = 213
        private const val POLL_MILLIS = 30_000L
        // Within this of setting off, or of the bus coming, look more often.
        private const val NEAR_POLL_MILLIS = 15_000L
        private const val NEAR_SECONDS = 180f
        // Half a quick poll: going off a little early beats going off a little late.
        private const val FIRE_EARLY_SECONDS = 10f
        // Only a bus this close to the stop that then has it behind it counts as having come.
        private const val PASS_WINDOW_SECONDS = 300f
        private const val LOST_POLLS_BEFORE_SWITCH = 3
        private const val PASSED_MEMORY_MILLIS = 20 * 60_000L
        private const val LEAVE_NOTICE_EXTRA_MILLIS = 2 * 60_000L
        private const val WALK_FIX_MILLIS = 60_000L
        private const val FRESH_FIX_MILLIS = 15_000L
        private const val WALK_RECOMPUTE_METRES = 25f
        private const val STREET_FACTOR = 1.3f
        const val ACTION_STOP = "it.mato.livebus.STOP_ALERT"
    }
}
