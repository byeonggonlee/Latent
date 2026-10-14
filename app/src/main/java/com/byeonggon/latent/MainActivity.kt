package com.byeonggon.latent

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.byeonggon.latent.location.LocationRecorderService
import com.byeonggon.latent.ui.AppRoot
import com.byeonggon.latent.ui.common.LatentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launchedByCamera = intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        if (savedInstanceState == null) resumeRecordingIfAsked(intent)
        setContent {
            LatentTheme {
                AppRoot(startOnSync = launchedByCamera)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        appContainer.usb.refresh()
        resumeRecordingIfAsked(intent)
    }

    private fun resumeRecordingIfAsked(intent: Intent?) {
        if (intent?.action == LocationRecorderService.ACTION_RESUME) LocationRecorderService.resume(this)
    }

    override fun onResume() {
        super.onResume()
        appContainer.usb.refresh()
    }
}
