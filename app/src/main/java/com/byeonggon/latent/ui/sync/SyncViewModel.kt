package com.byeonggon.latent.ui.sync

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.hardware.usb.UsbDevice
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.camera.CameraConnection
import com.byeonggon.latent.camera.SyncDates
import com.byeonggon.latent.camera.SyncService
import com.byeonggon.latent.camera.SyncState
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The folder a camera's card was last imported from; [name] is null while it cannot be read, the card being out. */
data class CardFolder(val uri: Uri, val name: String?)

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer

    val connection: StateFlow<CameraConnection> = container.usb.connection
    val syncState: StateFlow<SyncState> = container.syncEngine.state
    val settings: StateFlow<AppSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val deletedFromPhone: StateFlow<Int> =
        container.libraryIndex.deletedFromPhone.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Chosen per sync rather than saved: null imports every date.
    private val _dates = MutableStateFlow<SyncDates?>(null)
    val dates: StateFlow<SyncDates?> = _dates.asStateFlow()

    fun requestPermission(device: UsbDevice) = container.usb.requestPermission(device)

    fun setDates(value: SyncDates?) {
        _dates.value = value
    }

    fun start(device: UsbDevice) = SyncService.start(getApplication(), device, _dates.value)

    // Looked at again when the screen comes back: the card may have been put in or taken out meanwhile.
    private val cardChecks = MutableStateFlow(0)

    val cardFolder: StateFlow<CardFolder?> =
        combine(container.settings.settings.map { it.cardFolder }.distinctUntilChanged(), cardChecks) { folder, _ -> folder }
            .map { folder -> folder?.toUri()?.let { CardFolder(it, nameOf(it)) } }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun checkCardFolder() {
        cardChecks.value++
    }

    /**
     * Imports from [tree] from now on, keeping access to it. The access to a folder picked before is kept too: it
     * may be the library's own, which letting go would take from the library.
     */
    fun useCardFolder(tree: Uri) {
        getApplication<Application>().contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        viewModelScope.launch { container.settings.update { it.copy(cardFolder = tree.toString()) } }
    }

    fun startFromCard(folder: CardFolder) = SyncService.start(getApplication(), folder.uri, _dates.value)

    /** The name of the folder [tree] is, or null when it cannot be read now. */
    private fun nameOf(tree: Uri): String? = try {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        getApplication<Application>().contentResolver
            .query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        // A card taken out shows up as an exception, of a kind that depends on the provider.
        null
    }

    fun cancel() = container.syncEngine.requestCancel()

    /** Picks up files deleted with another app while this one was in the background. */
    fun refreshLibrary() {
        viewModelScope.launch { container.libraryIndex.forgetMissingFiles() }
    }

    fun allowReimportOfDeletedFiles() {
        if (syncState.value.isRunning) return
        viewModelScope.launch { container.libraryIndex.allowReimportOfDeletedFiles() }
    }

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
