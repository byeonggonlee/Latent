package com.byeonggon.cameramanager.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.byeonggon.cameramanager.R
import com.byeonggon.cameramanager.data.db.GeoSource
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CAPTURE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(itemId: Long, onClose: () -> Unit, viewModel: ViewerViewModel = viewModel()) {
    LaunchedEffect(itemId) { viewModel.open(itemId) }
    BackHandler(onBack = onClose)

    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.item
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.fileName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
                    }
                },
            )
        },
        bottomBar = {
            if (item != null) {
                InfoPanel(
                    item = item,
                    enhanced = state.enhanced != null,
                    busy = state.busy,
                    onToggleEnhance = viewModel::toggleEnhance,
                    onSaveEnhanced = viewModel::saveEnhanced,
                    onShowMap = { context.showOnMap(item) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Black,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (item != null) {
                val enhanced = state.enhanced
                ZoomableBox(resetKey = item.id) {
                    if (enhanced != null) {
                        Image(
                            bitmap = enhanced.asImageBitmap(),
                            contentDescription = item.fileName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AsyncImage(
                            model = File(item.path),
                            contentDescription = item.fileName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (item.kind == MediaKind.VIDEO) {
                    FilledIconButton(onClick = { context.playVideo(item) }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "재생", modifier = Modifier.size(40.dp))
                    }
                }
            }
            if (state.busy) CircularProgressIndicator()
        }
    }
}

@Composable
private fun InfoPanel(
    item: MediaItemEntity,
    enhanced: Boolean,
    busy: Boolean,
    onToggleEnhance: () -> Unit,
    onSaveEnhanced: () -> Unit,
    onShowMap: () -> Unit,
) {
    val context = LocalContext.current
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = locationLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (item.geoSource != GeoSource.NONE) TextButton(onClick = onShowMap) { Text("지도에서 보기") }
            }
            if (item.kind == MediaKind.PHOTO) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = enhanced,
                        onClick = onToggleEnhance,
                        enabled = !busy,
                        label = { Text("자동 보정") },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_auto_fix),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    Button(onClick = onSaveEnhanced, enabled = enhanced && !busy) { Text("보정본 저장") }
                }
            }
        }
    }
}

/** Pinch to zoom, drag to pan, double-tap to toggle. Resets when [resetKey] changes. */
@Composable
private fun ZoomableBox(resetKey: Any, content: @Composable () -> Unit) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(resetKey) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    val maxX = size.width * (scale - 1f) / 2f
                    val maxY = size.height * (scale - 1f) / 2f
                    offset = Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                    )
                }
            }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                        offset = Offset.Zero
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
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
