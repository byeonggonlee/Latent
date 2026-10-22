package com.byeonggon.latent.camera

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.IBinder
import com.byeonggon.latent.Notifications
import com.byeonggon.latent.R
import com.byeonggon.latent.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Keeps the process alive while a sync runs, so long video transfers survive leaving the app. */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = appContainer.syncEngine
        if (intent?.action == ACTION_CANCEL) {
            if (job?.isActive == true) engine.requestCancel() else stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            Notifications.ID_SYNC,
            notification(SyncState.Scanning(0)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive == true) return START_NOT_STICKY
        val folder = intent?.getParcelableExtra(EXTRA_FOLDER, Uri::class.java)
        val device = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        val source = when {
            folder != null -> FolderCameraSource(this, folder)
            device != null -> MtpCameraSource(getSystemService(UsbManager::class.java), device)
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val dates = intent?.syncDates()
        val compare = intent?.getBooleanExtra(EXTRA_COMPARE, false) == true
        lastStart = intent?.let { Intent(it).apply { removeExtra(EXTRA_COMPARE) } }

        job = scope.launch {
            val progress = launch {
                engine.state.collect { state ->
                    getSystemService(NotificationManager::class.java).notify(Notifications.ID_SYNC, notification(state))
                    delay(NOTIFICATION_INTERVAL_MS)
                }
            }
            try {
                engine.run(source, dates, compareLookAlikes = compare)
            } finally {
                progress.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // dataSync services are time-limited by the system; wind down instead of being killed.
    override fun onTimeout(startId: Int, fgsType: Int) {
        appContainer.syncEngine.requestCancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun Intent.syncDates(): SyncDates? {
        if (!hasExtra(EXTRA_START_DAY) || !hasExtra(EXTRA_END_DAY)) return null
        return SyncDates(
            start = LocalDate.ofEpochDay(getLongExtra(EXTRA_START_DAY, 0)),
            end = LocalDate.ofEpochDay(getLongExtra(EXTRA_END_DAY, 0)),
        )
    }

    private fun notification(state: SyncState): Notification {
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, SyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, Notifications.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_stat_latent)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentIntent(Notifications.openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
        if (state is SyncState.Transferring) {
            val percent = if (state.totalBytes > 0) (state.transferredBytes * 100 / state.totalBytes).toInt() else 0
            builder.setContentText("${state.index} / ${state.total} · ${state.fileName}")
            builder.setProgress(100, percent, false)
        } else {
            builder.setContentText(getString(R.string.sync_listing))
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        private const val ACTION_START = "com.byeonggon.latent.action.START_SYNC"
        private const val ACTION_CANCEL = "com.byeonggon.latent.action.CANCEL_SYNC"
        private const val EXTRA_START_DAY = "start_day"
        private const val EXTRA_END_DAY = "end_day"
        private const val EXTRA_FOLDER = "folder"
        private const val EXTRA_COMPARE = "compare"
        private const val NOTIFICATION_INTERVAL_MS = 500L

        /** How the last sync was started, for [compareLookAlikes] to start it again; gone with the process, as its result is. */
        private var lastStart: Intent? = null

        /**
         * Starts the last sync again, from the same camera or card and for the same dates, this time copying over and
         * comparing the files that look like photos in the library, which it left on the camera as too many to ask for.
         * Returns false when there is no last sync to start again.
         */
        fun compareLookAlikes(context: Context): Boolean {
            val intent = lastStart ?: return false
            context.startForegroundService(Intent(intent).putExtra(EXTRA_COMPARE, true))
            return true
        }

        fun start(context: Context, device: UsbDevice, dates: SyncDates?) =
            start(context, Intent(context, SyncService::class.java).putExtra(UsbManager.EXTRA_DEVICE, device), dates)

        /** Imports from a camera's card, or a camera read as a drive: [folder], picked in the system's folder dialog. */
        fun start(context: Context, folder: Uri, dates: SyncDates?) =
            start(context, Intent(context, SyncService::class.java).putExtra(EXTRA_FOLDER, folder), dates)

        private fun start(context: Context, intent: Intent, dates: SyncDates?) {
            intent.setAction(ACTION_START)
            if (dates != null) {
                intent.putExtra(EXTRA_START_DAY, dates.start.toEpochDay())
                intent.putExtra(EXTRA_END_DAY, dates.end.toEpochDay())
            }
            context.startForegroundService(intent)
        }
    }
}
