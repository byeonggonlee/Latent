package com.byeonggon.latent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    const val CHANNEL_LOCATION = "location_recording"
    const val CHANNEL_SYNC = "camera_sync"
    const val CHANNEL_LOCATION_STOPPED = "location_stopped"
    const val ID_LOCATION = 1
    const val ID_SYNC = 2
    const val ID_LOCATION_STOPPED = 3

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_LOCATION, context.getString(R.string.channel_location), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_SYNC, context.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_LOCATION_STOPPED, context.getString(R.string.channel_location_stopped), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
