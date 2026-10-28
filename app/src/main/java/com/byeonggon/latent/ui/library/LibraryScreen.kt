package com.byeonggon.latent.ui.library

import androidx.compose.foundation.background
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.byeonggon.latent.R
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.ui.common.ActionRow
import com.byeonggon.latent.ui.common.BarAction
import com.byeonggon.latent.ui.common.OverPhoto
import com.byeonggon.latent.ui.common.tabular
import com.byeonggon.latent.ui.compare.MOST_COMPARED
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.db.FLAG_PICKED
import com.byeonggon.latent.data.db.FLAG_REJECTED
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.ui.common.StorageAccessCard
import com.byeonggon.latent.ui.common.rememberThumbnailModel
import com.byeonggon.latent.ui.common.rememberStorageAccess
import com.byeonggon.latent.ui.common.rememberWindowLayout
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.ui.common.localFormat
import com.byeonggon.latent.ui.common.EmptyState


/**
 * Which of the library's dialogs are up. Kept above the title, not in it: the title is built again whenever the list
 * empties or fills, and a dialog that lived in it would close with it.
 */
internal class LibraryDialogs {
    var finding by mutableStateOf(false)
    var about by mutableStateOf(false)
    var albums by mutableStateOf(false)
    var trashingRejected by mutableStateOf(false)

    companion object {
        val Saver = listSaver<LibraryDialogs, Boolean>(
            save = { listOf(it.finding, it.about, it.albums, it.trashingRejected) },
            restore = { LibraryDialogs().apply { finding = it[0]; about = it[1]; albums = it[2]; trashingRejected = it[3] } },
        )
    }
}

