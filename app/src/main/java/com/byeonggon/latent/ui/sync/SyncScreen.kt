package com.byeonggon.latent.ui.sync

import android.hardware.usb.UsbDevice
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byeonggon.latent.camera.CameraConnection
import com.byeonggon.latent.camera.SyncDates
import com.byeonggon.latent.camera.SyncState
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.ui.common.SectionCard
import com.byeonggon.latent.ui.common.StorageAccessCard
import com.byeonggon.latent.ui.common.rememberStorageAccess
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun SyncScreen(viewModel: SyncViewModel = viewModel()) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val dates by viewModel.dates.collectAsStateWithLifecycle()
    val deletedFromPhone by viewModel.deletedFromPhone.collectAsStateWithLifecycle()
    val storageGranted = rememberStorageAccess()
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    var confirmingReimport by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshLibrary() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("카메라 동기화", style = MaterialTheme.typography.headlineMedium)
        if (!storageGranted) StorageAccessCard()

        SectionCard("카메라") {
            when (val current = connection) {
                CameraConnection.Disconnected -> {
                    Text("연결된 카메라가 없습니다.")
                    Hint("USB-C 케이블로 카메라를 연결하고, 카메라 MENU → 설정 → USB 연결을 'MTP'로 맞춰주세요.")
                }
                is CameraConnection.WrongMode -> {
                    Text("${current.device.displayName()}이(가) 저장 장치(Mass Storage) 모드로 연결됐습니다.")
                    Hint("카메라의 USB 연결 설정을 'MTP'로 바꾼 뒤 케이블을 다시 연결해주세요.")
                }
                is CameraConnection.NeedsPermission -> {
                    Text("${current.device.displayName()} 연결됨")
                    Button(onClick = { viewModel.requestPermission(current.device) }) { Text("USB 접근 허용") }
                }
                is CameraConnection.Ready -> {
                    Text("${current.device.displayName()} 연결됨")
                    Button(
                        onClick = { viewModel.start(current.device) },
                        enabled = storageGranted && !syncState.isRunning,
                    ) {
                        Text("동기화 시작")
                    }
                }
            }
        }

        SectionCard("가져올 항목") {
            KindSwitch("사진 (JPEG)", settings.syncPhotos) { viewModel.setKindEnabled(MediaKind.PHOTO, it) }
            KindSwitch("RAW (ARW)", settings.syncRaw) { viewModel.setKindEnabled(MediaKind.RAW, it) }
            KindSwitch("동영상", settings.syncVideos) { viewModel.setKindEnabled(MediaKind.VIDEO, it) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("촬영 기간")
                    Hint(dates?.label() ?: "전체")
                }
                if (dates != null) {
                    TextButton(onClick = { viewModel.setDates(null) }, enabled = !syncState.isRunning) {
                        Text("전체로")
                    }
                }
                OutlinedButton(onClick = { pickingDates = true }, enabled = !syncState.isRunning) {
                    Text("기간 선택")
                }
            }
            Hint("이미 가져온 파일은 건너뜁니다. 카메라의 파일은 삭제하지 않습니다.")
        }

        if (deletedFromPhone > 0) {
            SectionCard("폰에서 지운 파일") {
                Text("가져온 뒤 폰에서 지운 파일 ${deletedFromPhone}개는 동기화할 때 건너뜁니다.")
                OutlinedButton(onClick = { confirmingReimport = true }, enabled = !syncState.isRunning) {
                    Text("다시 가져올 수 있게 하기")
                }
            }
        }

        SyncStatus(syncState, onCancel = viewModel::cancel)
    }

    if (confirmingReimport) {
        AlertDialog(
            onDismissRequest = { confirmingReimport = false },
            title = { Text("가져온 기록 지우기") },
            text = {
                Text(
                    "폰에서 지운 파일 ${deletedFromPhone}개의 가져온 기록을 지웁니다. 다음 동기화부터 다시 가져올 수 있고, " +
                        "보관함에 남아 있는 파일은 영향을 받지 않습니다.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.allowReimportOfDeletedFiles()
                        confirmingReimport = false
                    },
                ) {
                    Text("지우기")
                }
            },
            dismissButton = { TextButton(onClick = { confirmingReimport = false }) { Text("취소") } },
        )
    }

    if (pickingDates) {
        SyncDatesDialog(
            initial = dates,
            onConfirm = {
                viewModel.setDates(it)
                pickingDates = false
            },
            onDismiss = { pickingDates = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncDatesDialog(initial: SyncDates?, onConfirm: (SyncDates) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.toPickerMillis(),
        initialSelectedEndDateMillis = initial?.end?.toPickerMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis?.toPickerDate() ?: return@TextButton
                    // Tapping a single day leaves the end of the range open.
                    onConfirm(SyncDates(start, state.selectedEndDateMillis?.toPickerDate() ?: start))
                },
            ) {
                Text("적용")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) {
        DateRangePicker(
            state = state,
            title = { Text("촬영 기간", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            showModeToggle = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SyncStatus(state: SyncState, onCancel: () -> Unit) {
    when (state) {
        SyncState.Idle -> Unit
        is SyncState.Scanning -> SectionCard("진행 상황") {
            Text("카메라의 파일 목록을 읽는 중… (${state.found}개)")
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text("취소") }
        }
        is SyncState.Transferring -> SectionCard("진행 상황") {
            val context = LocalContext.current
            Text("${state.index} / ${state.total} · ${state.fileName}")
            LinearProgressIndicator(
                progress = {
                    if (state.totalBytes > 0) state.transferredBytes.toFloat() / state.totalBytes else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                Formatter.formatShortFileSize(context, state.transferredBytes) + " / " +
                    Formatter.formatShortFileSize(context, state.totalBytes),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.cancelling) {
                Hint("현재 파일 전송이 끝나면 중지합니다.")
            } else {
                OutlinedButton(onClick = onCancel) { Text("취소") }
            }
        }
        is SyncState.Finished -> SectionCard(if (state.cancelled) "동기화 중지됨" else "동기화 완료") {
            if (state.imported == 0 && state.failed.isEmpty()) {
                Text(if (state.cancelled) "가져온 파일이 없습니다." else "새로 가져올 파일이 없습니다.")
            } else {
                Text("가져온 파일 ${state.imported}개 · 위치 삽입 ${state.geotagged}개")
            }
            if (state.failed.isNotEmpty()) {
                Text(
                    "실패 ${state.failed.size}개: " + state.failed.take(5).joinToString() +
                        if (state.failed.size > 5) " 외" else "",
                    color = MaterialTheme.colorScheme.error,
                )
                Hint("실패한 파일은 다음 동기화 때 다시 시도합니다.")
            }
        }
        is SyncState.Failed -> SectionCard("동기화 실패") {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Hint("케이블 연결과 카메라의 USB 연결 설정(MTP)을 확인해주세요.")
        }
    }
}

@Composable
private fun KindSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun UsbDevice.displayName(): String = productName ?: "USB 카메라"

private fun SyncDates.label(): String = if (start == end) "$start" else "$start ~ $end"

// The date picker works in UTC midnights, whatever the device's time zone.
private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toPickerDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
