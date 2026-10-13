package com.byeonggon.cameramanager.ui.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byeonggon.cameramanager.ui.common.ChoiceRow
import com.byeonggon.cameramanager.ui.common.SectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val INTERVAL_OPTIONS = listOf(1, 2, 5, 10, 15, 30)
private val MAX_GAP_OPTIONS = listOf(5, 15, 30, 60)
private val FIX_TIME_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

@Composable
fun LocationScreen(viewModel: LocationViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var locationGranted by remember { mutableStateOf(context.hasLocationPermission()) }
    var backgroundGranted by remember { mutableStateOf(context.hasBackgroundLocationPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        locationGranted = context.hasLocationPermission()
        backgroundGranted = context.hasBackgroundLocationPermission()
    }

    val startRecording = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationGranted = context.hasLocationPermission()
        permissionDenied = !locationGranted
        if (locationGranted) viewModel.startRecording()
    }
    val requestBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        backgroundGranted = it
    }
    val pickTimeline = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importTimeline(uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("위치", style = MaterialTheme.typography.headlineMedium)

        SectionCard("위치 기록") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.recording) "기록 중" else "꺼짐")
                    Hint(recordingSummary(state))
                }
                Switch(
                    checked = state.recording,
                    onCheckedChange = { enable ->
                        if (enable) {
                            startRecording.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ),
                            )
                        } else {
                            viewModel.stopRecording()
                        }
                    },
                )
            }
            if (permissionDenied) {
                Text("위치 권한이 없어 기록을 시작할 수 없습니다.", color = MaterialTheme.colorScheme.error)
            }

            Text("기록 주기", style = MaterialTheme.typography.labelLarge)
            ChoiceRow(
                options = INTERVAL_OPTIONS,
                selected = state.settings.locationIntervalMinutes,
                label = { "${it}분" },
                onSelect = { minutes -> viewModel.updateSettings { it.copy(locationIntervalMinutes = minutes) } },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("높은 정확도 (GPS)")
                    Hint("끄면 배터리를 덜 쓰지만 Wi-Fi·기지국 위주라 오차가 커집니다.")
                }
                Switch(
                    checked = state.settings.highAccuracyLocation,
                    onCheckedChange = { enabled ->
                        viewModel.updateSettings { it.copy(highAccuracyLocation = enabled) }
                    },
                )
            }

            if (locationGranted && !backgroundGranted) {
                Hint("위치 권한을 '항상 허용'으로 바꾸면 시스템이 앱을 재시작한 뒤에도 기록이 이어집니다.")
                OutlinedButton(
                    onClick = { requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                ) {
                    Text("항상 허용으로 변경")
                }
            }
        }

        SectionCard("구글 타임라인 가져오기") {
            Hint(
                "폰의 설정 → 위치 → 위치 서비스 → 타임라인 → '타임라인 데이터 내보내기'로 저장한 " +
                    "Timeline.json을 선택하세요. 앱 기록이 없는 기간을 채우는 데 사용됩니다.",
            )
            Button(
                onClick = { pickTimeline.launch(arrayOf("application/json", "application/octet-stream", "text/*")) },
                enabled = !state.timelineImport.running,
            ) {
                Text("Timeline.json 선택")
            }
            TaskStatus(state.timelineImport)
        }

        SectionCard("사진에 위치 삽입") {
            Text("위치 없는 항목 ${state.untaggedCount}개")
            Text("허용 시간 차", style = MaterialTheme.typography.labelLarge)
            ChoiceRow(
                options = MAX_GAP_OPTIONS,
                selected = state.settings.geotagMaxGapMinutes,
                label = { "${it}분" },
                onSelect = { minutes -> viewModel.updateSettings { it.copy(geotagMaxGapMinutes = minutes) } },
            )
            Hint(
                "촬영 시각과 이 시간 안에 있는 위치 기록만 사용합니다. 동기화할 때 자동으로 삽입되며, " +
                    "타임라인을 나중에 가져왔다면 아래 버튼으로 다시 맞출 수 있습니다.",
            )
            Button(
                onClick = viewModel::tagPending,
                enabled = state.untaggedCount > 0 && !state.backfill.running,
            ) {
                Text("위치 없는 항목 다시 맞추기")
            }
            TaskStatus(state.backfill)
        }
    }
}

@Composable
private fun TaskStatus(task: TaskState) {
    if (task.running) LinearProgressIndicator(Modifier.fillMaxWidth())
    task.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun recordingSummary(state: LocationUiState): String {
    val lastFix = state.lastFixAt?.let {
        "마지막 기록 " + FIX_TIME_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "아직 기록 없음"
    return "$lastFix · 저장된 위치 ${state.pointCount}개"
}

private fun Context.hasLocationPermission(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun Context.hasBackgroundLocationPermission(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