@Composable
fun LibraryScreen(
    gridState: LazyGridState,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
    onCompare: (List<Long>) -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val storageGranted = rememberStorageAccess()
    // Access given after the library opened: what is in the folder can be read now.
    LaunchedEffect(storageGranted) { if (storageGranted) viewModel.refresh() }
    // Coming back to the app: photos taken or deleted meanwhile, by the phone's camera say, are taken into account.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
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

    // Photos of other apps are deleted by the system, which asks the person first.
    val deletion = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { viewModel.deletionDone() }
    LaunchedEffect(state.deleteRequest) { state.deleteRequest?.let { deletion.launch(IntentSenderRequest.Builder(it).build()) } }

    val dialogs = rememberSaveable(saver = LibraryDialogs.Saver) { LibraryDialogs() }
    Box(Modifier.fillMaxSize()) {
        LibraryContent(state, storageGranted, selecting, gridState, onOpen, onSettings, onCompare, dialogs, viewModel)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (dialogs.finding) FindSheet(state, viewModel, onDismiss = { dialogs.finding = false })
    if (dialogs.about) AboutDialog(onDismiss = { dialogs.about = false })
    if (dialogs.albums) AlbumsDialog(state, viewModel, onDismiss = { dialogs.albums = false })
    if (dialogs.trashingRejected) {
        val keptDays = LocalContext.current.appContainer.settings.settings.collectAsStateWithLifecycle(AppSettings()).value.trashKeptDays
        ConfirmDeletion(
            stringResource(R.string.trash_rejected_question),
            pluralStringResource(R.plurals.trash_rejected_detail, keptDays, keptDays),
            { dialogs.trashingRejected = false },
        ) { viewModel.trashRejected() }
    }
    state.phoneChoice?.let { choice -> PhoneFoldersDialog(choice, viewModel) }
}

/** The folders of the phone with photos in them, to pick those the library lists. */
@Composable
private fun PhoneFoldersDialog(choice: PhoneFolderChoice, viewModel: LibraryViewModel) {
    val format = java.text.NumberFormat.getIntegerInstance()
    AlertDialog(
        onDismissRequest = viewModel::dismissPhoneFolders,
        title = { Text(stringResource(R.string.phone_folders_title)) },
        text = {
            Column {
                Text(stringResource(R.string.phone_folders_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (choice.folders.isEmpty()) {
                    Text(stringResource(R.string.phone_folders_none), modifier = Modifier.padding(top = 12.dp))
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    choice.folders.forEach { folder ->
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.togglePhoneFolder(folder.path) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = folder.path in choice.chosen, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                            Text(stringResource(R.string.phone_folder_count, folder.path.trimEnd('/'), format.format(folder.count)), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::confirmPhoneFolders) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissPhoneFolders) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    storageGranted: Boolean,
    selecting: Boolean,
    gridState: LazyGridState,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
    onCompare: (List<Long>) -> Unit,
    dialogs: LibraryDialogs,
    viewModel: LibraryViewModel,
) {
    val container = LocalContext.current.appContainer
    val gridSize = container.settings.settings.collectAsStateWithLifecycle(AppSettings()).value.gridSize
    // Which photos' edits are being saved, or could not be: shown on their thumbnails until settled.
    val saving by container.editSaver.saving.collectAsStateWithLifecycle()
    val failedSaves by container.editSaver.failed.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        if (!storageGranted) StorageAccessCard(Modifier.padding(16.dp))
        val batch = state.batch
        if (batch != null) {
            BatchBar(batch, onCancel = viewModel::cancelBatch)
        } else if (selecting && state.showingTrash) {
            TrashSelectionBar(state, viewModel)
        } else if (selecting) {
            SelectionBar(state, viewModel, onCompare)
        } else if (state.showingTrash) {
            TrashBar(state, viewModel)
        } else {
            // Once the large title has scrolled away, a small one stays at the top with the menu.
            val titleGone by remember(gridState) { derivedStateOf { gridState.firstVisibleItemIndex > 0 } }
            if (titleGone && state.days.isNotEmpty()) CompactTitle(state, viewModel, onSettings, dialogs)
        }
        val withTitle = !selecting && !state.showingTrash && state.batch == null
        if (state.loading) {
            // Nothing to show until the first query returns; it is too quick to warrant a spinner.
        } else if (state.days.isEmpty()) {
            if (withTitle) {
                LargeTitle(state, viewModel, onSettings, dialogs)
                KindFilters(state, viewModel)
            }
            EmptyLibrary(
                noItemsAtAll = state.libraryIsEmpty,
                trash = state.showingTrash,
                onPhonePhotos = viewModel::choosePhoneFolders,
                searching = state.query.isActive,
                onClearSearch = viewModel::clearQuery,
                onShowEverything = {
                    viewModel.setFilter(LibraryFilter.ALL)
                    viewModel.setFlagFilter(FlagFilter.ALL)
                    viewModel.setLeastRating(0)
                    viewModel.showAlbum(null)
                },
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    // Larger thumbnails where the window has room for them, as on a tablet or a laptop.
                    columns = GridCells.Adaptive((if (rememberWindowLayout().sideNavigation) 160.dp else 108.dp) * gridSize.factor),
                    state = gridState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (withTitle) {
                        item(key = "title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") { LargeTitle(state, viewModel, onSettings, dialogs) }
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
                            // A JPEG that stands for its raw file as well says so, and says how that file's save went too.
                            val withRaw = state.stacked && item.kind == MediaKind.PHOTO && item.id in state.partners
                            val partner = state.partners[item.id]?.takeIf { withRaw }
                            // Holding a photo starts picking photos out; from then on a tap picks, too.
                            MediaCell(
                                item = item,
                                withRaw = withRaw,
                                selected = item.id in state.selected,
                                selecting = selecting || state.showingTrash,
                                status = saveStatusOf(item.id, partner, saving, failedSaves.keys),
                                edited = item.edit != null || item.id in state.partnerEdited,
                                // In the trash a photo is only picked, to be put back or deleted. A pair whose raw file could
                                // not be saved opens on that file, where the failure can be dealt with.
                                onClick = { if (selecting || state.showingTrash) viewModel.toggle(item.id) else onOpen(openedFor(item.id, partner, failedSaves.keys)) },
                                onHold = { viewModel.toggle(item.id) },
                                // With a mouse, a right click picks the photo and offers what can be done to it, as a file manager does.
                                menuEnabled = !state.showingTrash,
                                menu = { contextActions(item, state, onOpen, viewModel) },
                                onMenuOpened = { if (item.id !in state.selected) viewModel.toggle(item.id) },
                            )
                        }
                    }
                }
                val months = remember(state.days, withTitle) { monthsAlongTheGrid(state.days, leading = if (withTitle) 2 else 0) }
                FastScroller(gridState, labelAt = { index -> months.labelAt(index) })
            }
        }
    }
}

/** At which grid item each day begins, and so which month the grid is in at any item. */
internal class MonthsAlongTheGrid(private val starts: IntArray, private val dates: List<LocalDate>) {
    fun labelAt(index: Int): String? {
        if (dates.isEmpty()) return null
        var day = starts.binarySearch(index)
        if (day < 0) day = (-day - 2).coerceAtLeast(0)
        return dates[day].format(localFormat("yMMMM"))
    }
}

/** [leading] grid items come before the first day: the title and the filters. */
internal fun monthsAlongTheGrid(days: List<LibraryDay>, leading: Int): MonthsAlongTheGrid {
    var next = leading
    val starts = IntArray(days.size)
    days.forEachIndexed { index, day ->
        starts[index] = next
        // The day's header, then a cell for each of its photos.
        next += 1 + day.items.size
    }
    return MonthsAlongTheGrid(starts, days.map { it.date })
}

/** What can be done to all the selected photos at once. */
@Composable
private fun SelectionBar(state: LibraryUiState, viewModel: LibraryViewModel, onCompare: (List<Long>) -> Unit) {
    var rating by remember { mutableStateOf(false) }
    var flagging by remember { mutableStateOf(false) }
    var choosingAlbum by remember { mutableStateOf(false) }
    if (choosingAlbum) AlbumPickDialog(state, viewModel, onDismiss = { choosingAlbum = false })
    // What is done most comes first; what does not fit on a narrow screen is under More at the end.
    val actions = buildList {
        add(BarAction(stringResource(R.string.share), onClick = viewModel::shareSelected))
        add(BarAction(stringResource(R.string.delete), onClick = viewModel::trashSelected))
        // Two to four photos can be set side by side to choose between.
        add(BarAction(stringResource(R.string.compare), enabled = state.selected.size in 2..MOST_COMPARED) { onCompare(state.selected.toList()) })
        add(BarAction(stringResource(R.string.rating)) { rating = true })
        add(BarAction(stringResource(R.string.flags)) { flagging = true })
        add(BarAction(stringResource(R.string.album_add)) { choosingAlbum = true })
        if (state.album != null) add(BarAction(stringResource(R.string.album_remove), onClick = viewModel::removeSelectedFromAlbum))
        add(BarAction(stringResource(R.string.paste_edit), enabled = state.canPaste, onClick = viewModel::pasteToSelected))
        add(BarAction(stringResource(R.string.raw_to_jpeg), onClick = viewModel::exportSelected))
    }
    // The rating and flag menus open from the bar, whether their action was in it or under More.
    Box {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The count, which also ends the selection, gives way before More does: every action stays reachable.
            ActionRow(
                actions,
                Modifier.weight(1f),
                lead = BarAction(pluralStringResource(R.plurals.selected_count, state.selected.size, state.selected.size), onClick = viewModel::clearSelection),
            )
        }
        DropdownMenu(expanded = rating, onDismissRequest = { rating = false }) {
            for (stars in 5 downTo 0) {
                DropdownMenuItem(
                    text = { Text(if (stars == 0) stringResource(R.string.rating_clear) else "★".repeat(stars)) },
                    onClick = {
                        rating = false
                        viewModel.rateSelected(stars)
                    },
                )
            }
        }
        DropdownMenu(expanded = flagging, onDismissRequest = { flagging = false }) {
            listOf(R.string.flag_pick to FLAG_PICKED, R.string.flag_reject to FLAG_REJECTED, R.string.flag_clear to 0).forEach { (label, flag) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        flagging = false
                        viewModel.flagSelected(flag)
                    },
                )
            }
        }
    }
}

/** Above the trash: the way back to the library, how long photos stay, and emptying it. */
@Composable
private fun TrashBar(state: LibraryUiState, viewModel: LibraryViewModel) {
    var confirming by remember { mutableStateOf(false) }
    val keptDays = LocalContext.current.appContainer.settings.settings.collectAsStateWithLifecycle(AppSettings()).value.trashKeptDays
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.showTrash(false) }) { Text(stringResource(R.string.back_to_library)) }
            Text(stringResource(R.string.trash), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirming = true }, enabled = state.trashCount > 0) { Text(stringResource(R.string.empty_trash)) }
        }
        Text(
            pluralStringResource(R.plurals.trash_explained, keptDays, keptDays),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    if (confirming) {
        ConfirmDeletion(
            stringResource(R.string.empty_trash_question),
            pluralStringResource(R.plurals.empty_trash_detail, state.trashCount, state.trashCount),
            { confirming = false },
        ) {
            viewModel.emptyTrash()
        }
    }
}

