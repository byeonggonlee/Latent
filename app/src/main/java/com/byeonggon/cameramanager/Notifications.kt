package com.byeonggon.cameramanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    const val CHANNEL_LOCATION = "location_recording"
    const val CHANNEL_SYNC = "camera_sync"
    const val ID_LOCATION = 1
    const val ID_SYNC = 2

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_LOCATION, "위치 기록", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_SYNC, "카메라 동기화", NotificationManager.IMPORTANCE_LOW),
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
