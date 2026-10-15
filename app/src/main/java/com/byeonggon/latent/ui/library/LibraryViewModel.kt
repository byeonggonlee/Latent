package com.byeonggon.latent.ui.library

import android.app.Application
import android.content.IntentSender
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.PhoneLibrary
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.PhotoEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.byeonggon.latent.R

/**
 * Raw files and JPEGs that the camera wrote of the same shot: in the same folder, under the same name.
 * Returns, for each file of such a pair, the other one's id.
 */
internal fun partnersOf(items: List<MediaItemEntity>): Map<Long, Long> {
    fun shotOf(item: MediaItemEntity) = File(item.path).let { File(it.parentFile, it.nameWithoutExtension.lowercase()).path }
    val jpegs = items.filter { it.kind == MediaKind.PHOTO }.associateBy(::shotOf)
    val partners = HashMap<Long, Long>()
    for (raw in items) {
        if (raw.kind != MediaKind.RAW) continue
        val jpeg = jpegs[shotOf(raw)] ?: continue
        partners[raw.id] = jpeg.id
        partners[jpeg.id] = raw.id
    }
    return partners
}

enum class LibraryFilter(@StringRes val label: Int, val kind: MediaKind?) {
    ALL(R.string.filter_all, null),
    PHOTO(R.string.filter_photos, MediaKind.PHOTO),
    RAW(R.string.filter_raw, MediaKind.RAW),
    VIDEO(R.string.filter_videos, MediaKind.VIDEO),
}

/** Whose photos are shown: those of the library's own folder, those listed from the phone, or both. */
enum class LibrarySource(@StringRes val label: Int) {
    ALL(R.string.source_all),
    LIBRARY(R.string.source_library),
    PHONE(R.string.source_phone),
}

/** The folders of the phone the person can choose from, and which are chosen. */
data class PhoneFolderChoice(val folders: List<PhoneLibrary.Folder>, val chosen: Set<String>)

data class LibraryDay(val date: LocalDate, val items: List<MediaItemEntity>)

/** Something being done to the selected photos one after the other, and how far it has got. */
data class BatchProgress(val label: String, val done: Int, val total: Int)