/** What can be done to photos picked out in the trash. */
@Composable
private fun TrashSelectionBar(state: LibraryUiState, viewModel: LibraryViewModel) {
    var confirming by remember { mutableStateOf(false) }
    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = viewModel::clearSelection) { Text(pluralStringResource(R.plurals.selected_count, state.selected.size, state.selected.size)) }
        TextButton(onClick = viewModel::restoreSelected) { Text(stringResource(R.string.put_back)) }
        TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.delete_forever)) }
    }
    if (confirming) {
        ConfirmDeletion(
            stringResource(R.string.delete_forever_question),
            pluralStringResource(R.plurals.delete_forever_detail, state.selected.size, state.selected.size),
            { confirming = false },
        ) {
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
            ) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** How far the work on the selected photos has got, and a way to stop it. */
@Composable
private fun BatchBar(batch: BatchProgress, onCancel: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${batch.label} ${batch.done + 1}/${batch.total}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.stop_batch)) }
        }
        LinearProgressIndicator(progress = { batch.done.toFloat() / batch.total }, modifier = Modifier.fillMaxWidth())
    }
}

/** The library's name in large type with what it holds, scrolling away with the photos. */
@Composable
private fun LargeTitle(state: LibraryUiState, viewModel: LibraryViewModel, onSettings: () -> Unit, dialogs: LibraryDialogs) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 40.dp)) {
        Text(stringResource(R.string.library), style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                summaryOf(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            LibraryMenu(state, viewModel, onSettings, dialogs)
        }
    }
}

