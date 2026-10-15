package com.byeonggon.latent.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.R
import com.byeonggon.latent.ui.common.NameDialog
import java.text.NumberFormat

/** All the albums: tap one to see it, make a new one, or delete one. */
@Composable
internal fun AlbumsDialog(state: LibraryUiState, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    val format = remember { NumberFormat.getIntegerInstance() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.albums_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AlbumRow(stringResource(R.string.album_all_photos), selected = state.album == null) {
                    viewModel.showAlbum(null)
                    onDismiss()
                }
                if (state.albums.isEmpty()) {
                    Text(stringResource(R.string.album_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
                state.albums.forEach { summary ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AlbumRow(
                            stringResource(R.string.album_count, summary.album.name, format.format(summary.count)),
                            selected = state.album?.album?.id == summary.album.id,
                            modifier = Modifier.weight(1f),
                        ) {
                            viewModel.showAlbum(summary.album.id)
                            onDismiss()
                        }
                        IconButton(onClick = { deleting = summary.album.id }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.album_delete))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { naming = true }) { Text(stringResource(R.string.album_new)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
    if (naming) {
        NameDialog(stringResource(R.string.album_new), onDismiss = { naming = false }) { viewModel.createAlbum(it, withSelected = false) }
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.album_delete_question)) },
            text = { Text(stringResource(R.string.album_delete_detail)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAlbum(id); deleting = null }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Which album the selected photos go into, or a new one. */
@Composable
internal fun AlbumPickDialog(state: LibraryUiState, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    val format = remember { NumberFormat.getIntegerInstance() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_choose)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.albums.forEach { summary ->
                    AlbumRow(stringResource(R.string.album_count, summary.album.name, format.format(summary.count)), selected = false) {
                        viewModel.addSelectedToAlbum(summary.album.id)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { naming = true }) { Text(stringResource(R.string.album_new)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (naming) {
        NameDialog(stringResource(R.string.album_new), onDismiss = { naming = false; onDismiss() }) { viewModel.createAlbum(it, withSelected = true) }
    }
}

@Composable
private fun AlbumRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}
