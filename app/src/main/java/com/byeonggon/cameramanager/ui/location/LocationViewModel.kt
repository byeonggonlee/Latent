package com.byeonggon.cameramanager.ui.location

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.cameramanager.appContainer
import com.byeonggon.cameramanager.data.AppSettings
import com.byeonggon.cameramanager.location.LocationRecorderService
import com.byeonggon.cameramanager.location.TimelineImporter
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
import java.time.format.DateTimeFormatter

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
                "파일을 읽지 못했습니다: ${e.message}"
            } catch (e: IllegalStateException) {
                // Thrown by the JSON reader when the file is not shaped like a Timeline export.
                "타임라인 내보내기 파일 형식이 아닙니다."
            } catch (e: SecurityException) {
                "파일에 접근할 수 없습니다."
            }
            tasks.update { it.copy(first = TaskState(message = message)) }
        }
    }

    fun tagPending() {
        if (tasks.value.second.running) return
        viewModelScope.launch {
            tasks.update { it.copy(second = TaskState(running = true)) }
            val maxGapMs = container.settings.settings.first().geotagMaxGapMinutes * 60_000L
            val tagged = container.geotagger.tagPending(maxGapMs)
            val message = if (tagged > 0) "${tagged}개 항목에 위치를 삽입했습니다." else "맞는 위치 기록을 찾지 못했습니다."
            tasks.update { it.copy(second = TaskState(message = message)) }
        }
    }

    private fun describe(result: TimelineImporter.Result): String {
        val from = result.from
        val to = result.to
        if (result.parsed == 0 || from == null || to == null) {
            return "위치 기록을 찾지 못했습니다. 기기에서 내보낸 Timeline.json인지 확인해주세요."
        }
        val zone = ZoneId.systemDefault()
        val range = DATE_FORMAT.format(Instant.ofEpochMilli(from).atZone(zone)) + " ~ " +
            DATE_FORMAT.format(Instant.ofEpochMilli(to).atZone(zone))
        return "위치 ${result.parsed}개 중 ${result.added}개를 새로 추가했습니다. ($range)"
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
