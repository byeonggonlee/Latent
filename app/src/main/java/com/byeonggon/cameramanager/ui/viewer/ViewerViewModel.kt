package com.byeonggon.cameramanager.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.cameramanager.appContainer
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.enhance.PhotoEnhancer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ViewerUiState(
    val item: MediaItemEntity? = null,
    val enhanced: Bitmap? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val mediaDao = container.database.mediaDao()
    private val itemId = MutableStateFlow<Long?>(null)
    private val edit = MutableStateFlow(ViewerUiState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ViewerUiState> = combine(
        itemId.flatMapLatest { id -> if (id == null) flowOf(null) else mediaDao.observeById(id) },
        edit,
    ) { item, edit -> edit.copy(item = item) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerUiState())

    fun open(id: Long) {
        if (itemId.value == id) return
        itemId.value = id
        edit.value = ViewerUiState()
    }

    fun toggleEnhance() {
        val item = state.value.item ?: return
        if (edit.value.busy) return
        if (edit.value.enhanced != null) {
            edit.update { it.copy(enhanced = null) }
            return
        }
        viewModelScope.launch {
            edit.update { it.copy(busy = true) }
            val preview = try {
                withContext(Dispatchers.Default) { PhotoEnhancer.preview(File(item.path), PREVIEW_EDGE) }
            } catch (e: IOException) {
                null
            }
            edit.update {
                it.copy(
                    busy = false,
                    enhanced = preview,
                    message = if (preview == null) "보정 미리보기를 만들지 못했습니다." else null,
                )
            }
        }
    }

    fun saveEnhanced() {
        val item = state.value.item ?: return
        if (edit.value.busy) return
        viewModelScope.launch {
            edit.update { it.copy(busy = true) }
            val message = try {
                val saved = withContext(Dispatchers.Default) { exportEnhanced(item) }
                "보정본을 저장했습니다: ${saved.name}"
            } catch (e: IOException) {
                "보정본을 저장하지 못했습니다."
            }
            edit.update { it.copy(busy = false, message = message) }
        }
    }

    fun consumeMessage() = edit.update { it.copy(message = null) }

    private suspend fun exportEnhanced(item: MediaItemEntity): File {
        val source = File(item.path)
        val target = container.storage.siblingFor(source, suffix = "_enhanced", extension = "jpg")
        PhotoEnhancer.export(source, target)
        container.storage.publish(target)
        mediaDao.insert(
            item.copy(
                id = 0,
                path = target.absolutePath,
                fileName = target.name,
                sizeBytes = target.length(),
                importedAt = System.currentTimeMillis(),
                sourceKey = null,
            ),
        )
        return target
    }

    private companion object {
        const val PREVIEW_EDGE = 2048
    }
}
