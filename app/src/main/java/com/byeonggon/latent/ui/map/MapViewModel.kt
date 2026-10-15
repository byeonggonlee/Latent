package com.byeonggon.latent.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.map.MapPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MapUiState(
    val onlineMaps: Boolean = false,
    /** Every photo and video that has a place. */
    val points: List<MapPoint> = emptyList(),
    /** How many do not. */
    val withoutPlace: Int = 0,
)

class MapViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val mediaDao = container.database.mediaDao()

    val state: StateFlow<MapUiState> = combine(
        container.settings.settings.map { it.onlineMaps },
        mediaDao.observeAll(),
    ) { online, items ->
        val located = items.filter { it.latitude != null && it.longitude != null }
        MapUiState(online, located.map { MapPoint(it.id, it.latitude!!, it.longitude!!) }, items.size - located.size)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    val tiles get() = container.mapTiles

    fun setOnlineMaps(on: Boolean) {
        viewModelScope.launch { container.settings.update { it.copy(onlineMaps = on) } }
    }

    /** The photos at a place on the map, newest first. */
    suspend fun itemsOf(ids: List<Long>): List<MediaItemEntity> = mediaDao.byIds(ids)
}
