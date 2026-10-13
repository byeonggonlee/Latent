package com.byeonggon.cameramanager.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface CameraConnection {
    data object Disconnected : CameraConnection

    /** A Sony device is attached as a plain USB drive, so the camera's USB mode needs changing. */
    data class WrongMode(val device: UsbDevice) : CameraConnection

    data class NeedsPermission(val device: UsbDevice) : CameraConnection

    data class Ready(val device: UsbDevice) : CameraConnection
}

/** Tracks whether an MTP camera is attached to the phone and whether the app may talk to it. */
class UsbCameraConnector(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val _connection = MutableStateFlow<CameraConnection>(CameraConnection.Disconnected)
    val connection: StateFlow<CameraConnection> = _connection.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        val devices = usbManager.deviceList.values
        val camera = devices.firstOrNull { it.hasInterface(UsbConstants.USB_CLASS_STILL_IMAGE) }
        val driveOnly = devices.firstOrNull {
            it.vendorId == SONY_VENDOR_ID && it.hasInterface(UsbConstants.USB_CLASS_MASS_STORAGE)
        }
        val next = when {
            camera != null && usbManager.hasPermission(camera) -> CameraConnection.Ready(camera)
            camera != null -> CameraConnection.NeedsPermission(camera)
            driveOnly != null -> CameraConnection.WrongMode(driveOnly)
            else -> CameraConnection.Disconnected
        }
        if (next != _connection.value) {
            val attached = devices.joinToString { it.describe() }.ifEmpty { "none" }
            Log.i(SYNC_LOG_TAG, "USB devices: $attached -> ${next::class.simpleName}")
        }
        _connection.value = next
    }

    fun requestPermission(device: UsbDevice) {
        // The system fills in the result extras, so the intent has to be mutable and therefore explicit.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    private fun UsbDevice.hasInterface(usbClass: Int) =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == usbClass }

    /** Vendor, product and the class/subclass/protocol of every interface, e.g. "054c:0d9f Camera [6/1/1]". */
    private fun UsbDevice.describe(): String {
        val interfaces = (0 until interfaceCount).joinToString(prefix = "[", postfix = "]") { index ->
            getInterface(index).run { "$interfaceClass/$interfaceSubclass/$interfaceProtocol" }
        }
        return "%04x:%04x %s %s".format(vendorId, productId, productName.orEmpty(), interfaces)
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.byeonggon.cameramanager.action.USB_PERMISSION"
        const val SONY_VENDOR_ID = 0x054C
    }
}
