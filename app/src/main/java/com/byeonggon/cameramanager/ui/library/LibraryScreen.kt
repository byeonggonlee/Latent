package com.byeonggon.cameramanager.ui.library

import androidx.compose.foundation.background
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.byeonggon.cameramanager.R
import com.byeonggon.cameramanager.data.Trash
import com.byeonggon.cameramanager.data.db.GeoSource
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.ui.common.StorageAccessCard
import com.byeonggon.cameramanager.ui.common.rememberMediaModel
import com.byeonggon.cameramanager.ui.common.rememberStorageAccess
import com.byeonggon.cameramanager.ui.common.rememberWindowLayout
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FORMAT = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
private val DAY_FORMAT_WITH_YEAR = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)

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

    val context = LocalContext.current
    LaunchedEffect(state.shareFiles) {
        if (state.shareFiles.isNotEmpty()) {
            context.shareAll(state.shareFiles)
            viewModel.consumeShareFiles()
        }
    }

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
        } else if (selecting && state.showingTrash) {
            TrashSelectionBar(state, viewModel)
        } else if (selecting) {
            SelectionBar(state, viewModel)
        } else if (state.showingTrash) {
            TrashBar(state, viewModel)
        } else {
            // Once the large title has scrolled away, a small one stays at the top with the menu, as in One UI.
            val titleGone by remember(gridState) { derivedStateOf { gridState.firstVisibleItemIndex > 0 } }
            if (titleGone && state.days.isNotEmpty()) CompactTitle(state, viewModel)
        }
        val withTitle = !selecting && !state.showingTrash && state.batch == null
        if (state.loading) {
            // Nothing to show until the first query returns; it is too quick to warrant a spinner.
        } else if (state.days.isEmpty()) {
            if (withTitle) {
                LargeTitle(state, viewModel)
                KindFilters(state, viewModel)
            }
            EmptyLibrary(noItemsAtAll = state.libraryIsEmpty, trash = state.showingTrash)
        } else {
            LazyVerticalGrid(
                // Larger thumbnails where the window has room for them, as on a tablet or a laptop.
                columns = GridCells.Adaptive(if (rememberWindowLayout().sideNavigation) 160.dp else 108.dp),
                state = gridState,
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (withTitle) {
                    item(key = "title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") { LargeTitle(state, viewModel) }
                    item(key = "kinds", span = { GridItemSpan(maxLineSpan) }, contentType = "kinds") { KindFilters(state, viewModel) }
                }
                state.days.forEach { day ->
                    item(
                        key = "day-${day.date}",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "header",
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(dayTitle(day.date), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${day.items.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(day.items, key = { it.id }, contentType = { "media" }) { item ->
                        // Holding a photo starts picking photos out; from then on a tap picks, too.
                        MediaCell(
                            item = item,
                            // A JPEG that stands for its raw file as well says so.
                            withRaw = state.stacked && item.kind == MediaKind.PHOTO && item.id in state.partners,
                            selected = item.id in state.selected,
                            // In the trash a photo is only picked, to be put back or deleted.
                            onClick = { if (selecting || state.showingTrash) viewModel.toggle(item.id) else onOpen(item.id) },
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
        TextButton(onClick = viewModel::shareSelected) { Text("공유") }
        TextButton(onClick = viewModel::trashSelected) { Text("삭제") }
    }
}

/** Above the trash: the way back to the library, how long photos stay, and emptying it. */
@Composable
private fun TrashBar(state: LibraryUiState, viewModel: LibraryViewModel) {
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.showTrash(false) }) { Text("← 보관함") }
            Text("휴지통", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirming = true }, enabled = state.trashCount > 0) { Text("비우기") }
        }
        Text(
            "${Trash.KEPT_DAYS}일이 지나면 완전히 삭제됩니다. 사진을 눌러 고른 뒤 되돌리거나 지울 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    if (confirming) {
        ConfirmDeletion("휴지통을 비울까요?", "${state.trashCount}장이 완전히 삭제되며 되돌릴 수 없습니다.", { confirming = false }) {
            viewModel.emptyTrash()
        }
    }
}

/** What can be done to photos picked out in the trash. */
@Composable
private fun TrashSelectionBar(state: LibraryUiState, viewModel: LibraryViewModel) {
    var confirming by remember { mutableStateOf(false) }
    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = viewModel::clearSelection) { Text("✕ ${state.selected.size}장 선택") }
        TextButton(onClick = viewModel::restoreSelected) { Text("되돌리기") }
        TextButton(onClick = { confirming = true }) { Text("완전히 삭제") }
    }
    if (confirming) {
        ConfirmDeletion("완전히 삭제할까요?", "${state.selected.size}장이 삭제되며 되돌릴 수 없습니다.", { confirming = false }) {
            viewModel.deleteSelectedForever()
        }
    }
}

