package com.byeonggon.latent.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byeonggon.latent.R
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.GridSize
import com.byeonggon.latent.data.TabPosition
import com.byeonggon.latent.data.ThemeMode
import com.byeonggon.latent.ui.common.SafeInsets
import com.byeonggon.latent.ui.library.AboutDialog
import kotlinx.coroutines.launch

/** What is set once and then left alone: where the tabs are, light or dark, the size of the grid, the language, how long the trash keeps photos, the internet for maps, which phone folders to list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onChoosePhoneFolders: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings = context.appContainer.settings
    val current by settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    var askingMaps by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    if (about) AboutDialog(onDismiss = { about = false })
    if (askingMaps) {
        AlertDialog(
            onDismissRequest = { askingMaps = false },
            title = { Text(stringResource(R.string.map_ask_title)) },
            text = { Text(stringResource(R.string.map_ask_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askingMaps = false
                    scope.launch { settings.update { it.copy(onlineMaps = true) } }
                }) { Text(stringResource(R.string.map_ask_yes)) }
            },
            dismissButton = { TextButton(onClick = { askingMaps = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Scaffold(
        contentWindowInsets = SafeInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading(R.string.settings_screen)
            Text(stringResource(R.string.tab_position), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabPosition.entries.forEach { position ->
                    FilterChip(
                        selected = current.tabPosition == position,
                        onClick = { scope.launch { settings.update { it.copy(tabPosition = position) } } },
                        label = { Text(stringResource(position.label)) },
                    )
                }
            }
            Hint(R.string.tab_position_hint)
            Choice(R.string.theme, ThemeMode.entries, current.theme, { it.label }) { mode -> scope.launch { settings.update { it.copy(theme = mode) } } }
            Choice(R.string.grid_size, GridSize.entries, current.gridSize, { it.label }) { size -> scope.launch { settings.update { it.copy(gridSize = size) } } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    // The phone's own list of languages for this app.
                    try {
                        context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${context.packageName}".toUri()))
                    } catch (e: ActivityNotFoundException) {
                        // Nowhere to send the person; nothing useful to do.
                    }
                }) { Text(stringResource(R.string.language_change)) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Heading(R.string.settings_library)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.phone_folders), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onChoosePhoneFolders) { Text(stringResource(R.string.choose_folder)) }
            }
            Text(stringResource(R.string.trash_kept), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TRASH_DAYS.forEach { days ->
                    FilterChip(
                        selected = current.trashKeptDays == days,
                        onClick = { scope.launch { settings.update { it.copy(trashKeptDays = days) } } },
                        label = { Text(pluralStringResource(R.plurals.days_count, days, days)) },
                    )
                }
            }
            Hint(R.string.trash_kept_hint)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Heading(R.string.settings_internet)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.online_maps), style = MaterialTheme.typography.bodyLarge)
                    Hint(R.string.online_maps_hint)
                }
                Switch(
                    checked = current.onlineMaps,
                    // Turning it on is asked about first; turning it off is not.
                    onCheckedChange = { on -> if (on) askingMaps = true else scope.launch { settings.update { it.copy(onlineMaps = false) } } },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { about = true }) { Text(stringResource(R.string.about)) }
        }
    }
}

/** How long the trash can keep photos. */
private val TRASH_DAYS = listOf(7, 30, 90)

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

private val GridSize.label: Int
    get() = when (this) {
        GridSize.SMALL -> R.string.grid_small
        GridSize.MEDIUM -> R.string.grid_medium
        GridSize.LARGE -> R.string.grid_large
    }

/** A setting with a few named values, one of them chosen. */
@Composable
private fun <T> Choice(title: Int, options: List<T>, selected: T, label: (T) -> Int, onSelect: (T) -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(stringResource(label(option))) })
        }
    }
}

private val TabPosition.label: Int
    get() = when (this) {
        TabPosition.AUTO -> R.string.tab_position_auto
        TabPosition.LEFT -> R.string.tab_position_left
        TabPosition.RIGHT -> R.string.tab_position_right
        TabPosition.BOTTOM -> R.string.tab_position_bottom
    }

@Composable
private fun Heading(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
