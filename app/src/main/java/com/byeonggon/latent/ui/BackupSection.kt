package com.byeonggon.latent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byeonggon.latent.R
import com.byeonggon.latent.backup.BackupFormat
import com.byeonggon.latent.backup.BackupStep
import com.byeonggon.latent.backup.RestorePlan
import com.byeonggon.latent.ui.common.localFormat
import java.time.ZoneId

/** Types a backup file may be listed under by the app that keeps it. */
private val BACKUP_TYPES = arrayOf(BackupFormat.MIME_TYPE, "application/x-zip-compressed", "application/octet-stream")

/** Backing up into one file the person keeps where they like, and restoring from it; for the settings screen. */
@Composable
internal fun BackupSection(viewModel: BackupViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)) { uri -> uri?.let(viewModel::backUp) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::inspect) }
    val work = state.work

    Heading(R.string.settings_backup)
    Hint(R.string.backup_hint)
    Text(stringResource(R.string.backup_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = work == null, onClick = {
            viewModel.dismissMessage()
            create.launch(viewModel.suggestedName())
        }) { Text(stringResource(R.string.backup_create)) }
        TextButton(enabled = work == null, onClick = {
            viewModel.dismissMessage()
            open.launch(BACKUP_TYPES)
        }) { Text(stringResource(R.string.backup_restore_open)) }
    }
    if (work != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (work.restoring) R.string.backup_restore_running else R.string.backup_running), style = MaterialTheme.typography.bodyMedium)
                val step = stringResource(work.step.label)
                Hint(if (work.total > 0) stringResource(R.string.backup_step_of, step, number(work.done), number(work.total)) else step)
                if (work.total > 0) {
                    LinearProgressIndicator(progress = { (work.done.toFloat() / work.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.cancel)) }
        }
    }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    state.plan?.let { plan -> RestoreDialog(plan, onConfirm = viewModel::confirmRestore, onDismiss = viewModel::dismissPlan) }
}

/** What a restore of [plan] will do, to be confirmed. */
@Composable
private fun RestoreDialog(plan: RestorePlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val made = remember(plan) { localFormat("yMMMdjmm").format(plan.manifest.createdAt.atZone(ZoneId.systemDefault())) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_question)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_restore_made, made, plan.manifest.app.versionName))
                Text(stringResource(R.string.backup_restore_photos, number(plan.found), number(plan.photos)))
                missingText(context, plan.missing)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (plan.replaced > 0) Text(stringResource(R.string.backup_restore_replaced, number(plan.replaced)), color = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(
                        R.string.backup_restore_contents,
                        number(plan.albums), number(plan.versions), number(plan.filters), number(plan.luts), number(plan.masks),
                        number(plan.locations), number(plan.syncRecords),
                    ),
                )
                Text(stringResource(R.string.backup_restore_merge_note), style = MaterialTheme.typography.bodySmall)
                if (plan.onlineMapsInBackup) Text(stringResource(R.string.backup_restore_online_maps_ahead), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_restore_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val BackupStep.label: Int
    get() = when (this) {
        BackupStep.CHECKING -> R.string.backup_step_checking
        BackupStep.PHOTOS -> R.string.backup_step_photos
        BackupStep.ALBUMS -> R.string.backup_step_albums
        BackupStep.VERSIONS -> R.string.backup_step_versions
        BackupStep.FILTERS -> R.string.backup_step_filters
        BackupStep.LUTS -> R.string.backup_step_luts
        BackupStep.MASKS -> R.string.backup_step_masks
        BackupStep.CAMERA -> R.string.backup_step_camera
        BackupStep.TIMELINE -> R.string.backup_step_timeline
        BackupStep.SETTINGS -> R.string.backup_step_settings
    }
