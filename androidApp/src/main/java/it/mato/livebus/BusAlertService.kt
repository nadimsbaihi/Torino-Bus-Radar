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
    private val notifiedVehicles = mutableSetOf<String>()
    private val passedVehicles = mutableSetOf<String>()
    private var transferNotified = false

    override fun onCreate() {
        super.onCreate()
        transitIndex = TransitIndex.load(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alert = AlertPreferences.load(this)
        if (alert == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            ongoingNotification(alert),
            if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        pollingJob?.cancel()
        pollingJob = scope.launch { poll(alert) }
        return START_STICKY
    }

    private suspend fun poll(initialAlert: BusAlert) {
        var alert = initialAlert
        var targetPattern = transitIndex.patterns.firstOrNull { it.id == alert.patternId }
        var needsFullReplan = false
        while (scope.isActive) {
            try {
                checkTransferArrival(alert)
                val allVehicles = repository.vehicles()
                if (needsFullReplan) {
                    val replanned = replanCompleteJourney(alert, allVehicles)
                    if (replanned == null) {
                        delay(30_000)
                        continue
                    }
                    alert = replanned
                    targetPattern = transitIndex.patterns.firstOrNull {
                        it.id == alert.patternId
                    }
                    needsFullReplan = false
                }

                val currentTargetPattern = targetPattern
                val directionVehicles = allVehicles
                    .asSequence()
                    .filter { it.routeId == alert.routeId }
                    .mapNotNull { vehicle ->
                        val pattern = transitIndex.matchDirection(vehicle)
                            ?: return@mapNotNull null
                        if (currentTargetPattern != null &&
                            (pattern.directionId != currentTargetPattern.directionId ||
                                pattern.headsign != currentTargetPattern.headsign)
                        ) return@mapNotNull null
                        vehicle to pattern
                    }
                    .toList()

                val stopId = alert.stopId
                val selected = alert.vehicleId?.let { selectedId ->
                    directionVehicles.firstOrNull { (vehicle, _) -> vehicle.id == selectedId }
                }
                val selectedPassed = stopId != null && selected != null &&
                    !transitIndex.isBoardingStopAhead(
                        selected.first, selected.second, stopId
                    )
                if (selectedPassed) {
                    passedVehicles.add(selected.first.id)
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
                        getSystemService(NotificationManager::class.java)
                            .notify(ONGOING_ID, ongoingNotification(alert))
                        delay(30_000)
                        continue
                    }
                }

                val watchedVehicles = alert.vehicleId?.let { watchedId ->
                    directionVehicles.asSequence()
                        .map { it.first }
                        .filter { it.id == watchedId }
                } ?: directionVehicles.asSequence().map { it.first }
                watchedVehicles.forEach { vehicle ->
                        val distance = distanceToStop(vehicle, alert)
                        val previous = previousDistances.put(vehicle.id, distance)
                        val approaching = previous == null || distance < previous - 8
                        if (distance <= alert.thresholdMetres && approaching &&
                            notifiedVehicles.add(vehicle.id)
                        ) {
                            notifyApproaching(alert, distance)
                        }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Background bus alert refresh failed", error)
            }
            delay(30_000)
        }
    }

    private suspend fun replanCompleteJourney(
        alert: BusAlert,
        vehicles: List<LiveVehicle>
    ): BusAlert? {
        val finalLatitude = alert.finalLatitude ?: return null
        val finalLongitude = alert.finalLongitude ?: return null
        val location = lastKnownLocation() ?: return null
        val choice = transitIndex.planLiveJourney(
            location.latitude,
            location.longitude,
            finalLatitude,
            finalLongitude,
            vehicles.filterNot { it.id in passedVehicles }
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
            transferLongitude = choice.transferAt?.longitude
        )
        AlertPreferences.save(this, updated)
        previousDistances.clear()
        notifiedVehicles.clear()
        notifyJourneyReplanned(updated)
        getSystemService(NotificationManager::class.java)
            .notify(ONGOING_ID, ongoingNotification(updated))
        return updated
    }

    private suspend fun lastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return suspendCancellableCoroutine<Location?> { continuation ->
            locationClient.lastLocation
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }
    }

    private suspend fun checkTransferArrival(alert: BusAlert) {
        val transferLat = alert.transferLatitude ?: return
        val transferLon = alert.transferLongitude ?: return
        if (transferNotified ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val location = lastKnownLocation() ?: return
        val result = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude, transferLat, transferLon, result
        )
        if (result[0] <= TRANSFER_RADIUS_METRES) {
            transferNotified = true
            notifyReplan(alert)
        }
    }

    private fun distanceToStop(vehicle: LiveVehicle, alert: BusAlert): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            vehicle.latitude, vehicle.longitude,
            alert.latitude, alert.longitude, result
        )
        return result[0]
    }

    private fun ongoingNotification(alert: BusAlert) =
        NotificationCompat.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(getString(R.string.watching_bus, alert.route, alert.headsign))
            .setContentText(getString(R.string.watching_stop, alert.stopName))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .build()

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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
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
        const val ACTION_REPLAN = "it.mato.livebus.REPLAN"
        const val EXTRA_FINAL_ADDRESS = "final_address"
    }
}
