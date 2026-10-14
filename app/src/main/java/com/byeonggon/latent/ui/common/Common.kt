package com.byeonggon.latent.ui.common

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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.request.ImageRequest
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.LibraryStorage
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import java.io.File

@Composable
fun LatentTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

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
    return remember(item.path, item.sizeBytes, item.kind) {
        ImageRequest.Builder(context)
            .data(if (item.kind == MediaKind.VIDEO) File(item.path) else Thumbnail(item.path, item.sizeBytes))
            .memoryCacheKey("thumbnail:${item.path}#${item.sizeBytes}")
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("저장소 접근이 필요합니다", style = MaterialTheme.typography.titleMedium)
            if (!canRead) {
                Text(
                    "1/2 · 사진과 동영상, 그 위치 정보를 읽을 수 있게 '모두 허용'을 선택해주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (refused) {
                    Text("허용되지 않았습니다. 앱 설정의 권한에서 허용해주세요.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                            )
                        },
                    ) {
                        Text("앱 설정 열기")
                    }
                }
                Button(onClick = { requestMedia.launch(LibraryStorage.MEDIA_PERMISSIONS) }) {
                    Text("사진·동영상 접근 허용")
                }
            } else {
                Text(
                    "2/2 · 사진을 정리할 DCIM/Latent 폴더를 열어 '이 폴더 사용'을 눌러주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (wrongFolder) {
                    Text("다른 폴더입니다. DCIM/Latent 폴더를 골라주세요.", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        // The picker can only open on a folder that exists.
                        storage.root.mkdirs()
                        pickFolder.launch(storage.folder.pickerStart)
                    },
                ) {
                    Text("폴더 선택")
                }
            }
        }
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
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