@Composable
private fun ConfirmDeletion(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            ) { Text("삭제") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
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

/** The library's name in large type with what it holds, scrolling away with the photos as in One UI. */
@Composable
private fun LargeTitle(state: LibraryUiState, viewModel: LibraryViewModel) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 40.dp)) {
        Text("보관함", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                summaryOf(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            LibraryMenu(state, viewModel)
        }
    }
}

/** What stays at the top once [LargeTitle] has scrolled away. */
@Composable
private fun CompactTitle(state: LibraryUiState, viewModel: LibraryViewModel) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("보관함", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        LibraryMenu(state, viewModel)
    }
}

/** How many photos and videos are shown, e.g. "사진 1,434 · 동영상 106". */
private fun summaryOf(state: LibraryUiState): String {
    val shown = state.days.flatMap { it.items }
    val videos = shown.count { it.kind == MediaKind.VIDEO }
    val photos = shown.size - videos
    val format = java.text.NumberFormat.getIntegerInstance(Locale.KOREAN)
    return listOfNotNull(
        "사진 ${format.format(photos)}".takeIf { photos > 0 || videos == 0 },
        "동영상 ${format.format(videos)}".takeIf { videos > 0 },
    ).joinToString(" · ")
}

/** Which kinds of files are shown, and the stars a photo needs if that is narrowed down too. */
@Composable
private fun KindFilters(state: LibraryUiState, viewModel: LibraryViewModel) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == state.filter,
                onClick = { viewModel.setFilter(filter) },
                label = { Text(filter.label) },
                shape = RoundedCornerShape(50),
                border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
        // Narrowing by stars is chosen from the menu; here it shows, and can be taken off.
        if (state.leastRating > 0) {
            InputChip(
                selected = true,
                onClick = { viewModel.setLeastRating(0) },
                label = { Text("★ ${state.leastRating} 이상") },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "별점 조건 지우기", modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(50),
            )
        }
    }
}

/** How the library is shown, and the trash. */
@Composable
private fun LibraryMenu(state: LibraryUiState, viewModel: LibraryViewModel) {
    var open by remember { mutableStateOf(false) }
    var rating by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "보관함 메뉴") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("별점으로 거르기") },
                onClick = {
                    open = false
                    rating = true
                },
            )
            DropdownMenuItem(
                text = { Text("RAW+JPEG 묶어 보기") },
                trailingIcon = { Checkbox(checked = state.stacked, onCheckedChange = null) },
                onClick = {
                    open = false
                    viewModel.setStacked(!state.stacked)
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (state.trashCount > 0) "휴지통 ${state.trashCount}" else "휴지통") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                onClick = {
                    open = false
                    viewModel.showTrash(true)
                },
            )
        }
        DropdownMenu(expanded = rating, onDismissRequest = { rating = false }) {
            for (stars in 0..5) {
                DropdownMenuItem(
                    text = { Text(if (stars == 0) "모두 보기" else "★".repeat(stars) + " 이상") },
                    onClick = {
                        rating = false
                        viewModel.setLeastRating(stars)
                    },
                )
            }
        }
    }
}

/** A day as One UI names it: "10월 9일 목요일", with the year only when it is not this one. */
private fun dayTitle(date: LocalDate): String =
    date.format(if (date.year == LocalDate.now().year) DAY_FORMAT else DAY_FORMAT_WITH_YEAR)

@Composable
private fun EmptyLibrary(noItemsAtAll: Boolean, trash: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = if (trash) {
                "휴지통이 비어 있습니다."
            } else if (noItemsAtAll) {
                "아직 가져온 사진이 없습니다.\n카메라 탭에서 카메라를 연결해 가져오세요."
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
                painterResource(R.drawable.ic_edit),
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

/** Hands several photos to whichever app the user picks. */
private fun Context.shareAll(files: List<File>) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType("image/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(Intent.createChooser(send, "사진 ${files.size}장"))
    } catch (e: ActivityNotFoundException) {
        // No app on the device takes photos; nothing useful to do.
    }
}
