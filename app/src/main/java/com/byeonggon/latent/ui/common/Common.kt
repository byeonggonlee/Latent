package com.byeonggon.latent.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.ThemeMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.request.ImageRequest
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.LibraryStorage
import com.byeonggon.latent.data.PhoneLibrary
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import java.io.File
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.R

@Composable
fun LatentTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode = context.appContainer.settings.settings.collectAsStateWithLifecycle(AppSettings()).value.theme
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // The system bars' icons are dark on a light screen and light on a dark one, whichever the phone is set to; over
    // the photo workspace, which is dark in either theme, they are light.
    val workspaces = remember { mutableIntStateOf(0) }
    val lightBars = !dark && workspaces.intValue == 0
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    // Latent's own colours, not the wallpaper's: those would tint what sits next to the photos, and differ by phone.
    CompositionLocalProvider(LocalWorkspaces provides workspaces) {
        MaterialTheme(colorScheme = if (dark) LatentDark else LatentLight, content = content)
    }
}

/** How many photo workspaces ([PhotoTheme]) are on screen; while one is, the system bars' icons are light. */
internal val LocalWorkspaces = staticCompositionLocalOf<MutableIntState?> { null }

/** Figures all of one width, for numbers that change in place (a slider's value, a count): they do not jitter. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/** A request for a library file's picture that loads again once the file has been replaced in place. */
@Composable
fun rememberMediaModel(item: MediaItemEntity): ImageRequest {
    val context = LocalContext.current
    return remember(item.path, item.sizeBytes) {
        ImageRequest.Builder(context)
            .data(File(item.path))
            .memoryCacheKey("${item.path}#${item.sizeBytes}")
            .build()
    }
}

/**
 * A request for a library file's thumbnail in the grid; one is made and kept the first time. Videos
 * are left to the image loader, which takes a frame from them.
 */
@Composable
fun rememberThumbnailModel(item: MediaItemEntity): ImageRequest {
    val context = LocalContext.current
    // A photo that stays as it is (another app\'s, a HEIF one); what was done to it shows in its thumbnail all the same.
    val edit = item.edit.takeIf { item.photoKeptAsIs }
    return remember(item.path, item.sizeBytes, item.kind, edit) {
        ImageRequest.Builder(context)
            .data(if (item.kind == MediaKind.VIDEO) File(item.path) else Thumbnail(item.path, item.sizeBytes, edit))
            .memoryCacheKey("thumbnail:${item.path}#${item.sizeBytes}${edit?.let { "#$it" } ?: ""}")
            .build()
    }
}

/** Whether the app may manage the library on shared storage; re-checked whenever the screen resumes. */
@Composable
fun rememberStorageAccess(): Boolean {
    val storage = LocalContext.current.appContainer.storage
    var granted by remember { mutableStateOf(storage.hasAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = storage.hasAccess() }
    return granted
}

/**
 * Asks for what the library needs on shared storage, one step at a time: reading photos and videos,
 * then the library folder itself.
 */
@Composable
fun StorageAccessCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val storage = context.appContainer.storage
    var canRead by remember { mutableStateOf(LibraryStorage.canReadMedia(context)) }
    var refused by remember { mutableStateOf(false) }
    var wrongFolder by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canRead = LibraryStorage.canReadMedia(context) }
    val requestMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canRead = LibraryStorage.canReadMedia(context)
        refused = !canRead
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        wrongFolder = tree != null && !storage.folder.accept(tree)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.storage_needed), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.storage_intro, PhoneLibrary.LIBRARY_FOLDER.removeSuffix("/")), style = MaterialTheme.typography.bodyMedium)
            if (!canRead) {
                Text(
                    stringResource(R.string.storage_step_media),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (refused) {
                    Text(stringResource(R.string.storage_refused), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.open_app_settings))
                    }
                }
                Button(onClick = { requestMedia.launch(LibraryStorage.MEDIA_PERMISSIONS) }) {
                    Text(stringResource(R.string.allow_media))
                }
            } else {
                Text(
                    stringResource(R.string.storage_step_folder, PhoneLibrary.LIBRARY_FOLDER.removeSuffix("/")),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (wrongFolder) {
                    Text(stringResource(R.string.storage_wrong_folder, PhoneLibrary.LIBRARY_FOLDER.removeSuffix("/")), style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        // The picker can only open on a folder that exists.
                        storage.root.mkdirs()
                        pickFolder.launch(storage.folder.pickerStart)
                    },
                ) {
                    Text(stringResource(R.string.choose_folder))
                }
            }
        }
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // A step above the screen in tone, not a shadow (UI_GUIDE §6, §8.3).
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

/** Opens a page in the browser; the app itself needs no internet for that. */
fun Context.openInBrowser(address: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, address.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No browser on the phone; nothing useful to do.
    }
}
