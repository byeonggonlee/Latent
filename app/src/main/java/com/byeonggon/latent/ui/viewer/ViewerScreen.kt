package com.byeonggon.latent.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.byeonggon.latent.R
import com.byeonggon.latent.ui.common.PhotoTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
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
import com.byeonggon.latent.data.db.FLAG_PICKED
import com.byeonggon.latent.data.db.FLAG_REJECTED
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.ui.common.SIDE_PANEL_WIDTH
import com.byeonggon.latent.ui.common.SafeInsets
import com.byeonggon.latent.ui.common.ZoomableBox
import com.byeonggon.latent.ui.common.rememberZoomState
import com.byeonggon.latent.ui.common.keys
import com.byeonggon.latent.ui.common.rememberWindowLayout
import com.byeonggon.latent.ui.common.rememberMediaModel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.ui.common.localFormat
import androidx.compose.ui.res.pluralStringResource

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
    /** Whether a pair is listed as one: then deleting one deletes both. */
    stacked: Boolean = false,
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
            if (state.toEdit) context.editInAnotherApp(it) else context.share(it, MediaKind.PHOTO)
            viewModel.consumeShareFile()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val pages = remember(items, partners, itemId, item) { pagesFor(items, partners, itemId, item) }
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

    // Puts the photo on screen in the trash and moves on to the next, as a gallery does; back to the library after the last.
    // A photo of another app is deleted by the system, which asks the person first: the viewer moves on once it has.
    var afterDeletion by remember { mutableStateOf<(() -> Unit)?>(null) }
    val deletion = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) afterDeletion?.invoke()
        afterDeletion = null
        viewModel.deletionDone()
    }
    LaunchedEffect(state.deleteRequest) { state.deleteRequest?.let { deletion.launch(IntentSenderRequest.Builder(it).build()) } }
    val trashShown: (() -> Unit)? = item?.let { shown ->
        {
            val page = pages.indexOfFirst { it.id == shown.id }
            val next = pages.getOrNull(page + 1) ?: pages.getOrNull(page - 1)
            val moveOn = { if (next != null && next.id != shown.id) onItemChange(next.id) else close() }
            if (shown.origin == MediaOrigin.PHONE) {
                afterDeletion = moveOn
                viewModel.moveToTrash(partners[shown.id]?.takeIf { stacked })
            } else {
                viewModel.moveToTrash(partners[shown.id]?.takeIf { stacked })
                moveOn()
            }
        }
    }

    val window = rememberWindowLayout()
    var rating by rememberSaveable { mutableStateOf(false) }
    var showingInfo by rememberSaveable { mutableStateOf(false) }
    var confirmingRestore by remember { mutableStateOf(false) }
    val share: (Int?) -> Unit = { longSide ->
        item?.let { if (longSide == RAW_FILE) context.share(File(it.path), it.kind) else viewModel.share(longSide) }
    }
    val actions: @Composable () -> Unit = {
        if (item != null) {
            ActionBar(
                item = item,
                state = state,
                // Beside the photo the stars are always there; under it they come up when asked for.
                onRating = if (window.sidePanel) null else ({ rating = !rating }),
                onEdit = onEdit,
                onShare = share,
                onTrash = { trashShown?.invoke() },
                onInfo = if (window.sidePanel) null else ({ showingInfo = true }),
                onPaste = viewModel::pasteEdit,
                onExport = viewModel::exportRaw,
                onShowMap = { context.showOnMap(item) },
                onEditElsewhere = { viewModel.share(null, toEdit = true) },
            )
        }
    }

    val scope = rememberCoroutineScope()
    // The other file of a raw and JPEG pair is flagged along with the one in front.
    val flagPartner = item?.let { partners[it.id] }?.takeIf { stacked }
    // The video being played, if any: turning to another photo stops it.
    var playing by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(item?.id) { playing = null }
    // The photo in front, zoomed by keys as well as fingers; each photo starts out as it fits.
    val zoom = rememberZoomState(item?.id ?: -1L)
    PhotoTheme {
    Scaffold(
        contentWindowInsets = SafeInsets,
        // With a keyboard: arrows turn the page, Delete puts the photo in the trash, Escape closes,
        // plus and minus zoom and 0 shows the whole photo, 1 to 5 give stars, and P, X and U pick, reject and clear.
        modifier = Modifier.keys { event ->
            when (event.key) {
                Key.P -> viewModel.setFlag(FLAG_PICKED, flagPartner).let { true }
                Key.X -> viewModel.setFlag(FLAG_REJECTED, flagPartner).let { true }
                Key.U -> item?.flag?.let { viewModel.setFlag(it, flagPartner) }.let { true }
                Key.One, Key.NumPad1 -> viewModel.setRating(1).let { true }
                Key.Two, Key.NumPad2 -> viewModel.setRating(2).let { true }
                Key.Three, Key.NumPad3 -> viewModel.setRating(3).let { true }
                Key.Four, Key.NumPad4 -> viewModel.setRating(4).let { true }
                Key.Five, Key.NumPad5 -> viewModel.setRating(5).let { true }
                Key.Zero, Key.NumPad0 -> zoom.reset().let { true }
                Key.Equals, Key.Plus, Key.NumPadAdd -> zoom.zoomBy(ZOOM_KEY_STEP).let { true }
                Key.Minus, Key.NumPadSubtract -> zoom.zoomBy(1f / ZOOM_KEY_STEP).let { true }
                Key.DirectionRight -> scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }.let { true }
                Key.DirectionLeft -> scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }.let { true }
                Key.Delete, Key.Backspace -> trashShown?.invoke().let { trashShown != null }
                Key.Escape -> close().let { true }
                else -> false
            }
        },
        topBar = {
            TopAppBar(
                windowInsets = SafeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                title = {
                    Column {
                        Text(
                            item?.let { localFormat("MMMdjmm").format(Instant.ofEpochMilli(it.capturedAt).atZone(ZoneId.systemDefault())) }.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            item?.fileName.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    val partner = item?.let { partners[it.id] }
                    if (item != null && partner != null) PartnerSwitch(showingRaw = item.kind == MediaKind.RAW, onSwitch = { onItemChange(partner) })
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
        bottomBar = {
            if (!window.sidePanel && item != null) {
                Column(Modifier.background(Color.Black).navigationBarsPadding()) {
                    Strip(state, item, onRestore = { confirmingRestore = true })
                    AnimatedVisibility(rating) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Stars(item.rating, viewModel::setRating)
                            Flags(item.flag, onFlag = { viewModel.setFlag(it, flagPartner) })
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    actions()
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Black,
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                // While the list of pages changes, the pager can briefly ask for a page that is no longer there.
                HorizontalPager(state = pager, key = { pages.getOrNull(it)?.id ?: -1L - it }, modifier = Modifier.fillMaxSize()) { page ->
                    val shown = pages.getOrNull(page) ?: return@HorizontalPager
                    // A raw file with corrections is drawn with them; that is only worked out for the photo in front.
                    val rendered = state.rendered.takeIf { shown.id == item?.id }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ZoomableBox(resetKey = shown.id, state = if (shown.id == item?.id) zoom else rememberZoomState(shown.id)) {
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
                            if (playing == shown.id) {
                                // Plays here; if this phone cannot, the video goes to an app that can.
                                VideoPlayer(shown.path, onFailed = {
                                    playing = null
                                    context.playVideo(shown)
                                })
                            } else {
                                FilledIconButton(onClick = { playing = shown.id }, modifier = Modifier.size(64.dp)) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play), modifier = Modifier.size(40.dp))
                                }
                            }
                        }
                    }
                }
                if (state.busy) CircularProgressIndicator()
                if (state.saving) {
                    Text(
                        stringResource(R.string.saving_edit),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            // In a wide window the information goes beside the photo, where there is room for it.
            if (window.sidePanel && item != null) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight()) {
                    Column(Modifier.navigationBarsPadding()) {
                        Details(
                            state = state,
                            item = item,
                            onRate = viewModel::setRating,
                            onFlag = { viewModel.setFlag(it, flagPartner) },
                            onRestore = { confirmingRestore = true },
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        actions()
                    }
                }
            }
        }
    }

    if (showingInfo && item != null) {
        ModalBottomSheet(onDismissRequest = { showingInfo = false }) {
            Details(state, item, onRate = viewModel::setRating, onFlag = { viewModel.setFlag(it, flagPartner) }, onRestore = { confirmingRestore = true }, modifier = Modifier.navigationBarsPadding())
        }
    }
    if (confirmingRestore) {
        AlertDialog(
            onDismissRequest = { confirmingRestore = false },
            title = { Text(stringResource(R.string.restore_original_question)) },
            text = { Text(stringResource(R.string.restore_original_detail)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRestore = false
                        viewModel.restoreOriginal()
                    },
                ) { Text(stringResource(R.string.put_back)) }
            },
            dismissButton = { TextButton(onClick = { confirmingRestore = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    }
}

/**
 * What can be swiped through while [itemId] is shown: the library as listed. The other file of a listed
 * pair takes its partner's place, so the pages around it, and the page number, stay as they were;
 * a photo the library does not list right now, filtered out perhaps, is shown on its own.
 */
private fun pagesFor(items: List<MediaItemEntity>, partners: Map<Long, Long>, itemId: Long, item: MediaItemEntity?): List<MediaItemEntity> {
    if (items.any { it.id == itemId }) return items
    val place = items.indexOfFirst { it.id == partners[itemId] }
    return when {
        place < 0 -> listOfNotNull(item)
        // Until the partner itself has been read, the listed file keeps its place.
        item?.id != itemId -> items
        else -> items.toMutableList().apply { this[place] = item }
    }
}

/** Between JPEG and raw file of a pair, as one photo with two versions. */
@Composable
private fun PartnerSwitch(showingRaw: Boolean, onSwitch: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        for ((label, raw) in listOf("JPEG" to false, "RAW" to true)) {
            val chosen = raw == showingRaw
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .selectable(selected = chosen, onClick = { if (!chosen) onSwitch() }, role = Role.Tab)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Under the photo on a phone: how it was taken, and the way back to the original of an edited one. */
@Composable
private fun Strip(state: ViewerUiState, item: MediaItemEntity, onRestore: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        state.details?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.edited && item.kind != MediaKind.VIDEO) {
            TextButton(onClick = onRestore, enabled = !state.busy) { Text(stringResource(R.string.restore_original)) }
        }
    }
}

@Composable
private fun Stars(rating: Int, onRate: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (stars in 1..5) {
            Icon(
                painterResource(if (stars <= rating) R.drawable.ic_star_filled else R.drawable.ic_star),
                contentDescription = pluralStringResource(R.plurals.stars, stars, stars),
                tint = if (stars <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onRate(stars) }
                    .padding(10.dp)
                    .size(28.dp),
            )
        }
    }
}

/** Pick and reject for a cull; the one that is set is marked, and tapping it again takes it back. */
@Composable
private fun Flags(flag: Int, onFlag: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = flag == FLAG_PICKED,
            onClick = { onFlag(FLAG_PICKED) },
            label = { Text(stringResource(R.string.flag_pick)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_flag), contentDescription = null, modifier = Modifier.size(16.dp)) },
        )
        FilterChip(
            selected = flag == FLAG_REJECTED,
            onClick = { onFlag(FLAG_REJECTED) },
            label = { Text(stringResource(R.string.flag_reject)) },
            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
        )
    }
}

/** Everything known about the photo: when, how, where, and its stars. */
@Composable
private fun Details(
    state: ViewerUiState,
    item: MediaItemEntity,
    onRate: (Int) -> Unit,
    onFlag: (Int) -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            if (item.geoSource != GeoSource.NONE) TextButton(onClick = { context.showOnMap(item) }) { Text(stringResource(R.string.show_on_map)) }
        }
        Stars(item.rating, onRate, Modifier.offset(x = (-10).dp))
        Flags(item.flag, onFlag = onFlag)
        if (state.edited && item.kind != MediaKind.VIDEO) {
            OutlinedButton(onClick = onRestore, enabled = !state.busy) { Text(stringResource(R.string.restore_original)) }
        }
    }
}

