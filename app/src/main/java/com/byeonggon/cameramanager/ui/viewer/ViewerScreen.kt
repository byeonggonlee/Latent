package com.byeonggon.cameramanager.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.byeonggon.cameramanager.data.db.GeoSource
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.ui.common.ZoomableBox
import com.byeonggon.cameramanager.ui.common.rememberMediaModel
import kotlinx.coroutines.flow.drop
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CAPTURE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Shows one photo of [items], the library as it is listed, and lets the neighbouring ones be swiped to;
 * [onItemChange] is told which one is on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    itemId: Long,
    items: List<MediaItemEntity>,
    onItemChange: (Long) -> Unit,
    /** For each photo shot as raw and JPEG at once, the other file of the pair. */
    partners: Map<Long, Long> = emptyMap(),
    onClose: () -> Unit,
    onEdit: () -> Unit,
    viewModel: ViewerViewModel = viewModel(),
) {
    LaunchedEffect(itemId) { viewModel.open(itemId) }
    val close = {
        viewModel.close()
        onClose()
    }
    BackHandler(onBack = close)

    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.item
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.shareFile) {
        state.shareFile?.let {
            context.share(it, MediaKind.PHOTO)
            viewModel.consumeShareFile()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // A photo that the library does not list right now, filtered out perhaps, is shown on its own.
    val pages = if (items.any { it.id == itemId }) items else listOfNotNull(item)
    val pager = rememberPagerState(initialPage = pages.indexOfFirst { it.id == itemId }.coerceAtLeast(0)) { pages.size }
    val shownId by rememberUpdatedState(itemId)
    val changeItem by rememberUpdatedState(onItemChange)
    val currentPages by rememberUpdatedState(pages)
    // The list can change under the photo, when one is added before it: the photo stays, its page number does not.
    LaunchedEffect(pages, itemId) {
        val page = pages.indexOfFirst { it.id == itemId }
        if (page >= 0 && page != pager.currentPage) pager.scrollToPage(page)
    }
    LaunchedEffect(pager) {
        // Only a page the user turned to counts. Where the pager starts out may not be the photo yet,
        // if the list of pages was not ready when it was set up; the effect above takes it there.
        snapshotFlow { pager.settledPage }.drop(1).collect { page ->
            currentPages.getOrNull(page)?.id?.let { if (it != shownId) changeItem(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.fileName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
                    }
                },
            )
        },
        bottomBar = {
            if (item != null) {
                InfoPanel(
                    state = state,
                    item = item,
                    onEdit = onEdit,
                    onPaste = viewModel::pasteEdit,
                    onRestore = viewModel::restoreOriginal,
                    onExport = viewModel::exportRaw,
                    onRate = viewModel::setRating,
                    onShare = { longSide -> if (longSide == RAW_FILE) context.share(File(item.path), item.kind) else viewModel.share(longSide) },
                    onShowMap = { context.showOnMap(item) },
                    onPartner = partners[item.id]?.let { partner -> { onItemChange(partner) } },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Black,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            // While the list of pages changes, the pager can briefly ask for a page that is no longer there.
            HorizontalPager(state = pager, key = { pages.getOrNull(it)?.id ?: -1L - it }, modifier = Modifier.fillMaxSize()) { page ->
                val shown = pages.getOrNull(page) ?: return@HorizontalPager
                // A raw file with corrections is drawn with them; that is only worked out for the photo in front.
                val rendered = state.rendered.takeIf { shown.id == item?.id }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ZoomableBox(resetKey = shown.id) {
                        if (rendered != null) {
                            Image(
                                bitmap = rendered.asImageBitmap(),
                                contentDescription = shown.fileName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            AsyncImage(
                                model = rememberMediaModel(shown),
                                contentDescription = shown.fileName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (shown.kind == MediaKind.VIDEO) {
                        FilledIconButton(onClick = { context.playVideo(shown) }, modifier = Modifier.size(64.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "재생", modifier = Modifier.size(40.dp))
                        }
                    }
                }
            }
            if (state.busy) CircularProgressIndicator()
        }
    }
}

@Composable
private fun InfoPanel(
    state: ViewerUiState,
    item: MediaItemEntity,
    onEdit: () -> Unit,
    onPaste: () -> Unit,
    onRestore: () -> Unit,
    onExport: () -> Unit,
    onRate: (Int) -> Unit,
    /** Called with the long side the shared copy may have, null for full size, or [RAW_FILE] for the file as it is. */
    onShare: (Int?) -> Unit,
    onShowMap: () -> Unit,
    /** Shows the other file of a raw and JPEG pair; null for a photo that has none. */
    onPartner: (() -> Unit)?,
) {
    val context = LocalContext.current
    var confirmingRestore by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                CAPTURE_FORMAT.format(Instant.ofEpochMilli(item.capturedAt).atZone(ZoneId.systemDefault())) +
                    " · " + Formatter.formatShortFileSize(context, item.sizeBytes),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.details?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = locationLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (item.geoSource != GeoSource.NONE) TextButton(onClick = onShowMap) { Text("지도에서 보기") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (stars in 1..5) {
                    Text(
                        text = if (stars <= item.rating) "★" else "☆",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (stars <= item.rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable(role = Role.Button) { onRate(stars) }
                            .padding(horizontal = 6.dp)
                            .semantics { contentDescription = "별점 $stars" },
                    )
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.kind != MediaKind.VIDEO) {
                    Button(onClick = onEdit, enabled = !state.busy) { Text("보정") }
                    if (state.canPaste) {
                        OutlinedButton(onClick = onPaste, enabled = !state.busy) { Text("보정 값 붙여넣기") }
                    }
                    if (item.kind == MediaKind.RAW) {
                        OutlinedButton(onClick = onExport, enabled = !state.busy) { Text("JPEG로 저장") }
                    }
                    if (state.edited) {
                        OutlinedButton(onClick = { confirmingRestore = true }, enabled = !state.busy) { Text("원본으로 되돌리기") }
                    }
                }
                ShareButton(item.kind, enabled = !state.busy, onShare = onShare)
                if (onPartner != null) {
                    TextButton(onClick = onPartner, enabled = !state.busy) {
                        Text(if (item.kind == MediaKind.RAW) "JPEG 보기" else "RAW 보기")
                    }
                }
            }
        }
    }

    if (confirmingRestore) {
        AlertDialog(
            onDismissRequest = { confirmingRestore = false },
            title = { Text("원본으로 되돌릴까요?") },
            text = { Text("이 사진에 저장한 보정 값이 지워집니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRestore = false
                        onRestore()
                    },
                ) { Text("되돌리기") }
            },
            dismissButton = { TextButton(onClick = { confirmingRestore = false }) { Text("취소") } },
        )
    }
}

private fun locationLabel(item: MediaItemEntity): String {
    val latitude = item.latitude
    val longitude = item.longitude
    if (latitude == null || longitude == null) return "위치 정보 없음"
    val source = when (item.geoSource) {
        GeoSource.CAMERA -> "카메라"
        GeoSource.TRACK -> if (item.kind == MediaKind.VIDEO) "위치 기록 · 앱에만 저장" else "위치 기록"
        GeoSource.NONE -> ""
    }
    return String.format(Locale.US, "%.5f, %.5f (%s)", latitude, longitude, source)
}

private fun Context.showOnMap(item: MediaItemEntity) {
    val latitude = item.latitude ?: return
    val longitude = item.longitude ?: return
    val uri = "geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(item.fileName)})".toUri()
    launch(Intent(Intent.ACTION_VIEW, uri))
}

/** Offers the sizes a photo can be shared at; a video is shared as it is. */
@Composable
private fun ShareButton(kind: MediaKind, enabled: Boolean, onShare: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { if (kind == MediaKind.VIDEO) onShare(RAW_FILE) else open = true }, enabled = enabled) { Text("공유") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val choices = when (kind) {
                MediaKind.RAW -> listOf("JPEG · 원본 크기" to null, "JPEG · 긴 변 2048px" to 2048, "JPEG · 긴 변 1080px" to 1080, "RAW 파일 그대로" to RAW_FILE)
                else -> listOf("원본 그대로" to null, "긴 변 2048px로 줄여서" to 2048, "긴 변 1080px로 줄여서" to 1080)
            }
            for ((label, longSide) in choices) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onShare(longSide)
                    },
                )
            }
        }
    }
}

/** What [onShare] is given to share a file just as it is stored. */
private const val RAW_FILE = -1

/** Hands the file to whichever app the user picks: a messenger, a cloud drive, another editor. */
private fun Context.share(file: File, kind: MediaKind) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val type = when (kind) {
        MediaKind.PHOTO -> "image/jpeg"
        MediaKind.RAW -> "image/x-sony-arw"
        MediaKind.VIDEO -> "video/*"
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launch(Intent.createChooser(send, file.name))
}

private fun Context.playVideo(item: MediaItemEntity) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", File(item.path))
    launch(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

private fun Context.launch(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No app on the device can handle it; nothing useful to do.
    }
}
