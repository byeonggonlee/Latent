package com.byeonggon.latent.ui.sync

import android.content.Context
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.text.format.Formatter
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.R
import com.byeonggon.latent.ui.location.LocationCard
import com.byeonggon.latent.ui.common.localFormat

@Composable
fun SyncScreen(onOpenLocation: (() -> Unit)? = null, viewModel: SyncViewModel = viewModel()) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val dates by viewModel.dates.collectAsStateWithLifecycle()
    val deletedFromPhone by viewModel.deletedFromPhone.collectAsStateWithLifecycle()
    val cardFolder by viewModel.cardFolder.collectAsStateWithLifecycle()
    val storageGranted = rememberStorageAccess()
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    var confirmingReimport by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val pickCardFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) viewModel.useCardFolder(tree)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshLibrary()
        viewModel.checkCardFolder()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.tab_import), style = MaterialTheme.typography.headlineMedium)
        if (!storageGranted) StorageAccessCard()

        SectionCard(stringResource(R.string.camera)) {
            when (val current = connection) {
                CameraConnection.Disconnected -> {
                    Text(stringResource(R.string.no_camera))
                    Hint(stringResource(R.string.connect_camera_hint))
                }
                is CameraConnection.WrongMode -> {
                    Text(stringResource(R.string.mass_storage_connected, current.device.displayName()))
                    Hint(stringResource(R.string.mass_storage_hint))
                }
                is CameraConnection.NeedsPermission -> {
                    Text(stringResource(R.string.camera_connected, current.device.displayName()))
                    Button(onClick = { viewModel.requestPermission(current.device) }) { Text(stringResource(R.string.allow_usb)) }
                }
                is CameraConnection.Ready -> {
                    Text(stringResource(R.string.camera_connected, current.device.displayName()))
                    Button(
                        onClick = { viewModel.start(current.device) },
                        enabled = storageGranted && !syncState.isRunning,
                    ) {
                        Text(stringResource(R.string.start_sync))
                    }
                }
            }
        }

        SectionCard(stringResource(R.string.card_import)) {
            val folder = cardFolder
            Text(
                when {
                    folder == null -> stringResource(R.string.card_folder_none)
                    folder.name == null -> stringResource(R.string.card_folder_missing)
                    else -> stringResource(R.string.card_folder, folder.name)
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (folder?.name != null) {
                    Button(onClick = { viewModel.startFromCard(folder) }, enabled = storageGranted && !syncState.isRunning) {
                        Text(stringResource(R.string.import_from_card))
                    }
                }
                OutlinedButton(onClick = { pickCardFolder.launch(cardStart(context)) }, enabled = !syncState.isRunning) {
                    Text(stringResource(if (folder == null) R.string.choose_card_folder else R.string.choose_another_folder))
                }
            }
            Hint(stringResource(R.string.card_import_hint))
        }

        // Right under where an import is started, not below the settings: its progress, Cancel and result in view.
        SyncStatus(syncState, onCancel = viewModel::cancel, onCompare = viewModel::compareLookAlikes)

        SectionCard(stringResource(R.string.what_to_import)) {
            KindSwitch(stringResource(R.string.kind_photos_jpeg), settings.syncPhotos) { viewModel.setKindEnabled(MediaKind.PHOTO, it) }
            KindSwitch("RAW (ARW)", settings.syncRaw) { viewModel.setKindEnabled(MediaKind.RAW, it) }
            KindSwitch(stringResource(R.string.kind_videos), settings.syncVideos) { viewModel.setKindEnabled(MediaKind.VIDEO, it) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.shooting_dates))
                    Hint(dates?.label() ?: stringResource(R.string.all_dates))
                }
                if (dates != null) {
                    TextButton(onClick = { viewModel.setDates(null) }, enabled = !syncState.isRunning) {
                        Text(stringResource(R.string.all_dates_again))
                    }
                }
                OutlinedButton(onClick = { pickingDates = true }, enabled = !syncState.isRunning) {
                    Text(stringResource(R.string.pick_dates))
                }
            }
            Hint(stringResource(R.string.sync_skips_hint))
        }

        if (deletedFromPhone > 0) {
            SectionCard(stringResource(R.string.deleted_from_phone)) {
                Text(pluralStringResource(R.plurals.deleted_from_phone_detail, deletedFromPhone, deletedFromPhone))
                OutlinedButton(onClick = { confirmingReimport = true }, enabled = !syncState.isRunning) {
                    Text(stringResource(R.string.allow_reimport))
                }
            }
        }

        // Locations are put on photos as they come in: whether the phone is recording is checked here, before a shoot.
        onOpenLocation?.let { LocationCard(onOpen = it) }
    }

    if (confirmingReimport) {
        AlertDialog(
            onDismissRequest = { confirmingReimport = false },
            title = { Text(stringResource(R.string.forget_imports)) },
            text = {
                Text(
                    pluralStringResource(R.plurals.forget_imports_detail, deletedFromPhone, deletedFromPhone),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.allowReimportOfDeletedFiles()
                        confirmingReimport = false
                    },
                ) {
                    Text(stringResource(R.string.forget))
                }
            },
            dismissButton = { TextButton(onClick = { confirmingReimport = false }) { Text(stringResource(R.string.cancel)) } },
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
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DateRangePicker(
            state = state,
            title = { Text(stringResource(R.string.shooting_dates), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            showModeToggle = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SyncStatus(state: SyncState, onCancel: () -> Unit, onCompare: () -> Unit) {
    when (state) {
        SyncState.Idle -> Unit
        is SyncState.Scanning -> SectionCard(stringResource(R.string.progress)) {
            Text(pluralStringResource(R.plurals.reading_camera, state.found, state.found))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        is SyncState.Transferring -> SectionCard(stringResource(R.string.progress)) {
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
                Hint(stringResource(R.string.stops_after_file))
            } else {
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
        is SyncState.Finished -> SectionCard(stringResource(if (state.cancelled || state.outOfSpace) R.string.sync_stopped else R.string.sync_done)) {
            if (state.imported == 0 && state.failed.isEmpty()) {
                if (state.toCompare == null || state.cancelled) Text(stringResource(if (state.cancelled) R.string.nothing_imported else R.string.nothing_new))
            } else {
                Text(
                    stringResource(
                        R.string.imported_summary,
                        pluralStringResource(R.plurals.imported_files, state.imported, state.imported),
                        pluralStringResource(R.plurals.geotagged_files, state.geotagged, state.geotagged),
                    ),
                )
            }
            state.toCompare?.let { toCompare ->
                val context = LocalContext.current
                Text(
                    stringResource(
                        R.string.sync_look_alikes,
                        pluralStringResource(R.plurals.look_alike_files, toCompare.files, toCompare.files),
                        Formatter.formatShortFileSize(context, toCompare.bytes),
                    ),
                )
                Hint(stringResource(R.string.sync_look_alikes_hint))
                Button(onClick = onCompare) { Text(stringResource(R.string.sync_compare_look_alikes)) }
            }
            if (state.outOfSpace) {
                Text(stringResource(R.string.sync_out_of_space), color = MaterialTheme.colorScheme.error)
                Hint(stringResource(R.string.sync_out_of_space_hint))
            }
            if (state.failed.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.failed_files, state.failed.size, state.failed.size) + state.failed.take(5).joinToString() +
                        if (state.failed.size > 5) stringResource(R.string.and_more) else "",
                    color = MaterialTheme.colorScheme.error,
                )
                Hint(stringResource(R.string.failed_retry_hint))
            }
        }
        is SyncState.Failed -> SectionCard(stringResource(R.string.sync_failed)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Hint(stringResource(R.string.sync_failed_hint))
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

/** Where the folder dialog opens: the first card or drive in the phone, if there is one. */
private fun cardStart(context: Context): Uri? = context.getSystemService(StorageManager::class.java).storageVolumes
    .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
    ?.uuid
    ?.let { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "$it:") }

@Composable
private fun UsbDevice.displayName(): String = productName ?: stringResource(R.string.usb_camera)

private fun SyncDates.label(): String {
    val format = localFormat("yMMMd")
    return if (start == end) start.format(format) else start.format(format) + " – " + end.format(format)
}

// The date picker works in UTC midnights, whatever the device's time zone.
private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toPickerDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
