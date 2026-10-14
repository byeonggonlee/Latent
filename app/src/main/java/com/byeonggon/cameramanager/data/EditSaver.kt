package com.byeonggon.cameramanager.data

import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.enhance.PhotoEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Puts edits into effect in the background, so that the editor closes as soon as Done is tapped: a
 * full-size photo with many tools takes seconds to work out. Edits are saved one at a time, as each
 * needs whole frames in memory; the screens show which photos are still being saved.
 */
class EditSaver(
    private val edits: LibraryEdits,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _saving = MutableStateFlow<Set<Long>>(emptySet())

    /** The library entries whose edits are being saved or wait their turn. */
    val saving: StateFlow<Set<Long>> = _saving.asStateFlow()

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = FAILURES_KEPT)

    /** What to tell the person when an edit could not be saved. */
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    private val turn = Mutex()

    fun save(item: MediaItemEntity, edit: PhotoEdit) {
        _saving.update { it + item.id }
        scope.launch {
            try {
                turn.withLock { edits.apply(item, edit) }
            } catch (e: IOException) {
                _failures.tryEmit("${item.fileName}을(를) 저장하지 못했습니다.")
            } catch (e: OutOfMemoryError) {
                _failures.tryEmit("${item.fileName}을(를) 저장하지 못했습니다. 메모리가 부족합니다.")
            } finally {
                _saving.update { it - item.id }
            }
        }
    }

    private companion object {
        const val FAILURES_KEPT = 8
    }
}
