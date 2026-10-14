package com.byeonggon.latent.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.IBinder
import androidx.core.content.edit
import com.byeonggon.latent.MainActivity
import com.byeonggon.latent.Notifications
import com.byeonggon.latent.R
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
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
            setWanted(this, false)
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
            // The system restarting the service after killing the app is not allowed to use the location
            // while the app is out of sight; the person has to start it again.
            stopSelf()
            notifyStopped(this)
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            stopSelf()
            notifyStopped(this)
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).cancel(Notifications.ID_LOCATION_STOPPED)
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
            getString(R.string.location_waiting)
        } else {
            getString(R.string.location_last_fix, TIME_FORMAT.format(Instant.ofEpochMilli(lastFixAt).atZone(ZoneId.systemDefault())))
        }
        return Notification.Builder(this, Notifications.CHANNEL_LOCATION)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(getString(R.string.location_recording))
            .setContentText(text)
            .setContentIntent(Notifications.openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.byeonggon.latent.action.STOP_RECORDING"

        // Cell-tower-only fixes can be kilometres off; a wrong location on a photo is worse than none.
        private const val MAX_ACCEPTED_ACCURACY_M = 200f
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        const val ACTION_RESUME = "com.byeonggon.latent.action.RESUME_RECORDING"
        private const val PREFERENCES = "location_recording"
        private const val WANTED = "wanted"

        fun start(context: Context) {
            setWanted(context, true)
            context.startForegroundService(Intent(context, LocationRecorderService::class.java))
        }

        fun stop(context: Context) {
            setWanted(context, false)
            context.stopService(Intent(context, LocationRecorderService::class.java))
        }

        /** Whether the person left recording switched on, even if the service is not running now. */
        fun isWanted(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(WANTED, false)

        private fun setWanted(context: Context, wanted: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean(WANTED, wanted) }
        }

        /**
         * Tells the person that recording stopped although they left it on, for when the system ended it:
         * starting it again needs them, since the app may only use the location while they are using it.
         */
        fun notifyStopped(context: Context) {
            if (!isWanted(context) || isRunning.value) return
            val resume = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_RESUME)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, Notifications.CHANNEL_LOCATION_STOPPED)
                .setSmallIcon(R.drawable.ic_stat_camera)
                .setContentTitle(context.getString(R.string.location_stopped_title))
                .setContentText(context.getString(R.string.location_stopped_text))
                .setContentIntent(resume)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(Notifications.ID_LOCATION_STOPPED, notification)
        }

        /** Starts recording again from the "stopped" notification, if location access is still there. */
        fun resume(context: Context) {
            val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (allowed && isWanted(context)) start(context)
        }
    }
}