/** What stays at the top once [LargeTitle] has scrolled away. */
@Composable
private fun CompactTitle(state: LibraryUiState, viewModel: LibraryViewModel, onSettings: () -> Unit, dialogs: LibraryDialogs) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.library), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        LibraryMenu(state, viewModel, onSettings, dialogs)
    }
}

/** How many photos and videos are shown, e.g. "1,434 photos · 106 videos". */
@Composable
private fun summaryOf(state: LibraryUiState): String {
    val shown = state.days.flatMap { it.items }
    val videos = shown.count { it.kind == MediaKind.VIDEO }
    val photos = shown.size - videos
    val format = java.text.NumberFormat.getIntegerInstance()
    return listOfNotNull(
        pluralStringResource(R.plurals.photo_count, photos, format.format(photos)).takeIf { photos > 0 || videos == 0 },
        pluralStringResource(R.plurals.video_count, videos, format.format(videos)).takeIf { videos > 0 },
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
            // The same selected style as the app's other filter chips (UI guide §8.3).
            FilterChip(
                selected = filter == state.filter,
                onClick = { viewModel.setFilter(filter) },
                label = { Text(stringResource(filter.label)) },
            )
        }
        if (state.flagFilter != FlagFilter.ALL) {
            InputChip(
                selected = true,
                onClick = { viewModel.setFlagFilter(FlagFilter.ALL) },
                label = { Text(stringResource(state.flagFilter.label)) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.flag_filter_clear), modifier = Modifier.size(16.dp)) },
            )
        }
        // The album the library is narrowed to shows too, and can be left.
        state.album?.let { shown ->
            InputChip(
                selected = true,
                onClick = { viewModel.showAlbum(null) },
                label = { Text(shown.album.name) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.album_filter_clear), modifier = Modifier.size(16.dp)) },
            )
        }
        // A search shows as one chip, and is taken off with it; the magnifying glass changes it.
        if (state.query.isActive) {
            InputChip(
                selected = true,
                onClick = { viewModel.clearQuery() },
                label = { Text(pluralStringResource(R.plurals.find_conditions, state.query.count, state.query.count)) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.find_clear), modifier = Modifier.size(16.dp)) },
            )
        }
        // Narrowing by stars is chosen from the menu; here it shows, and can be taken off.
        if (state.leastRating > 0) {
            InputChip(
                selected = true,
                onClick = { viewModel.setLeastRating(0) },
                label = { Text(stringResource(R.string.rating_at_least, state.leastRating)) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.rating_filter_clear), modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

/** How the library is shown, and the trash. */
@Composable
private fun LibraryMenu(state: LibraryUiState, viewModel: LibraryViewModel, onSettings: () -> Unit, dialogs: LibraryDialogs) {
    var open by remember { mutableStateOf(false) }
    var rating by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = { dialogs.finding = true }) { Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.find)) }
    Box {
        IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.library_menu)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_by_rating)) },
                onClick = {
                    open = false
                    rating = true
                },
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.show_photos_from),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            LibrarySource.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.label)) },
                    trailingIcon = { RadioButton(selected = state.source == entry, onClick = null) },
                    onClick = {
                        open = false
                        viewModel.setSource(entry)
                    },
                )
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.flags_menu),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            FlagFilter.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.label)) },
                    trailingIcon = { RadioButton(selected = state.flagFilter == entry, onClick = null) },
                    onClick = {
                        open = false
                        viewModel.setFlagFilter(entry)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trash_rejected)) },
                onClick = {
                    open = false
                    dialogs.trashingRejected = true
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.phone_folders)) },
                onClick = {
                    open = false
                    viewModel.choosePhoneFolders()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stack_raw_jpeg)) },
                trailingIcon = { Checkbox(checked = state.stacked, onCheckedChange = null) },
                onClick = {
                    open = false
                    viewModel.setStacked(!state.stacked)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.albums)) },
                onClick = {
                    open = false
                    dialogs.albums = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings)) },
                onClick = {
                    open = false
                    onSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.about)) },
                onClick = {
                    open = false
                    dialogs.about = true
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (state.trashCount > 0) stringResource(R.string.trash_count, state.trashCount) else stringResource(R.string.trash)) },
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
                    text = { Text(if (stars == 0) stringResource(R.string.show_all) else stringResource(R.string.stars_or_more, "★".repeat(stars))) },
                    onClick = {
                        rating = false
                        viewModel.setLeastRating(stars)
                    },
                )
            }
        }
    }
    }
}

