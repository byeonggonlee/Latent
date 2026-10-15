package com.byeonggon.latent.ui.location

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.location.LocationRecorderService
import com.byeonggon.latent.location.TimelineImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.byeonggon.latent.R
import com.byeonggon.latent.ui.common.localFormat

/** Progress and outcome of a one-off action on the location screen. */
data class TaskState(val running: Boolean = false, val message: String? = null)

data class LocationUiState(
    val recording: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val pointCount: Int = 0,
    val lastFixAt: Long? = null,
    val untaggedCount: Int = 0,
    val timelineImport: TaskState = TaskState(),
    val backfill: TaskState = TaskState(),
)

class LocationViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val locationDao = container.database.locationDao()
    private val tasks = MutableStateFlow(TaskState() to TaskState())

    val state: StateFlow<LocationUiState> = combine(
        LocationRecorderService.isRunning,
        container.settings.settings,
        combine(locationDao.observeCount(), locationDao.observeLatestRecorded()) { count, latest ->
            count to latest?.timestamp
        },
        container.database.mediaDao().observeWithoutLocationCount(),
        tasks,
    ) { recording, settings, (pointCount, lastFixAt), untagged, (timelineImport, backfill) ->
        LocationUiState(recording, settings, pointCount, lastFixAt, untagged, timelineImport, backfill)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationUiState())

    fun startRecording() = LocationRecorderService.start(getApplication())

    fun stopRecording() = LocationRecorderService.stop(getApplication())

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    fun importTimeline(uri: Uri) {
        if (tasks.value.first.running) return
        viewModelScope.launch {
            tasks.update { it.copy(first = TaskState(running = true)) }
            val message = try {
                describe(container.timelineImporter.import(uri))
            } catch (e: IOException) {
                text(R.string.timeline_read_failed, e.message.orEmpty())
            } catch (e: IllegalStateException) {
                // Thrown by the JSON reader when the file is not shaped like a Timeline export.
                text(R.string.timeline_wrong_format)
            } catch (e: SecurityException) {
                text(R.string.timeline_no_access)
            }
            tasks.update { it.copy(first = TaskState(message = message)) }
        }
    }

    fun tagPending() {
        if (tasks.value.second.running) return
        viewModelScope.launch {
            tasks.update { it.copy(second = TaskState(running = true)) }
            val settings = container.settings.settings.first()
            val tagged = container.geotagger.tagPending(settings.geotagMaxGapMinutes * 60_000L, settings.cameraClockOffsetMinutes * 60_000L)
            val message = if (tagged > 0) plural(R.plurals.tagged_count, tagged) else text(R.string.tag_none_matched)
            tasks.update { it.copy(second = TaskState(message = message)) }
        }
    }

    private fun describe(result: TimelineImporter.Result): String {
        val from = result.from
        val to = result.to
        if (result.parsed == 0 || from == null || to == null) {
            return text(R.string.timeline_empty)
        }
        val zone = ZoneId.systemDefault()
        val format = localFormat("yMMMd")
        val range = format.format(Instant.ofEpochMilli(from).atZone(zone)) + " – " + format.format(Instant.ofEpochMilli(to).atZone(zone))
        return getApplication<Application>().resources.getQuantityString(R.plurals.timeline_added, result.added, result.added, result.parsed, range)
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun plural(@PluralsRes id: Int, count: Int): String =
        getApplication<Application>().resources.getQuantityString(id, count, count)
}
