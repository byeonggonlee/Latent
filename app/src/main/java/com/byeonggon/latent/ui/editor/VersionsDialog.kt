package com.byeonggon.latent.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.R
import com.byeonggon.latent.data.db.EditVersionEntity
import com.byeonggon.latent.ui.common.NameDialog

/** The versions of an edit that were kept: open one, keep the edit as it is now as another, or delete one. */
@Composable
internal fun VersionsDialog(
    versions: List<EditVersionEntity>,
    onKeep: (String) -> Unit,
    onOpen: (EditVersionEntity) -> Unit,
    onDelete: (EditVersionEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.versions_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.versions_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (versions.isEmpty()) {
                    Text(stringResource(R.string.version_none), modifier = Modifier.padding(vertical = 12.dp))
                }
                versions.forEach { version ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            version.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).clickable {
                                onOpen(version)
                                onDismiss()
                            }.padding(vertical = 12.dp),
                        )
                        IconButton(onClick = { onDelete(version) }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.version_delete))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { naming = true }) { Text(stringResource(R.string.version_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
    if (naming) {
        NameDialog(
            title = stringResource(R.string.version_save),
            initial = stringResource(R.string.version_default_name, versions.size + 1),
            onDismiss = { naming = false },
        ) {
            onKeep(it)
            onDismiss()
        }
    }
}