/** A day as the phone's language names it, "Thursday, October 9", with the year only when it is not this one. */
private fun dayTitle(date: LocalDate): String =
    date.format(localFormat(if (date.year == LocalDate.now().year) "MMMMdEEEE" else "yMMMMdEEEE"))

/** The library or the trash with nothing to show, and the one way on from there: the trash needs none. */
@Composable
private fun EmptyLibrary(
    noItemsAtAll: Boolean,
    trash: Boolean,
    onPhonePhotos: () -> Unit,
    searching: Boolean,
    onClearSearch: () -> Unit,
    onShowEverything: () -> Unit,
) {
    when {
        trash -> EmptyState(R.drawable.ic_delete, stringResource(R.string.trash_is_empty))
        // The phone's own photos can fill an empty library without a camera.
        noItemsAtAll -> EmptyState(R.drawable.ic_photo_library, stringResource(R.string.library_is_empty), action = stringResource(R.string.phone_folders), onAction = onPhonePhotos)
        searching -> EmptyState(R.drawable.ic_search, stringResource(R.string.find_nothing), action = stringResource(R.string.find_clear), onAction = onClearSearch)
        else -> EmptyState(R.drawable.ic_photo_library, stringResource(R.string.nothing_of_kind), action = stringResource(R.string.show_everything), onAction = onShowEverything)
    }
}

@OptIn(ExperimentalFoundationApi::class)
/** Whether a photo's edit is being saved, or could not be: shown on its thumbnail before anything else. */
internal enum class SaveStatus { NONE, SAVING, FAILED }

/**
 * Where the save of the photo [id] stands, together with that of its raw [partner] when the two show as one: a
 * failure of either is a failure of the pair, so a raw file that could not be saved is not hidden behind its JPEG.
 */
