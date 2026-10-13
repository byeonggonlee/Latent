package com.byeonggon.cameramanager

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.byeonggon.cameramanager.ui.AppRoot
import com.byeonggon.cameramanager.ui.common.CameraManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launchedByCamera = intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        setContent {
            CameraManagerTheme {
                AppRoot(startOnSync = launchedByCamera)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        appContainer.usb.refresh()
    }

    override fun onResume() {
        super.onResume()
        appContainer.usb.refresh()
    }
}