data class LibraryUiState(
    val loading: Boolean = true,
    val filter: LibraryFilter = LibraryFilter.ALL,
    /** Only photos with at least this many stars are shown. */
    val leastRating: Int = 0,
    /** A raw file taken together with a JPEG is shown as that JPEG only. */
    val stacked: Boolean = true,
    /** For each photo shot as raw and JPEG at once, the other file of the pair, both ways round. */
    val partners: Map<Long, Long> = emptyMap(),
    val days: List<LibraryDay> = emptyList(),
    val libraryIsEmpty: Boolean = false,
    /** The photos picked out to have the same thing done to all of them. */
    val selected: Set<Long> = emptySet(),
    /** Whether tones and colours copied from a photo are waiting to be given to others. */
    val canPaste: Boolean = false,
    val batch: BatchProgress? = null,
    val message: String? = null,
    /** Files ready to be handed to another app together, until the screen has done so. */
    val shareFiles: List<File> = emptyList(),
    /** Showing what is in the trash instead of the library. */
    val showingTrash: Boolean = false,
    val trashCount: Int = 0,
    val source: LibrarySource = LibrarySource.ALL,
    /** Set while the person is choosing which folders of the phone to list. */
    val phoneChoice: PhoneFolderChoice? = null,
    /** Photos of other apps picked out for deletion: the system asks the person, with this, before it does. */
    val deleteRequest: IntentSender? = null,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val mediaDao = container.database.mediaDao()
    private val edits = container.libraryEdits
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val leastRating = MutableStateFlow(0)
    private val stacked = MutableStateFlow(true)
    private val source = MutableStateFlow(LibrarySource.ALL)
    private val showingTrash = MutableStateFlow(false)
    private val trash = container.trash

    /** What is not read from the library itself: the selection, and what is being done with it. */
    private val activity = MutableStateFlow(LibraryUiState())
    private var batch: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val listed = combine(
        showingTrash.flatMapLatest { inTrash -> if (inTrash) trash.items else mediaDao.observeAll() },
        filter,
        leastRating,
        stacked,
        source,
    ) { items, filter, leastRating, stacked, source ->
        val zone = ZoneId.systemDefault()
        val partners = partnersOf(items)
        // Only the JPEG of a pair is listed, unless raw files are what is asked for.
        val hidden = if (stacked && filter != LibraryFilter.RAW) {
            items.filter { it.kind == MediaKind.RAW && it.id in partners }.mapTo(HashSet()) { it.id }
        } else {
            emptySet()
        }
        val days = items
            .filter { (filter.kind == null || it.kind == filter.kind) && it.rating >= leastRating && it.id !in hidden }
            .filter { source == LibrarySource.ALL || (source == LibrarySource.PHONE) == (it.origin == MediaOrigin.PHONE) }
            .groupBy { Instant.ofEpochMilli(it.capturedAt).atZone(zone).toLocalDate() }
            .map { (date, dayItems) -> LibraryDay(date, dayItems) }
        LibraryUiState(loading = false, filter, leastRating, stacked, partners, days, libraryIsEmpty = items.isEmpty(), source = source)
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<LibraryUiState> =
        combine(listed, activity, edits.copied, showingTrash, trash.items.map { it.size }) { listed, activity, copied, inTrash, trashCount ->
            // A photo that is no longer listed, deleted or filtered out, is no longer selected either.
            val shown = listed.days.flatMapTo(HashSet()) { day -> day.items.map { it.id } }
            listed.copy(
                selected = activity.selected.filterTo(LinkedHashSet()) { it in shown },
                canPaste = copied != null,
                batch = activity.batch,
                message = activity.message,
                shareFiles = activity.shareFiles,
                phoneChoice = activity.phoneChoice,
                deleteRequest = activity.deleteRequest,
                showingTrash = inTrash,
                trashCount = trashCount,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private var refreshing: Job? = null

    init {
        refresh()
    }

    /**
     * Brings the library in line with its folder: forgets what was deleted elsewhere, adds what was put
     * there, empties the trash of what is old enough, and makes the thumbnails. Done when the library
     * opens and when storage access is given.
     */
    fun refresh() {
        if (refreshing?.isActive == true) return
        refreshing = viewModelScope.launch {
            container.libraryIndex.forgetMissingFiles()
            // A file a sync is moving in is the sync's to add.
            val added = if (container.syncEngine.state.value.isRunning) 0 else container.libraryIndex.adoptUnknownFiles()
            if (added > 0) activity.update { it.copy(message = plural(R.plurals.library_adopted, added)) }
            container.refreshPhoneLibrary()
            trash.emptyExpired()
            makeThumbnails()
        }
    }

    /**
     * Makes the thumbnails the grid has not had yet, one at a time and out of the way, newest first
     * as the grid lists them; and drops those of photos that have changed or gone.
     */
    private suspend fun makeThumbnails() = withContext(Dispatchers.IO) {
        if (!container.storage.isAvailable()) return@withContext
        val thumbnails = container.thumbnails
        // Photos of other apps are many and come in their own time, as the grid asks for them; only the newest are made ahead.
        val (library, phone) = mediaDao.all().filter { it.kind != MediaKind.VIDEO }.partition { it.origin == MediaOrigin.LIBRARY }
        val photos = (library + phone.sortedByDescending { it.capturedAt }.take(PHONE_THUMBNAILS_AHEAD)).sortedByDescending { it.capturedAt }
        thumbnails.keepOnly((library + phone).map { thumbnails.fileFor(it.path, it.sizeBytes, it.edit.takeIf { _ -> it.origin == MediaOrigin.PHONE }) })
        for (photo in photos) {
            ensureActive()
            thumbnails.of(File(photo.path), photo.sizeBytes, photo.edit.takeIf { photo.origin == MediaOrigin.PHONE })
        }
    }

    /** Shows the trash instead of the library, or the library again. The selection does not carry over. */
    fun showTrash(shown: Boolean) {
        showingTrash.value = shown
        clearSelection()
    }

    /**
     * Puts the selected photos in the trash. A JPEG that stands for its raw file as well, in the
     * stacked library, takes the raw file along.
     */
    fun trashSelected() = whileBusy(R.string.trash_failed) {
        val current = state.value
        val (phone, own) = selectedItems().partition { it.origin == MediaOrigin.PHONE }
        val partners = if (current.stacked) mediaDao.byIds(own.mapNotNull { current.partners[it.id] }) else emptyList()
        trash.throwAway(own + partners.filter { partner -> own.none { it.id == partner.id } })
        // What other apps made is theirs to delete: the system asks the person, and keeps it in its own trash.
        if (phone.isNotEmpty()) requestDeletion(phone)
        plural(R.plurals.trashed_count, own.size + phone.size)
    }

    private suspend fun requestDeletion(items: List<MediaItemEntity>) {
        val uris = withContext(Dispatchers.IO) { items.mapNotNull { container.phoneLibrary.uriOf(it.path) } }
        if (uris.isEmpty()) return
        val request = MediaStore.createTrashRequest(getApplication<Application>().contentResolver, uris, true).intentSender
        activity.update { it.copy(deleteRequest = request) }
    }

    /** The system has dealt with the request to delete photos of other apps; the library drops those that are gone. */
    fun deletionDone() {
        activity.update { it.copy(deleteRequest = null) }
        viewModelScope.launch { container.refreshPhoneLibrary() }
    }

    fun setSource(value: LibrarySource) {
        source.value = value
    }

    /** Looks up the folders of the phone that hold photos, for the person to choose which to list. */
    fun choosePhoneFolders() {
        viewModelScope.launch {
            val folders = container.phoneLibrary.folders()
            val chosen = container.settings.settings.first().phoneFolders
            // Nothing is chosen at first: the phone's camera folder is the one most people mean.
            val start = chosen.ifEmpty { folders.map { it.path }.filter { it == PhoneLibrary.CAMERA_FOLDER }.toSet() }
            activity.update { it.copy(phoneChoice = PhoneFolderChoice(folders, start)) }
        }
    }

    fun togglePhoneFolder(path: String) = activity.update { current ->
        val choice = current.phoneChoice ?: return@update current
        current.copy(phoneChoice = choice.copy(chosen = if (path in choice.chosen) choice.chosen - path else choice.chosen + path))
    }

    fun dismissPhoneFolders() = activity.update { it.copy(phoneChoice = null) }

    /** Lists the photos of the chosen folders in the library, and leaves out those of the folders no longer chosen. */
    fun confirmPhoneFolders() {
        val chosen = activity.value.phoneChoice?.chosen ?: return
        activity.update { it.copy(phoneChoice = null) }
        viewModelScope.launch {
            container.settings.update { it.copy(phoneFolders = chosen) }
            val added = container.refreshPhoneLibrary()
            if (added > 0) activity.update { it.copy(message = plural(R.plurals.library_adopted, added)) }
            makeThumbnails()
        }
    }

    fun restoreSelected() = whileBusy(R.string.restore_failed) {
        val chosen = selectedItems()
        trash.restore(chosen)
        plural(R.plurals.restored_count, chosen.size)
    }

    fun deleteSelectedForever() = whileBusy(R.string.delete_failed) {
        val chosen = selectedItems()
        trash.deleteForever(chosen)
        plural(R.plurals.deleted_count, chosen.size)
    }

    fun emptyTrash() = whileBusy(R.string.empty_trash_failed) {
        val all = trash.items.first()
        trash.deleteForever(all)
        plural(R.plurals.deleted_count, all.size)
    }

    /** Runs [work] and reports what it says it did, or [failure]; the selection is cleared either way. */
    private fun whileBusy(@StringRes failure: Int, work: suspend () -> String) {
        viewModelScope.launch {
            val message = try {
                work()
            } catch (e: IOException) {
                text(failure)
            }
            activity.update { it.copy(selected = emptySet(), message = message) }
        }
    }

    fun setFilter(value: LibraryFilter) {
        filter.value = value
    }

    fun setLeastRating(value: Int) {
        leastRating.value = value
    }

    fun setStacked(value: Boolean) {
        stacked.value = value
    }

    /** Adds a photo to the selection, or takes it out again. */
    fun toggle(id: Long) = activity.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    fun clearSelection() = activity.update { it.copy(selected = emptySet()) }

    fun consumeMessage() = activity.update { it.copy(message = null) }

    /** Gives every selected photo [rating] stars. */
    fun rateSelected(rating: Int) {
        viewModelScope.launch {
            val chosen = selectedItems()
            chosen.forEach { mediaDao.setRating(it.id, rating) }
            activity.update { it.copy(selected = emptySet(), message = plural(R.plurals.rated_count, chosen.size)) }
        }
    }

    /** Gives every selected photo the tones and colours that were copied from another; each keeps its own framing. */
    fun pasteToSelected() {
        val copied = edits.copied.value ?: return
        runBatch(R.string.batch_pasting, { it.kind != MediaKind.VIDEO }, done = R.plurals.batch_pasted) { item ->
            edits.apply(item, (edits.editOf(item) ?: PhotoEdit()).withLookOf(copied))
        }
    }

    /** Develops every selected raw file, with its edit, into a JPEG next to it; edited photos of other apps are saved as copies. */
    fun exportSelected() {
        runBatch(
            R.string.batch_exporting,
            // A photo of another app is saved as a copy when it has an edit to put into one.
            { it.kind == MediaKind.RAW || (it.origin == MediaOrigin.PHONE && it.kind == MediaKind.PHOTO && edits.isEdited(it)) },
            done = R.plurals.batch_exported,
        ) { item ->
            if (item.origin == MediaOrigin.PHONE) edits.saveCopy(item) else edits.exportRaw(item)
        }
    }

    /**
     * Gets the selected photos ready to be sent to another app together: JPEGs as they are, raw files
     * developed with their edit, no larger than [RAW_SHARE_SIDE] pixels.
     */
    fun shareSelected() {
        val ready = ArrayList<File>()
        container.storage.clearShared()
        runBatch(
            label = R.string.batch_sharing,
            wanted = { it.kind != MediaKind.VIDEO },
            done = R.plurals.batch_shared,
            whenDone = { activity.update { it.copy(shareFiles = ready.toList(), message = null) } },
        ) { item ->
            ready += edits.shareCopy(item, if (item.kind == MediaKind.RAW) RAW_SHARE_SIDE else null, alongsideOthers = true)
        }
    }

    fun consumeShareFiles() = activity.update { it.copy(shareFiles = emptyList()) }

    /** Stops after the photo that is being worked on. */
    fun cancelBatch() {
        batch?.cancel()
    }

    /**
     * The selected photos as the library has them right now. What is on screen follows the selection
     * a moment later, so it is not what an action goes by.
     */
    private suspend fun selectedItems(): List<MediaItemEntity> = mediaDao.byIds(activity.value.selected.toList())

    /** Does [work] to those of the selected photos that are [wanted], one at a time, off the main thread, showing how far it has got. */
    private fun runBatch(
        @StringRes label: Int,
        wanted: (MediaItemEntity) -> Boolean,
        @PluralsRes done: Int,
        whenDone: () -> Unit = {},
        work: suspend (MediaItemEntity) -> Unit,
    ) {
        if (batch?.isActive == true) return
        batch = viewModelScope.launch {
            val items = selectedItems().filter(wanted)
            if (items.isEmpty()) {
                activity.update { it.copy(message = text(R.string.batch_nothing)) }
                return@launch
            }
            var finished = 0
            var failed = 0
            try {
                for (item in items) {
                    activity.update { it.copy(batch = BatchProgress(text(label), finished + failed, items.size)) }
                    try {
                        withContext(Dispatchers.Default) { work(item) }
                        finished++
                    } catch (e: IOException) {
                        failed++
                    } catch (e: OutOfMemoryError) {
                        failed++
                    }
                }
            } catch (e: CancellationException) {
                activity.update { it.copy(batch = null, message = plural(R.plurals.batch_stopped, finished)) }
                throw e
            }
            val outcome = plural(done, finished) + if (failed > 0) " " + plural(R.plurals.batch_failed, failed) else ""
            activity.update { it.copy(batch = null, selected = emptySet(), message = outcome) }
            whenDone()
        }
    }

    private fun text(@StringRes id: Int): String = getApplication<Application>().getString(id)

    private fun plural(@PluralsRes id: Int, count: Int): String =
        getApplication<Application>().resources.getQuantityString(id, count, count)

    private companion object {
        /** Raw files shared several at a time are sent at a size that messengers and mail take without trouble. */
        const val RAW_SHARE_SIDE = 2048

        /** How many of the phone's photos, the newest, get their thumbnails made before the grid asks. */
        const val PHONE_THUMBNAILS_AHEAD = 300
    }
}