internal fun saveStatusOf(id: Long, partner: Long?, saving: Set<Long>, failed: Set<Long>): SaveStatus = when {
    id in failed || (partner != null && partner in failed) -> SaveStatus.FAILED
    id in saving || (partner != null && partner in saving) -> SaveStatus.SAVING
    else -> SaveStatus.NONE
}

/** Which file of a pair a tap opens: the raw [partner] when it is the one that could not be saved, else [id]. */
internal fun openedFor(id: Long, partner: Long?, failed: Set<Long>): Long =
    if (partner != null && partner in failed && id !in failed) partner else id

/**
 * A photo in the grid, with what is known about it in four fixed corners (UI_GUIDE §8.8): the top left for whether
 * its edit is saving or failed, else its kind; the top right for selection; the bottom left for the cull's decision;
 * the bottom right for a video and an edit. A small cell or large text keeps only the first three, and the video.
 */
@Composable
private fun MediaCell(
    item: MediaItemEntity,
    withRaw: Boolean,
    selected: Boolean,
    selecting: Boolean,
    status: SaveStatus,
    edited: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    menuEnabled: Boolean = false,
    menu: @Composable () -> List<Pair<String, () -> Unit>> = { emptyList() },
    onMenuOpened: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onHold)
            // After the click handling, so that a right click is taken here before that sees it.
            .pointerInput(menuEnabled) {
                if (!menuEnabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            onMenuOpened()
                            menuOpen = true
                        }
                    }
                }
            },
    ) {
        // Few corners on a small cell or with large text: status, selection and the decision are never dropped.
        val roomy = maxWidth >= ROOMY_CELL && LocalDensity.current.fontScale < CROWDED_FONT_SCALE
        val badgeWidth = maxWidth * 0.4f
        if (menuOpen && menuEnabled) {
            DropdownMenu(expanded = true, onDismissRequest = { menuOpen = false }) {
                for ((label, action) in menu()) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menuOpen = false
                            action()
                        },
                    )
                }
            }
        }
        // A selected photo draws in from its edges, with the accent around it: shape and colour together.
        val photoModifier = if (selected) {
            Modifier.fillMaxSize().padding(SELECTED_INSET).border(3.dp, MaterialTheme.colorScheme.primary)
        } else {
            Modifier.fillMaxSize()
        }
        AsyncImage(
            model = rememberThumbnailModel(item),
            contentDescription = item.fileName,
            contentScale = ContentScale.Crop,
            // A rejected photo is dimmed, so that what is left stands out.
            modifier = photoModifier.alpha(if (item.flag == FLAG_REJECTED) 0.4f else 1f),
        )

        // Top left: the save first, else the kind.
        Box(Modifier.align(Alignment.TopStart).padding(BADGE_MARGIN).widthIn(max = badgeWidth)) {
            when {
                status == SaveStatus.FAILED -> FailureBadge()
                status == SaveStatus.SAVING -> Badge(contentDescription = stringResource(R.string.saving_edit)) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = OverPhoto.Content, strokeWidth = 2.dp)
                }
                roomy && (item.kind == MediaKind.RAW || withRaw) -> Badge { BadgeText(if (withRaw) "RAW+J" else "RAW") }
            }
        }

        // Top right: whether it is selected, while photos are being picked out.
        if (selecting) {
            Box(Modifier.align(Alignment.TopEnd).padding(BADGE_MARGIN)) { SelectionMark(selected) }
        }

        // Bottom left: the cull's decision, rejected before picked before stars.
        Box(Modifier.align(Alignment.BottomStart).padding(BADGE_MARGIN).widthIn(max = badgeWidth)) {
            when {
                item.flag == FLAG_REJECTED -> Badge(contentDescription = stringResource(R.string.flag_rejected)) { BadgeIcon(R.drawable.ic_reject) }
                item.flag == FLAG_PICKED -> Badge(contentDescription = stringResource(R.string.flag_picked)) { BadgeIcon(R.drawable.ic_flag) }
                item.rating > 0 -> {
                    val stars = pluralStringResource(R.plurals.stars, item.rating, item.rating)
                    Badge(contentDescription = stars) { BadgeText("★${item.rating}") }
                }
            }
        }

        // Bottom right: a video and an edit; the edit is the first to go when room is short. Whether a photo has a
        // location is left to its details.
        if (roomy) {
            val video = item.kind == MediaKind.VIDEO
            if (video || edited) {
                Box(Modifier.align(Alignment.BottomEnd).padding(BADGE_MARGIN).widthIn(max = badgeWidth)) {
                    Badge {
                        if (video) BadgeIcon(R.drawable.ic_video, stringResource(R.string.video))
                        if (edited) BadgeIcon(R.drawable.ic_edit, stringResource(R.string.edited))
                    }
                }
            }
        } else if (item.kind == MediaKind.VIDEO) {
            // Even in a small cell a video says so, where the photo would otherwise look like any other.
            Box(Modifier.align(Alignment.BottomEnd).padding(BADGE_MARGIN)) {
                Badge { BadgeIcon(R.drawable.ic_video, stringResource(R.string.video)) }
            }
        }
    }
}

