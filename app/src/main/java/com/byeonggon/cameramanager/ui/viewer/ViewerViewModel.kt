package com.byeonggon.cameramanager.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.cameramanager.appContainer
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.enhance.PhotoEnhancer
import com.byeonggon.cameramanager.location.GeoPoint
import com.byeonggon.cameramanager.raw.ArwImage
import com.byeonggon.cameramanager.raw.RawOptions
import com.byeonggon.cameramanager.raw.RawRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ViewerUiState(
    val item: MediaItemEntity? = null,
    /** Whether the photo was enhanced in place and its original can be brought back. */
    val edited: Boolean = false,
    /** Raw files only: show our own development instead of the preview the camera embedded. */
    val developed: Boolean = false,
    val autoEnhance: Boolean = false,
    val lensCorrection: Boolean = false,
    /** Shown in place of the stored picture while corrections are being tried out. */
    val rendered: Bitmap? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val mediaDao = container.database.mediaDao()
    private val storage = container.storage
    private val itemId = MutableStateFlow<Long?>(null)
    private val edit = MutableStateFlow(ViewerUiState())

    // Renderings work on whole frames; running one at a time keeps memory use in check.
    private val renderLock = Mutex()
    private var renderJob: Job? = null

    // Decoding a raw file takes a moment, so the result is kept for as long as its photo is open.
    private var decodedRaw: ArwImage? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ViewerUiState> = combine(
        itemId
            .flatMapLatest { id -> if (id == null) flowOf(null) else mediaDao.observeById(id) }
            .map { item -> item to (item != null && storage.originalOf(File(item.path)).exists()) }
            .flowOn(Dispatchers.IO),
        edit,
    ) { (item, edited), edit -> edit.copy(item = item, edited = edited) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerUiState())

    fun open(id: Long) {
        if (itemId.value == id) return
        renderJob?.cancel()
        decodedRaw = null
        itemId.value = id
        edit.value = ViewerUiState()
    }

    /**
     * Switches a raw file between the camera's embedded preview and our development of it. Lens
     * correction starts switched on, as the camera's preview has it too; that way this switch only
     * changes the rendering, and the lens switch only the geometry.
     */
    fun setDeveloped(enabled: Boolean) {
        edit.update { it.copy(developed = enabled, lensCorrection = enabled, autoEnhance = false) }
        render()
    }

    fun setAutoEnhance(enabled: Boolean) {
        edit.update { it.copy(autoEnhance = enabled) }
        render()
    }

    fun setLensCorrection(enabled: Boolean) {
        edit.update { it.copy(lensCorrection = enabled) }
        render()
    }

    /** Replaces the photo with its enhanced version, keeping the original for [restoreOriginal]. */
    fun applyToPhoto() {
        val item = state.value.item?.takeIf { it.kind == MediaKind.PHOTO } ?: return
        runTask(failure = "보정을 적용하지 못했습니다.") {
            val photo = File(item.path)
            PhotoEnhancer.enhanceInPlace(photo, storage.originalOf(photo))
            storage.publish(photo)
            mediaDao.setSize(item.id, photo.length())
            edit.update { it.copy(autoEnhance = false, rendered = null) }
            "보정을 적용했습니다. 원본은 보관되어 되돌릴 수 있습니다."
        }
    }

    fun restoreOriginal() {
        val item = state.value.item?.takeIf { it.kind == MediaKind.PHOTO } ?: return
        runTask(failure = "원본으로 되돌리지 못했습니다.") {
            val photo = File(item.path)
            PhotoEnhancer.restoreOriginal(photo, storage.originalOf(photo))
            storage.publish(photo)
            mediaDao.setSize(item.id, photo.length())
            "원본으로 되돌렸습니다."
        }
    }

    /** Develops the raw file at full resolution, the way it is shown, into a new JPEG. */
    fun exportRaw() {
        val item = state.value.item?.takeIf { it.kind == MediaKind.RAW && edit.value.developed } ?: return
        val options = edit.value.options()
        runTask(failure = "JPEG로 저장하지 못했습니다.") {
            // The export decodes the file itself; holding a second copy would only cost memory.
            decodedRaw = null
            val source = File(item.path)
            val target = storage.siblingFor(source, suffix = "_developed", extension = "jpg")
            RawRenderer.export(source, target, options)
            val latitude = item.latitude
            val longitude = item.longitude
            if (latitude != null && longitude != null) {
                container.geotagger.writeExif(target, GeoPoint(latitude, longitude, item.altitude))
            }
            storage.publish(target)
            mediaDao.insert(
                item.copy(
                    id = 0,
                    path = target.absolutePath,
                    fileName = target.name,
                    kind = MediaKind.PHOTO,
                    sizeBytes = target.length(),
                    importedAt = System.currentTimeMillis(),
                    sourceKey = null,
                ),
            )
            "JPEG로 저장했습니다: ${target.name}"
        }
    }

    fun consumeMessage() = edit.update { it.copy(message = null) }

    private fun render() {
        val item = state.value.item ?: return
        val current = edit.value
        val options = current.options()
        renderJob?.cancel()
        val wanted = if (item.kind == MediaKind.RAW) current.developed else current.autoEnhance
        if (!wanted) {
            edit.update { it.copy(rendered = null, busy = false) }
            return
        }
        renderJob = viewModelScope.launch {
            edit.update { it.copy(busy = true) }
            val bitmap = try {
                withContext(Dispatchers.Default) {
                    renderLock.withLock {
                        when (item.kind) {
                            MediaKind.PHOTO -> PhotoEnhancer.preview(File(item.path), PREVIEW_EDGE)
                            MediaKind.RAW -> RawRenderer.preview(decoded(item), options)
                            MediaKind.VIDEO -> null
                        }
                    }
                }
            } catch (e: IOException) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            edit.update {
                it.copy(busy = false, rendered = bitmap, message = if (bitmap == null) "미리보기를 만들지 못했습니다." else null)
            }
        }
    }

    private fun decoded(item: MediaItemEntity): ArwImage =
        decodedRaw ?: RawRenderer.read(File(item.path)).also { decodedRaw = it }

    /** Runs a slow file operation and reports its outcome, or [failure], as a message. */
    private fun runTask(failure: String, task: suspend () -> String) {
        if (edit.value.busy) return
        renderJob?.cancel()
        viewModelScope.launch {
            edit.update { it.copy(busy = true) }
            val message = try {
                withContext(Dispatchers.Default) { renderLock.withLock { task() } }
            } catch (e: IOException) {
                failure
            } catch (e: OutOfMemoryError) {
                "$failure 메모리가 부족합니다."
            }
            edit.update { it.copy(busy = false, message = message) }
        }
    }

    private fun ViewerUiState.options() = RawOptions(lensCorrection = lensCorrection, autoEnhance = autoEnhance)

    private companion object {
        const val PREVIEW_EDGE = 2048
    }
}