/**
 * Along the bottom, as in a gallery app: stars, edit, share, delete and the rest in a menu. [onRating]
 * and [onInfo] are null where the stars and the information are on screen anyway.
 */
@Composable
private fun ActionBar(
    item: MediaItemEntity,
    state: ViewerUiState,
    onRating: (() -> Unit)?,
    onEdit: () -> Unit,
    /** Called with the long side the shared copy may have, null for full size, or [RAW_FILE] for the file as it is. */
    onShare: (Int?) -> Unit,
    onTrash: () -> Unit,
    onInfo: (() -> Unit)?,
    onPaste: () -> Unit,
    onExport: () -> Unit,
    onShowMap: () -> Unit,
    onEditElsewhere: () -> Unit,
) {
    val enabled = !state.busy
    val photo = item.kind != MediaKind.VIDEO
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
        if (onRating != null) {
            ActionButton(if (item.rating > 0) R.drawable.ic_star_filled else R.drawable.ic_star, stringResource(R.string.rating), enabled, onRating)
        }
        if (photo) ActionButton(R.drawable.ic_edit, stringResource(R.string.edit), enabled, onEdit)
        Box {
            var open by remember { mutableStateOf(false) }
            ActionButton(R.drawable.ic_share, stringResource(R.string.share), enabled) { if (item.kind == MediaKind.VIDEO) onShare(RAW_FILE) else open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val choices = when (item.kind) {
                    MediaKind.RAW -> listOf(R.string.share_jpeg_full to null, R.string.share_jpeg_2048 to 2048, R.string.share_jpeg_1080 to 1080, R.string.share_raw_file to RAW_FILE)
                    else -> listOf(R.string.share_as_is to null, R.string.share_2048 to 2048, R.string.share_1080 to 1080)
                }
                for ((label, longSide) in choices) {
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        onClick = {
                            open = false
                            onShare(longSide)
                        },
                    )
                }
            }
        }
        ActionButton(R.drawable.ic_delete, stringResource(R.string.delete), enabled, onTrash)
        Box {
            var open by remember { mutableStateOf(false) }
            ActionButton(R.drawable.ic_more_horiz, stringResource(R.string.more), enabled) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val entries = listOfNotNull(
                    onInfo?.let { Triple(stringResource(R.string.info), true, it) },
                    Triple(stringResource(R.string.paste_edit), state.canPaste, onPaste).takeIf { photo },
                    Triple(stringResource(if (item.origin == MediaOrigin.PHONE) R.string.save_copy else R.string.save_as_jpeg), true, onExport)
                        // A raw file is developed into a JPEG; an edited photo of another app, which stays as it is, into a copy.
                        .takeIf { item.kind == MediaKind.RAW || (item.origin == MediaOrigin.PHONE && state.edited && photo) },
                    Triple(stringResource(R.string.edit_elsewhere), true, onEditElsewhere).takeIf { photo },
                    Triple(stringResource(R.string.show_on_map), true, onShowMap).takeIf { item.geoSource != GeoSource.NONE },
                )
                for ((label, available, action) in entries) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        enabled = available,
                        onClick = {
                            open = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

/** An icon over its name. */
@Composable
private fun ActionButton(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun locationLabel(item: MediaItemEntity): String {
    val latitude = item.latitude
    val longitude = item.longitude
    if (latitude == null || longitude == null) return stringResource(R.string.no_location)
    val source = when (item.geoSource) {
        GeoSource.CAMERA -> stringResource(R.string.location_from_camera)
        GeoSource.TRACK -> stringResource(if (item.kind == MediaKind.VIDEO) R.string.location_from_record_video else R.string.location_from_record)
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

/** How much a press of plus or minus zooms. */
private const val ZOOM_KEY_STEP = 1.25f

/** What [onShare] is given to share a file just as it is stored. */
private const val RAW_FILE = -1

/**
 * Opens a copy of a photo in an editor the user picks, such as Snapseed or Lightroom. The editor may read
 * it but not write it: it saves its result as a photo of its own, which the library can list from the
 * phone's folders.
 */
private fun Context.editInAnotherApp(file: File) {
    val uri = try {
        FileProvider.getUriForFile(this, "$packageName.files", file)
    } catch (e: IllegalArgumentException) {
        return
    }
    val edit = Intent(Intent.ACTION_EDIT)
        .setDataAndType(uri, "image/jpeg")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launch(Intent.createChooser(edit, file.name))
}

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
    // A file the provider has no folder for, such as one on another storage volume, cannot be handed over.
    val uri = try {
        FileProvider.getUriForFile(this, "$packageName.files", File(item.path))
    } catch (e: IllegalArgumentException) {
        return
    }
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
