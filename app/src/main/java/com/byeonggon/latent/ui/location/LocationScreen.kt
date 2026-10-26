package com.byeonggon.latent.ui.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byeonggon.latent.ui.common.ChoiceRow
import com.byeonggon.latent.ui.common.ReadableWidth
import com.byeonggon.latent.ui.common.SafeInsets
import com.byeonggon.latent.ui.common.SectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.R
import com.byeonggon.latent.ui.common.localFormat

private val INTERVAL_OPTIONS = listOf(1, 2, 5, 10, 15, 30)
private val MAX_GAP_OPTIONS = listOf(5, 15, 30, 60)

/**
 * Recording where the phone has been, and placing photos by it: a screen of its own, opened from the import tab,
 * where the locations are used, and from the settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(onBack: () -> Unit, viewModel: LocationViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickTimeline = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importTimeline(uri)
    }

    Scaffold(
        contentWindowInsets = SafeInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.location)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close)) } },
            )
        },
    ) { padding ->
        ReadableWidth(Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionCard(stringResource(R.string.channel_location)) {
                    RecordingRow(state, viewModel)

                    Text(stringResource(R.string.record_every), style = MaterialTheme.typography.labelLarge)
                    ChoiceRow(
                        options = INTERVAL_OPTIONS,
                        selected = state.settings.locationIntervalMinutes,
                        label = { stringResource(R.string.minutes, it) },
                        onSelect = { minutes -> viewModel.updateSettings { it.copy(locationIntervalMinutes = minutes) } },
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.high_accuracy))
                            Hint(stringResource(R.string.high_accuracy_hint))
                        }
                        Switch(
                            checked = state.settings.highAccuracyLocation,
                            onCheckedChange = { enabled ->
                                viewModel.updateSettings { it.copy(highAccuracyLocation = enabled) }
                            },
                        )
                    }
                    Hint(stringResource(R.string.recording_stopped_hint))
                }

                SectionCard(stringResource(R.string.import_timeline)) {
                    Hint(
                        stringResource(R.string.import_timeline_hint),
                    )
                    Button(
                        onClick = { pickTimeline.launch(arrayOf("application/json", "application/octet-stream", "text/*")) },
                        enabled = !state.timelineImport.running,
                    ) {
                        Text(stringResource(R.string.pick_timeline))
                    }
                    TaskStatus(state.timelineImport)
                }

                SectionCard(stringResource(R.string.tag_photos)) {
                    Text(pluralStringResource(R.plurals.untagged_count, state.untaggedCount, state.untaggedCount))
                    Text(stringResource(R.string.allowed_gap), style = MaterialTheme.typography.labelLarge)
                    ChoiceRow(
                        options = MAX_GAP_OPTIONS,
                        selected = state.settings.geotagMaxGapMinutes,
                        label = { stringResource(R.string.minutes, it) },
                        onSelect = { minutes -> viewModel.updateSettings { it.copy(geotagMaxGapMinutes = minutes) } },
                    )
                    Hint(
                        stringResource(R.string.tag_photos_hint),
                    )
                    ClockOffsetRow(
                        minutes = state.settings.cameraClockOffsetMinutes,
                        onChange = { minutes -> viewModel.updateSettings { it.copy(cameraClockOffsetMinutes = minutes) } },
                    )
                    Button(
                        onClick = viewModel::tagPending,
                        enabled = state.untaggedCount > 0 && !state.backfill.running,
                    ) {
                        Text(stringResource(R.string.tag_again))
                    }
                    TaskStatus(state.backfill)
                }
            }
        }
    }
}

/** Recording on or off, with when it last recorded; turning it on asks for location access first. */
@Composable
internal fun RecordingRow(state: LocationUiState, viewModel: LocationViewModel) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val startRecording = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = context.hasLocationPermission()
        permissionDenied = !granted
        if (granted) viewModel.startRecording()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(if (state.recording) R.string.recording_on else R.string.recording_off))
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
        Text(stringResource(R.string.no_location_permission), color = MaterialTheme.colorScheme.error)
    }
}

/** The import tab's card for recording: switched on or off there, before going out to shoot, and the rest of [LocationScreen] a tap away. */
@Composable
fun LocationCard(onOpen: () -> Unit, viewModel: LocationViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SectionCard(stringResource(R.string.channel_location)) {
        RecordingRow(state, viewModel)
        Hint(pluralStringResource(R.plurals.untagged_count, state.untaggedCount, state.untaggedCount))
        TextButton(onClick = onOpen) { Text(stringResource(R.string.location_more)) }
    }
}

/** How far the camera's clock is off, set in steps of a minute or an hour, for photos taken by it to be placed where they were really taken. */
@Composable
private fun ClockOffsetRow(minutes: Int, onChange: (Int) -> Unit) {
    Text(stringResource(R.string.camera_clock), style = MaterialTheme.typography.labelLarge)
    Text(
        when {
            minutes == 0 -> stringResource(R.string.camera_clock_same)
            minutes > 0 -> stringResource(R.string.camera_clock_ahead, offsetText(minutes))
            else -> stringResource(R.string.camera_clock_behind, offsetText(-minutes))
        },
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CLOCK_STEPS.forEach { step ->
            AssistChip(
                onClick = { onChange((minutes + step).coerceIn(-MAX_CLOCK_OFFSET, MAX_CLOCK_OFFSET)) },
                label = { Text((if (step > 0) "+" else "−") + offsetText(kotlin.math.abs(step))) },
            )
        }
    }
    if (minutes != 0) TextButton(onClick = { onChange(0) }) { Text(stringResource(R.string.camera_clock_reset)) }
}

/** A number of minutes as "5 min" or "2 h 30 min". */
@Composable
private fun offsetText(minutes: Int): String = when {
    minutes % 60 == 0 -> stringResource(R.string.hours, minutes / 60)
    minutes < 60 -> stringResource(R.string.minutes, minutes)
    else -> stringResource(R.string.hours, minutes / 60) + " " + stringResource(R.string.minutes, minutes % 60)
}

private val CLOCK_STEPS = listOf(-60, -10, -1, 1, 10, 60)
private const val MAX_CLOCK_OFFSET = 14 * 60

@Composable
private fun TaskStatus(task: TaskState) {
    if (task.running) LinearProgressIndicator(Modifier.fillMaxWidth())
    task.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun recordingSummary(state: LocationUiState): String {
    val lastFix = state.lastFixAt?.let {
        stringResource(R.string.last_fix, localFormat("MMMdjmm").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())))
    } ?: stringResource(R.string.no_fix_yet)
    return lastFix + " · " + pluralStringResource(R.plurals.stored_points, state.pointCount, state.pointCount)
}

private fun Context.hasLocationPermission(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
