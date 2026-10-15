package com.byeonggon.latent.ui.viewer

import android.app.Application
import android.content.IntentSender
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.enhance.PhotoEnhancer
import com.byeonggon.latent.raw.RawRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
import androidx.annotation.StringRes
import com.byeonggon.latent.R

data class ViewerUiState(
    val item: MediaItemEntity? = null,
    /** Whether the photo carries corrections that can be taken away again. */
    val edited: Boolean = false,
    /** Whether tones and colours copied from another photo are waiting to be given to this one. */
    val canPaste: Boolean = false,
    /** A file ready to be handed to another app, until the screen has done so. */
    val shareFile: File? = null,
    /** How the photo was taken, as far as the file says. */
    val details: String? = null,
    /** A raw file as its corrections make it look, shown in place of the preview the camera embedded. */
    val rendered: Bitmap? = null,
    /** Set while the person is to be asked by the system whether a photo of another app may be deleted. */
    val deleteRequest: IntentSender? = null,
    val busy: Boolean = false,
    /** Whether an edit of the photo is still being saved; until it is, the photo shows as it was. */
    val saving: Boolean = false,
    val message: String? = null,
)

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val mediaDao = container.database.mediaDao()
    private val storage = container.storage
    private val edits = container.libraryEdits
    private val itemId = MutableStateFlow<Long?>(null)
    private val status = MutableStateFlow(ViewerUiState())

    // Renderings work on whole frames; running one at a time keeps memory use in check.
    private val renderLock = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val item: StateFlow<MediaItemEntity?> = itemId
        .flatMapLatest { id -> if (id == null) flowOf(null) else mediaDao.observeById(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Read once for each file, not again whenever its library entry changes.
    private val details = item.map { it?.takeIf { item -> item.kind != MediaKind.VIDEO }?.path }
        .distinctUntilChanged()
        .map { path -> path?.let { PhotoDetails.of(File(it)) } }
        .flowOn(Dispatchers.IO)

    val state: StateFlow<ViewerUiState> = combine(
        item.map { item -> item to (item != null && edits.isEdited(item)) }.flowOn(Dispatchers.IO),
        status,
        combine(edits.copied, container.editSaver.saving) { copied, saving -> copied to saving },
        details,
    ) { (item, edited), status, (copied, saving), details ->
        val beingSaved = item != null && item.id in saving
        // Nothing else is done to a photo while its edit is being saved.
        status.copy(item = item, edited = edited, canPaste = copied != null, details = details, saving = beingSaved, busy = status.busy || beingSaved)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerUiState())

    init {
        viewModelScope.launch {
            container.editSaver.failures.collect { failure -> status.update { it.copy(message = failure) } }
        }
        viewModelScope.launch {
            item.map { it?.takeIf { item -> item.editsAsSettings && item.kind != MediaKind.VIDEO } }
                .distinctUntilChanged { old, new -> old?.path == new?.path && old?.edit == new?.edit }
                .collectLatest { stored -> showCorrected(stored) }
        }
    }

    fun open(id: Long) {
        if (itemId.value == id) return
        itemId.value = id
        status.value = ViewerUiState()
    }

    /** Lets go of the photo and everything kept in memory for it. */
    fun close() {
        itemId.value = null
        status.value = ViewerUiState()
        container.rawCache.clear()
    }

    /** Gives the photo [rating] stars; the number it already has takes them away again. */
    fun setRating(rating: Int) {
        val item = state.value.item ?: return
        viewModelScope.launch {
            // What the database has: a second tap may come before the screen shows the first.
            val current = mediaDao.byId(item.id)?.rating ?: return@launch
            mediaDao.setRating(item.id, if (rating == current) 0 else rating)
        }
    }

    /** Takes the corrections away again. A JPEG gets back the original that was kept when it was edited. */
    fun restoreOriginal() {
        val item = state.value.item ?: return
        runTask(failure = R.string.restore_original_failed) {
            edits.remove(item)
            text(R.string.restored_original)
        }
    }

    /** Gives this photo the tones and colours copied from another, keeping its own framing. */
    fun pasteEdit() {
        val item = state.value.item?.takeIf { it.kind != MediaKind.VIDEO } ?: return
        val copied = edits.copied.value ?: return
        runTask(failure = R.string.paste_failed) {
            val own = edits.editOf(item) ?: PhotoEdit()
            edits.apply(item, own.withLookOf(copied))
            text(R.string.pasted)
        }
    }

    /**
     * Develops the raw file at full resolution, with its corrections, into a new JPEG; a photo of
     * another app, which stays as it is, is saved as an edited copy in the library.
     */
    fun exportRaw() {
        val item = state.value.item?.takeIf { it.kind == MediaKind.RAW || it.origin == MediaOrigin.PHONE } ?: return
        runTask(failure = R.string.save_as_jpeg_failed) {
            text(R.string.saved_as_jpeg, (if (item.origin == MediaOrigin.PHONE) edits.saveCopy(item) else edits.exportRaw(item)).name)
        }
    }

    /**
     * Gets the photo ready to be sent to another app: as a JPEG no larger than [longSide] pixels, or
     * at its full size for null. A raw file is developed with its edit first.
     */
    fun share(longSide: Int?) {
        val item = state.value.item ?: return
        if (status.value.busy) return
        viewModelScope.launch {
            status.update { it.copy(busy = true) }
            val file = try {
                withContext(Dispatchers.Default) { edits.shareCopy(item, longSide) }
            } catch (e: IOException) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            status.update { it.copy(busy = false, shareFile = file, message = if (file == null) text(R.string.share_failed) else null) }
        }
    }

    /** Puts the photo in the trash, together with [alsoId], the other file of its pair if that goes along. */
    fun moveToTrash(alsoId: Long?) {
        val item = state.value.item ?: return
        if (item.origin == MediaOrigin.PHONE) {
            // Another app's photo: the system asks the person, and keeps it in its own trash.
            viewModelScope.launch {
                val uri = withContext(Dispatchers.IO) { container.phoneLibrary.uriOf(item.path) }
                val request = uri?.let { MediaStore.createTrashRequest(getApplication<Application>().contentResolver, listOf(it), true).intentSender }
                status.update { it.copy(deleteRequest = request, message = if (request == null) text(R.string.trash_failed) else null) }
            }
            return
        }
        runTask(failure = R.string.trash_failed) {
            val partner = alsoId?.let { mediaDao.byId(it) }
            container.trash.throwAway(listOfNotNull(item, partner))
            text(R.string.trashed)
        }
    }

    fun consumeShareFile() = status.update { it.copy(shareFile = null) }

    /** The system has dealt with the request to delete; the library drops the photo if it is gone. */
    fun deletionDone() {
        status.update { it.copy(deleteRequest = null) }
        viewModelScope.launch { container.refreshPhoneLibrary() }
    }

    fun consumeMessage() = status.update { it.copy(message = null) }

    /** Renders [raw], a photo whose corrections are stored as settings, with them; without any, the file itself is what there is to show. */
    private suspend fun showCorrected(raw: MediaItemEntity?) {
        val edit = PhotoEdit.fromJson(raw?.edit)
        if (raw == null || edit == null) {
            status.update { it.copy(rendered = null) }
            return
        }
        status.update { it.copy(busy = true) }
        val bitmap = try {
            withContext(Dispatchers.Default) {
                renderLock.withLock {
                    if (raw.kind == MediaKind.RAW) RawRenderer.preview(container.rawCache.read(File(raw.path)), edit) else PhotoEnhancer.preview(File(raw.path), edit, PREVIEW_EDGE)
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        status.update {
            it.copy(busy = false, rendered = bitmap, message = if (bitmap == null) text(R.string.render_failed) else null)
        }
    }

    /** Runs a slow file operation and reports its outcome, or [failure], as a message. */
    private fun runTask(@StringRes failure: Int, task: suspend () -> String) {
        if (status.value.busy) return
        viewModelScope.launch {
            status.update { it.copy(busy = true) }
            val message = try {
                withContext(Dispatchers.Default) { renderLock.withLock { task() } }
            } catch (e: IOException) {
                text(failure)
            } catch (e: OutOfMemoryError) {
                text(R.string.failed_memory, text(failure))
            }
            status.update { it.copy(busy = false, message = message) }
        }
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private companion object {
        /** A photo of another app is drawn this large on the screen: about as much as it shows at full width. */
        const val PREVIEW_EDGE = 2400
    }
}