/** A badge over a photo: content in white on a backing dark enough to read over any picture. */
@Composable
private fun Badge(contentDescription: String? = null, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .background(OverPhoto.Scrim, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .then(if (contentDescription != null) Modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides OverPhoto.Content) { content() }
    }
}

@Composable
private fun BadgeText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall.tabular, color = OverPhoto.Content, maxLines = 1)
}

@Composable
private fun BadgeIcon(icon: Int, contentDescription: String? = null) {
    Icon(painterResource(icon), contentDescription = contentDescription, tint = OverPhoto.Content, modifier = Modifier.size(14.dp))
}

/** An edit that could not be saved: white on red with a white edge, to stand out from bright and dark photos alike. */
@Composable
private fun FailureBadge() {
    Icon(
        painterResource(R.drawable.ic_error),
        contentDescription = stringResource(R.string.edit_failed),
        tint = OverPhoto.Content,
        modifier = Modifier
            .size(22.dp)
            .background(OverPhoto.Failure, CircleShape)
            .border(1.dp, OverPhoto.Content, CircleShape)
            .padding(3.dp),
    )
}

/** Picked out or not, while photos are being picked: a filled check, or an empty ring to tap. */
@Composable
private fun SelectionMark(selected: Boolean) {
    // A white edge inside a black one, so the mark shows on any photo.
    val edged = Modifier.size(24.dp).border(2.dp, Color.Black, CircleShape).padding(1.dp).border(1.dp, Color.White, CircleShape)
    if (selected) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = stringResource(R.string.item_selected),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = edged.background(MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp),
        )
    } else {
        val notSelected = stringResource(R.string.not_selected)
        Box(edged.background(OverPhoto.Scrim, CircleShape).semantics { contentDescription = notSelected })
    }
}

private val ROOMY_CELL = 110.dp
private const val CROWDED_FONT_SCALE = 1.5f
private val BADGE_MARGIN = 4.dp
private val SELECTED_INSET = 8.dp

/** Hands several photos to whichever app the user picks. */
private fun Context.shareAll(files: List<File>) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType("image/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(Intent.createChooser(send, resources.getQuantityString(R.plurals.share_photos, files.size, files.size)))
    } catch (e: ActivityNotFoundException) {
        // No app on the device takes photos; nothing useful to do.
    }
}

/** What a right click on a photo offers: opening it, and what can be done to the photos picked, which it joins. */
@Composable
private fun contextActions(
    item: MediaItemEntity,
    state: LibraryUiState,
    onOpen: (Long) -> Unit,
    viewModel: LibraryViewModel,
): List<Pair<String, () -> Unit>> = listOfNotNull(
    stringResource(R.string.context_open) to { onOpen(item.id) },
    (stringResource(if (item.id in state.selected) R.string.context_deselect else R.string.context_select)) to { viewModel.toggle(item.id) },
    (stringResource(R.string.paste_edit) to { viewModel.pasteToSelected() }).takeIf { state.canPaste && item.kind != MediaKind.VIDEO },
    stringResource(R.string.share) to { viewModel.shareSelected() },
    stringResource(R.string.delete) to { viewModel.trashSelected() },
)
