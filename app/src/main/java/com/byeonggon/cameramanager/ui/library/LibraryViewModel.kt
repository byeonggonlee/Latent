package com.byeonggon.cameramanager.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.cameramanager.appContainer
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class LibraryFilter(val label: String, val kind: MediaKind?) {
    ALL("전체", null),
    PHOTO("사진", MediaKind.PHOTO),
    RAW("RAW", MediaKind.RAW),
    VIDEO("동영상", MediaKind.VIDEO),
}

data class LibraryDay(val date: LocalDate, val items: List<MediaItemEntity>)

data class LibraryUiState(
    val loading: Boolean = true,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val days: List<LibraryDay> = emptyList(),
    val libraryIsEmpty: Boolean = false,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val filter = MutableStateFlow(LibraryFilter.ALL)

    val state: StateFlow<LibraryUiState> =
        combine(container.database.mediaDao().observeAll(), filter) { items, filter ->
            val zone = ZoneId.systemDefault()
            val days = items
                .filter { filter.kind == null || it.kind == filter.kind }
                .groupBy { Instant.ofEpochMilli(it.capturedAt).atZone(zone).toLocalDate() }
                .map { (date, dayItems) -> LibraryDay(date, dayItems) }
            LibraryUiState(loading = false, filter, days, libraryIsEmpty = items.isEmpty())
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch { container.libraryIndex.forgetMissingFiles() }
    }

    fun setFilter(value: LibraryFilter) {
        filter.value = value
    }
}
