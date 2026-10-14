package com.byeonggon.cameramanager.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A restart of the phone or an update of the app ends location recording, and the app may not start it
 * again on its own; this tells the person, if they had left it on.
 */
class RecordingStoppedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LocationRecorderService.notifyStopped(context)
        }
    }
}
