package com.byeonggon.latent.data

import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.enhance.PhotoEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import android.content.Context
import com.byeonggon.latent.R

/**
 * Puts edits into effect in the background, so that the editor closes as soon as Done is tapped: a
 * full-size photo with many tools takes seconds to work out. Edits are saved one at a time, as each
 * needs whole frames in memory; the screens show which photos are still being saved, and which could
 * not be, until the person tries again or gives the edit up.
 */
class EditSaver(
    private val context: Context,
    private val edits: LibraryEdits,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _saving = MutableStateFlow<Set<Long>>(emptySet())

    /** The library entries whose edits are being saved or wait their turn. */
    val saving: StateFlow<Set<Long>> = _saving.asStateFlow()

    private val _failed = MutableStateFlow<Map<Long, SaveFailure>>(emptyMap())

    /**
     * The edits that could not be saved, by library entry. A failure stays until the edit is tried again or given
     * up: a message that goes by on its own would be missed on another screen, and the edit lost with it.
     */
    val failed: StateFlow<Map<Long, SaveFailure>> = _failed.asStateFlow()

    private val turn = Mutex()

    fun save(item: MediaItemEntity, edit: PhotoEdit) {
        // A newer edit of the photo takes the place of one that failed.
        _failed.update { it - item.id }
        _saving.update { it + item.id }
        scope.launch {
            try {
                turn.withLock { edits.apply(item, edit) }
            } catch (e: IOException) {
                failed(item, edit, context.getString(R.string.edit_not_saved, item.fileName))
            } catch (e: OutOfMemoryError) {
                failed(item, edit, context.getString(R.string.edit_not_saved_memory, item.fileName))
            } finally {
                _saving.update { it - item.id }
            }
        }
    }

    /** Saves again the edit that could not be saved for [id]. */
    fun retry(id: Long) {
        _failed.value[id]?.let { save(it.item, it.edit) }
    }

    /** Gives up the edit that could not be saved for [id]: the photo stays as it was saved last. */
    fun discard(id: Long) {
        _failed.update { it - id }
    }

    private fun failed(item: MediaItemEntity, edit: PhotoEdit, reason: String) {
        _failed.update { it + (item.id to SaveFailure(item, edit, reason)) }
    }
}

/** An edit that could not be saved, kept to be tried again: the photo it was for, the edit, and why, to show. */
data class SaveFailure(val item: MediaItemEntity, val edit: PhotoEdit, val reason: String)
