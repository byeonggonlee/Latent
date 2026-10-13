package com.byeonggon.cameramanager.ui.sync

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.cameramanager.appContainer
import com.byeonggon.cameramanager.camera.CameraConnection
import com.byeonggon.cameramanager.camera.SyncService
import com.byeonggon.cameramanager.camera.SyncState
import com.byeonggon.cameramanager.data.AppSettings
import com.byeonggon.cameramanager.data.db.MediaKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer

    val connection: StateFlow<CameraConnection> = container.usb.connection
    val syncState: StateFlow<SyncState> = container.syncEngine.state
    val settings: StateFlow<AppSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun requestPermission(device: UsbDevice) = container.usb.requestPermission(device)

    fun start(device: UsbDevice) = SyncService.start(getApplication(), device)

    fun cancel() = container.syncEngine.requestCancel()

    fun setKindEnabled(kind: MediaKind, enabled: Boolean) {
        viewModelScope.launch {
            container.settings.update {
                when (kind) {
                    MediaKind.PHOTO -> it.copy(syncPhotos = enabled)
                    MediaKind.RAW -> it.copy(syncRaw = enabled)
                    MediaKind.VIDEO -> it.copy(syncVideos = enabled)
                }
            }
        }
    }
}
