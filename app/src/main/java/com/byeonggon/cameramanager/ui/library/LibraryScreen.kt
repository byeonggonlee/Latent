package com.byeonggon.cameramanager.ui.library

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.byeonggon.cameramanager.R
import com.byeonggon.cameramanager.data.db.GeoSource
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.ui.common.StorageAccessCard
import com.byeonggon.cameramanager.ui.common.rememberMediaModel
import com.byeonggon.cameramanager.ui.common.rememberStorageAccess
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREAN)

@Composable
fun LibraryScreen(
    gridState: LazyGridState,
    onOpen: (Long) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val storageGranted = rememberStorageAccess()
    val selecting = state.selected.isNotEmpty()
    BackHandler(enabled = selecting, onBack = viewModel::clearSelection)

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LibraryContent(state, storageGranted, selecting, gridState, onOpen, viewModel)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    storageGranted: Boolean,
    selecting: Boolean,
    gridState: LazyGridState,
    onOpen: (Long) -> Unit,
    viewModel: LibraryViewModel,
) {
    Column(Modifier.fillMaxSize()) {
        if (!storageGranted) StorageAccessCard(Modifier.padding(16.dp))
        val batch = state.batch
        if (batch != null) {
            BatchBar(batch, onCancel = viewModel::cancelBatch)
        } else if (selecting) {
            SelectionBar(state, viewModel)
        } else Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryFilter.entries.forEach { filter ->
                FilterChip(selected = filter == state.filter, onClick = { viewModel.setFilter(filter) }, label = { Text(filter.label) })
            }
            RatingFilter(state.leastRating, viewModel::setLeastRating)
            FilterChip(selected = state.stacked, onClick = { viewModel.setStacked(!state.stacked) }, label = { Text("RAW+JPEG 묶기") })
        }
        if (state.loading) {
            // Nothing to show until the first query returns; it is too quick to warrant a spinner.
        } else if (state.days.isEmpty()) {
            EmptyLibrary(noItemsAtAll = state.libraryIsEmpty)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                state = gridState,
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.days.forEach { day ->
                    item(
                        key = "day-${day.date}",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "header",
                    ) {
                        Text(
                            text = "${day.date.format(DAY_FORMAT)} · ${day.items.size}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items(day.items, key = { it.id }, contentType = { "media" }) { item ->
                        // Holding a photo starts picking photos out; from then on a tap picks, too.
                        MediaCell(
                            item = item,
                            // A JPEG that stands for its raw file as well says so.
                            withRaw = state.stacked && item.kind == MediaKind.PHOTO && item.id in state.partners,
                            selected = item.id in state.selected,
                            onClick = { if (selecting) viewModel.toggle(item.id) else onOpen(item.id) },
                            onHold = { viewModel.toggle(item.id) },
                        )
                    }
                }
            }
        }
    }
}

/** What can be done to all the selected photos at once. */
@Composable
private fun SelectionBar(state: LibraryUiState, viewModel: LibraryViewModel) {
    var rating by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = viewModel::clearSelection) { Text("✕ ${state.selected.size}장 선택") }
        Box {
            TextButton(onClick = { rating = true }) { Text("별점") }
            DropdownMenu(expanded = rating, onDismissRequest = { rating = false }) {
                for (stars in 5 downTo 0) {
                    DropdownMenuItem(
                        text = { Text(if (stars == 0) "별점 지우기" else "★".repeat(stars)) },
                        onClick = {
                            rating = false
                            viewModel.rateSelected(stars)
                        },
                    )
                }
            }
        }
        TextButton(onClick = viewModel::pasteToSelected, enabled = state.canPaste) { Text("보정 값 붙여넣기") }
        TextButton(onClick = viewModel::exportSelected) { Text("RAW를 JPEG로") }
    }
}

/** How far the work on the selected photos has got, and a way to stop it. */
@Composable
private fun BatchBar(batch: BatchProgress, onCancel: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${batch.label} ${batch.done + 1}/${batch.total}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("중단") }
        }
        LinearProgressIndicator(progress = { batch.done.toFloat() / batch.total }, modifier = Modifier.fillMaxWidth())
    }
}

/** Narrows the library down to the photos with at least so many stars. */
@Composable
private fun RatingFilter(leastRating: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = leastRating > 0,
            onClick = { open = true },
            label = { Text(if (leastRating == 0) "별점" else "★ $leastRating 이상") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (stars in 0..5) {
                DropdownMenuItem(
                    text = { Text(if (stars == 0) "모두 보기" else "★".repeat(stars) + " 이상") },
                    onClick = {
                        open = false
                        onChange(stars)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(noItemsAtAll: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = if (noItemsAtAll) {
                "아직 가져온 사진이 없습니다.\n동기화 탭에서 카메라를 연결해 가져오세요."
            } else {
                "이 종류의 항목이 없습니다."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(item: MediaItemEntity, withRaw: Boolean, selected: Boolean, onClick: () -> Unit, onHold: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onHold)
            .then(if (selected) Modifier.border(4.dp, MaterialTheme.colorScheme.primary) else Modifier),
    ) {
        AsyncImage(
            model = rememberMediaModel(item),
            contentDescription = item.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        when (item.kind) {
            MediaKind.RAW, MediaKind.PHOTO -> if (item.kind == MediaKind.RAW || withRaw) Text(
                text = if (withRaw) "RAW+J" else "RAW",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            MediaKind.VIDEO -> Icon(
                Icons.Default.PlayArrow,
                contentDescription = "동영상",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
            )
        }
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .semantics { contentDescription = "선택됨" },
            )
        }
        if (item.rating > 0) {
            Text(
                text = "★".repeat(item.rating),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (item.edit != null) {
            Icon(
                painterResource(R.drawable.ic_auto_fix),
                contentDescription = "보정됨",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp),
            )
        }
        if (item.geoSource != GeoSource.NONE) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = "위치 정보 있음",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(14.dp),
            )
        }
    }
}
