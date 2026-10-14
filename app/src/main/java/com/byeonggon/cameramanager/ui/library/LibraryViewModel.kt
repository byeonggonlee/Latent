package com.byeonggon.cameramanager.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.cameramanager.appContainer
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.enhance.PhotoEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
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

enum class LibraryFilter(val label: String, val kind: MediaKind?) {
    ALL("전체", null),
    PHOTO("사진", MediaKind.PHOTO),
    RAW("RAW", MediaKind.RAW),
    VIDEO("동영상", MediaKind.VIDEO),
}

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
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val mediaDao = container.database.mediaDao()
    private val edits = container.libraryEdits
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val leastRating = MutableStateFlow(0)
    private val stacked = MutableStateFlow(true)
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
    ) { items, filter, leastRating, stacked ->
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
            .groupBy { Instant.ofEpochMilli(it.capturedAt).atZone(zone).toLocalDate() }
            .map { (date, dayItems) -> LibraryDay(date, dayItems) }
        LibraryUiState(loading = false, filter, leastRating, stacked, partners, days, libraryIsEmpty = items.isEmpty())
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
                showingTrash = inTrash,
                trashCount = trashCount,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch {
            container.libraryIndex.forgetMissingFiles()
            trash.emptyExpired()
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
    fun trashSelected() = whileBusy("휴지통으로 옮기지 못했습니다.") {
        val current = state.value
        val chosen = selectedItems()
        val partners = if (current.stacked) mediaDao.byIds(chosen.mapNotNull { current.partners[it.id] }) else emptyList()
        trash.throwAway(chosen + partners.filter { partner -> chosen.none { it.id == partner.id } })
        "${chosen.size}장을 휴지통으로 옮겼습니다."
    }

    fun restoreSelected() = whileBusy("되돌리지 못했습니다.") {
        val chosen = selectedItems()
        trash.restore(chosen)
        "${chosen.size}장을 되돌렸습니다."
    }

    fun deleteSelectedForever() = whileBusy("삭제하지 못했습니다.") {
        val chosen = selectedItems()
        trash.deleteForever(chosen)
        "${chosen.size}장을 완전히 삭제했습니다."
    }

    fun emptyTrash() = whileBusy("휴지통을 비우지 못했습니다.") {
        val all = trash.items.first()
        trash.deleteForever(all)
        "${all.size}장을 완전히 삭제했습니다."
    }

    /** Runs [work] and reports what it says it did, or [failure]; the selection is cleared either way. */
    private fun whileBusy(failure: String, work: suspend () -> String) {
        viewModelScope.launch {
            val message = try {
                work()
            } catch (e: IOException) {
                failure
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
            activity.update { it.copy(selected = emptySet(), message = "${chosen.size}장에 별점을 매겼습니다.") }
        }
    }

    /** Gives every selected photo the tones and colours that were copied from another; each keeps its own framing. */
    fun pasteToSelected() {
        val copied = edits.copied.value ?: return
        runBatch("보정 값 붙여넣는 중", { it.kind != MediaKind.VIDEO }, done = "보정 값을 붙여넣었습니다") { item ->
            edits.apply(item, (edits.editOf(item) ?: PhotoEdit()).withLookOf(copied))
        }
    }

    /** Develops every selected raw file, with its edit, into a JPEG next to it. */
    fun exportSelected() {
        runBatch("JPEG로 내보내는 중", { it.kind == MediaKind.RAW }, done = "JPEG로 내보냈습니다") { item ->
            edits.exportRaw(item)
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
            label = "공유할 사진 준비 중",
            wanted = { it.kind != MediaKind.VIDEO },
            done = "공유할 사진을 준비했습니다",
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
        label: String,
        wanted: (MediaItemEntity) -> Boolean,
        done: String,
        whenDone: () -> Unit = {},
        work: suspend (MediaItemEntity) -> Unit,
    ) {
        if (batch?.isActive == true) return
        batch = viewModelScope.launch {
            val items = selectedItems().filter(wanted)
            if (items.isEmpty()) {
                activity.update { it.copy(message = "선택한 것 중에 해당하는 사진이 없습니다.") }
                return@launch
            }
            var finished = 0
            var failed = 0
            try {
                for (item in items) {
                    activity.update { it.copy(batch = BatchProgress(label, finished + failed, items.size)) }
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
                activity.update { it.copy(batch = null, message = "중단했습니다. ${finished}장은 끝났습니다.") }
                throw e
            }
            val outcome = "${finished}장에 $done." + if (failed > 0) " ${failed}장은 실패했습니다." else ""
            activity.update { it.copy(batch = null, selected = emptySet(), message = outcome) }
            whenDone()
        }
    }

    private companion object {
        /** Raw files shared several at a time are sent at a size that messengers and mail take without trouble. */
        const val RAW_SHARE_SIDE = 2048
    }
}
