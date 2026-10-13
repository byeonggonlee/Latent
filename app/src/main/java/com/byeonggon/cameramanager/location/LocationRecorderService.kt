package com.byeonggon.cameramanager.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.IBinder
import com.byeonggon.cameramanager.Notifications
import com.byeonggon.cameramanager.R
import com.byeonggon.cameramanager.appContainer
import com.byeonggon.cameramanager.data.db.LocationPointEntity
import com.byeonggon.cameramanager.data.db.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Records the phone's position at a fixed interval so photos from the camera can be geotagged later. */
class LocationRecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val listener = LocationListener(::onFix)
    private var settingsJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                Notifications.ID_LOCATION,
                notification(lastFixAt = null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: SecurityException) {
            // A restart from the background is only allowed with "allow all the time" location access.
            stopSelf()
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        }
        running.value = true
        if (settingsJob == null) {
            settingsJob = scope.launch {
                appContainer.settings.settings
                    .map { it.locationIntervalMinutes to it.highAccuracyLocation }
                    .distinctUntilChanged()
                    .collect { (minutes, highAccuracy) -> subscribe(minutes, highAccuracy) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        locationManager.removeUpdates(listener)
        scope.cancel()
        running.value = false
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(intervalMinutes: Int, highAccuracy: Boolean) {
        val intervalMs = intervalMinutes * 60_000L
        val quality = if (highAccuracy) {
            LocationRequest.QUALITY_HIGH_ACCURACY
        } else {
            LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(intervalMs)
            .setQuality(quality)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val provider = if (locationManager.hasProvider(LocationManager.FUSED_PROVIDER)) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }
        try {
            locationManager.removeUpdates(listener)
            locationManager.requestLocationUpdates(provider, request, mainExecutor, listener)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun onFix(location: Location) {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTED_ACCURACY_M) return
        val altitude = when {
            location.hasMslAltitude() -> location.mslAltitudeMeters
            location.hasAltitude() -> location.altitude
            else -> null
        }
        val point = LocationPointEntity(
            timestamp = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = altitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            source = LocationSource.APP,
        )
        scope.launch {
            appContainer.database.locationDao().insertAll(listOf(point))
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_LOCATION, notification(lastFixAt = location.time))
        }
    }

    private fun notification(lastFixAt: Long?): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, LocationRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (lastFixAt == null) {
            "위치를 기다리는 중"
        } else {
            "마지막 기록 " + TIME_FORMAT.format(Instant.ofEpochMilli(lastFixAt).atZone(ZoneId.systemDefault()))
        }
        return Notification.Builder(this, Notifications.CHANNEL_LOCATION)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle("위치 기록 중")
            .setContentText(text)
            .setContentIntent(Notifications.openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.byeonggon.cameramanager.action.STOP_RECORDING"

        // Cell-tower-only fixes can be kilometres off; a wrong location on a photo is worse than none.
        private const val MAX_ACCEPTED_ACCURACY_M = 200f
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LocationRecorderService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationRecorderService::class.java))
        }
    }
}
