package com.byeonggon.latent.ui.editor

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byeonggon.latent.R
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.Framing
import com.byeonggon.latent.enhance.Mask
import com.byeonggon.latent.ui.common.PhotoTheme
import com.byeonggon.latent.ui.common.PictureBackground
import com.byeonggon.latent.ui.common.SIDE_PANEL_WIDTH
import com.byeonggon.latent.ui.common.SafeInsets
import com.byeonggon.latent.ui.common.ZoomableBox
import com.byeonggon.latent.ui.common.rememberZoomState
import com.byeonggon.latent.ui.common.keys
import com.byeonggon.latent.ui.common.rememberWindowLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

private enum class EditorTab(@StringRes val label: Int, @DrawableRes val icon: Int, val key: Key, val controls: List<Control> = emptyList()) {
    FILTERS(R.string.tool_filters, R.drawable.ic_tool_filters, Key.P),
    FRAMING(R.string.tool_crop, R.drawable.ic_tool_crop, Key.R),
    LIGHT(R.string.tool_light, R.drawable.ic_tool_light, Key.L, listOf(Control.EXPOSURE, Control.CONTRAST, Control.HIGHLIGHTS, Control.SHADOWS, Control.WHITES, Control.BLACKS)),
    COLOR(R.string.tool_color, R.drawable.ic_tool_color, Key.C, listOf(Control.TEMPERATURE, Control.TINT, Control.VIBRANCE, Control.SATURATION)),
    EFFECTS(R.string.tool_effects, R.drawable.ic_tool_effects, Key.E, listOf(Control.TEXTURE, Control.CLARITY, Control.DEHAZE, Control.VIGNETTE, Control.GRAIN, Control.GLOW)),
    LOCAL(R.string.tool_local, R.drawable.ic_tool_local, Key.M),
    SPOTS(R.string.tool_spots, R.drawable.ic_tool_spots, Key.H),
    DETAIL(R.string.tool_detail, R.drawable.ic_tool_detail, Key.D, listOf(Control.SHARPENING, Control.NOISE_REDUCTION, Control.COLOR_NOISE_REDUCTION));

    /** What of the picture has to be on screen while this tab is open. */
    val view: EditorView
        get() = when (this) {
            FRAMING, LOCAL, SPOTS -> EditorView.WHOLE
            DETAIL -> EditorView.DETAIL
            else -> EditorView.PICTURE
        }
}

/** The tools whose settings can be changed by swiping on the picture; the others have more than values. */
private val SWIPE_TABS = listOf(EditorTab.LIGHT, EditorTab.COLOR, EditorTab.EFFECTS)

private enum class LightTools(@StringRes val label: Int) { SLIDERS(R.string.tools_sliders), CURVE(R.string.tools_curve) }

private enum class ColorTools(@StringRes val label: Int) { SLIDERS(R.string.tools_sliders), MIXER(R.string.tools_mixer), GRADING(R.string.tools_grading) }

/** A shape the kept part of the picture can be held to. */
private enum class CropShape(@StringRes private val named: Int?, private val longSide: Int, private val shortSide: Int) {
    FREE(R.string.crop_free, 0, 0),
    ORIGINAL(R.string.crop_original, 0, 0),
    SQUARE(null, 1, 1),
    FOUR_BY_THREE(null, 4, 3),
    THREE_BY_TWO(null, 3, 2),
    SIXTEEN_BY_NINE(null, 16, 9);

    /** "Free" and "Original" in the phone's language, the others as their ratio, such as 4:3. */
    val label: String
        @Composable get() = named?.let { stringResource(it) } ?: "$longSide:$shortSide"

    /** Width over height of this shape for a picture of the given size, lying the way the picture does; null if any shape goes. */
    fun ratio(width: Float, height: Float): Float? = when {
        this == FREE -> null
        this == ORIGINAL -> width / height
        width >= height -> longSide.toFloat() / shortSide
        else -> shortSide.toFloat() / longSide
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(itemId: Long, onClose: () -> Unit, viewModel: EditorViewModel = viewModel()) {
    LaunchedEffect(itemId) { viewModel.open(itemId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val leave = {
        viewModel.close()
        onClose()
    }
    var confirmingDiscard by remember { mutableStateOf(false) }
    val closeUnlessChanged = {
        if (state.colourPicker != null) viewModel.cancelColourPick()
        else if (state.changed) confirmingDiscard = true else leave()
    }
    BackHandler(onBack = closeUnlessChanged)
    LaunchedEffect(state.finished) { if (state.finished) leave() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    var tab by rememberSaveable { mutableStateOf(EditorTab.LIGHT) }
    var shape by rememberSaveable { mutableStateOf(CropShape.FREE) }
    var selectedLocal by rememberSaveable { mutableIntStateOf(0) }
    var selectedSpot by rememberSaveable { mutableIntStateOf(-1) }
    var brushRadius by rememberSaveable { mutableFloatStateOf(DEFAULT_BRUSH_RADIUS) }
    var erasing by rememberSaveable { mutableStateOf(false) }
    var spotRadius by rememberSaveable { mutableFloatStateOf(DEFAULT_SPOT_RADIUS) }
    var showHistogram by rememberSaveable { mutableStateOf(false) }
    var gestureMode by rememberSaveable { mutableStateOf(false) }
    var showingVersions by rememberSaveable { mutableStateOf(false) }
    val versions by viewModel.versions.collectAsStateWithLifecycle()
    val zoom = rememberZoomState(itemId)
    LaunchedEffect(tab) { viewModel.show(tab.view) }

    /** Changes the mask of the part that is selected, as dragging it on the picture does. */
    fun setMask(mask: Mask) {
        val locals = state.edit.locals
        if (selectedLocal in locals.indices) {
            viewModel.setLocals(locals.toMutableList().apply { this[selectedLocal] = this[selectedLocal].copy(mask = mask) })
        }
    }

    val ready = state.preview != null && !state.saving && state.colourPicker == null
    val window = rememberWindowLayout()
    // Swiping on the picture is for a phone held upright; a mouse and a side panel have no use for it.
    val gestures = gestureMode && !window.sidePanel && tab in SWIPE_TABS
    val editorPanel: @Composable (Modifier) -> Unit = { modifier ->
        EditorPanel(
            modifier = modifier,
            tall = window.sidePanel,
                state = state,
                enabled = ready,
                tab = tab,
                shape = shape,
                onShape = { shape = it },
                selectedLocal = selectedLocal,
                onSelectLocal = { viewModel.cancelColourPick(); selectedLocal = it },
                selectedSpot = selectedSpot,
                onSelectSpot = { selectedSpot = it },
                spotRadius = spotRadius,
                onSpotRadius = { spotRadius = it },
                brushRadius = brushRadius,
                onBrushRadius = { brushRadius = it },
                erasing = erasing,
                onErasing = { erasing = it },
                viewModel = viewModel,
            )
    }
    PhotoTheme {
    Scaffold(
        contentWindowInsets = SafeInsets,
        // With a keyboard: Ctrl+Z undoes, Ctrl+Shift+Z or Ctrl+Y redoes, Ctrl+S saves, Escape closes;
        // a letter for each tool, J for marking clipped tones, plus, minus and 0 to zoom, and the original shown while the backslash is held.
        modifier = Modifier.keys(
            onRelease = { event -> (event.key == Key.Backslash).also { if (it) viewModel.setComparing(false) } },
        ) { event ->
            val plain = !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
            when {
                state.colourPicker != null -> when (event.key) {
                    Key.Escape -> { viewModel.cancelColourPick(); true }
                    Key.DirectionLeft -> { viewModel.moveColourCursor(-0.01f, 0f); true }
                    Key.DirectionRight -> { viewModel.moveColourCursor(0.01f, 0f); true }
                    Key.DirectionUp -> { viewModel.moveColourCursor(0f, -0.01f); true }
                    Key.DirectionDown -> { viewModel.moveColourCursor(0f, 0.01f); true }
                    else -> event.isCtrlPressed || event.isAltPressed || (plain && EditorTab.entries.any { it.key == event.key })
                }
                event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed -> viewModel.undo().let { true }
                event.isCtrlPressed && (event.key == Key.Y || (event.key == Key.Z && event.isShiftPressed)) -> viewModel.redo().let { true }
                event.isCtrlPressed && event.key == Key.S -> viewModel.save().let { true }
                event.key == Key.Escape -> closeUnlessChanged().let { true }
                plain && event.key == Key.Backslash -> viewModel.setComparing(true).let { true }
                plain && event.key == Key.J -> viewModel.setClipping(!state.clipping).let { true }
                plain && (event.key == Key.Zero || event.key == Key.NumPad0) -> zoom.reset().let { true }
                plain && (event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd) -> zoom.zoomBy(ZOOM_KEY_STEP).let { true }
                plain && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> zoom.zoomBy(1f / ZOOM_KEY_STEP).let { true }
                plain -> EditorTab.entries.firstOrNull { it.key == event.key }?.let { tab = it } != null
                else -> false
            }
        },
        topBar = {
            TopAppBar(
                windowInsets = SafeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                title = {},
                navigationIcon = {
                    IconButton(onClick = closeUnlessChanged) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close)) }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = ready && state.canUndo) {
                        Icon(painterResource(R.drawable.ic_undo), contentDescription = stringResource(R.string.undo))
                    }
                    IconButton(onClick = viewModel::redo, enabled = ready && state.canRedo) {
                        Icon(painterResource(R.drawable.ic_redo), contentDescription = stringResource(R.string.redo))
                    }
                    MoreMenu(
                        enabled = ready,
                        canPaste = state.canPaste,
                        // Beside the picture there is room to show it all the time.
                        histogram = if (window.sidePanel) null else showHistogram,
                        onHistogram = { showHistogram = it },
                        clipping = state.clipping,
                        onClipping = viewModel::setClipping,
                        onVersions = { showingVersions = true },
                        onGestures = if (window.sidePanel || gestureMode) null else {
                            {
                                if (tab !in SWIPE_TABS) tab = EditorTab.LIGHT
                                gestureMode = true
                            }
                        },
                        onReset = viewModel::reset,
                        onCopy = viewModel::copyEdit,
                        onPaste = viewModel::pasteEdit,
                    )
                    Button(
                        onClick = viewModel::save,
                        enabled = ready && state.canSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp).height(36.dp),
                    ) { Text(stringResource(R.string.done)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PictureBackground),
            )
        },
        bottomBar = {
            if (state.colourPicker != null) {
                // The picker has its own reachable Apply and Cancel controls.
            } else if (gestures) {
                GestureBar(
                    tool = stringResource(tab.label),
                    icon = tab.icon,
                    onPrevious = { tab = SWIPE_TABS[(SWIPE_TABS.indexOf(tab) + SWIPE_TABS.size - 1) % SWIPE_TABS.size] },
                    onNext = { tab = SWIPE_TABS[(SWIPE_TABS.indexOf(tab) + 1) % SWIPE_TABS.size] },
                    onSliders = { gestureMode = false },
                )
            } else if (!window.sidePanel) {
                Column {
                    editorPanel(Modifier)
                    ToolStrip(tab, onTab = { tab = it })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = PictureBackground,
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            // In a wide window the tools stand in a column on the left, as in desktop editors.
            if (window.sidePanel && state.colourPicker == null) ToolRail(tab, onTab = { tab = it })
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val picker = state.colourPicker
                if (picker != null) {
                    ColourPickerFrame(picker, viewModel::pickColour, viewModel::moveColourCursor, viewModel::applyColourPick, viewModel::cancelColourPick)
                } else state.preview?.let { preview ->
                    // The preview lags a moment behind a change of tab; each view is only drawn onto a picture made for it.
                    when {
                        tab == EditorTab.FRAMING && state.previewView == EditorView.WHOLE -> CropFrame(
                            picture = preview.asImageBitmap(),
                            framing = state.edit.framing,
                            keepShape = shape != CropShape.FREE,
                            onChange = viewModel::setFraming,
                            onDone = viewModel::commit,
                        )
                        tab == EditorTab.LOCAL && state.previewView == EditorView.WHOLE -> MaskFrame(
                            picture = preview.asImageBitmap(),
                            locals = state.edit.locals,
                            selected = selectedLocal,
                            onSelect = { viewModel.cancelColourPick(); selectedLocal = it },
                            onChange = ::setMask,
                            onDone = viewModel::commit,
                            brushRadius = brushRadius,
                            erasing = erasing,
                        )
                        tab == EditorTab.SPOTS && state.previewView == EditorView.WHOLE -> SpotFrame(
                            picture = preview.asImageBitmap(),
                            spots = state.edit.spots,
                            selected = selectedSpot,
                            onSelect = { selectedSpot = it },
                            onAdd = { x, y -> viewModel.addSpot(x, y, spotRadius) { selectedSpot = it } },
                            onMoveSource = { spot ->
                                viewModel.setSpots(state.edit.spots.toMutableList().apply { this[selectedSpot] = spot })
                            },
                            onDone = viewModel::commit,
                        )
                        tab == EditorTab.DETAIL && state.previewView == EditorView.DETAIL ->
                            DetailView(preview.asImageBitmap(), onMove = viewModel::moveDetail)
                        else -> GestureLayer(
                            active = gestures,
                            controls = tab.controls,
                            adjustments = state.edit.adjustments,
                            enabled = ready,
                            onAdjust = { control, position -> viewModel.adjust { control.setting.set(it, control.valueAt(position)) } },
                            onDone = viewModel::commit,
                        ) {
                            ZoomableBox(resetKey = itemId, onHold = viewModel::setComparing, state = zoom) {
                                // Holding the picture shows it without its corrections, the same as the button below does.
                                Image(
                                    bitmap = preview.asImageBitmap(),
                                    contentDescription = state.item?.fileName,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                if (showHistogram && !window.sidePanel && tab.view != EditorView.WHOLE) {
                    state.histogram?.let { HistogramView(it, Modifier.align(Alignment.TopStart)) }
                }
                if (state.edit.changesColors && tab.view == EditorView.PICTURE) {
                    CompareButton(onCompare = viewModel::setComparing, modifier = Modifier.align(Alignment.BottomEnd))
                }
                if (state.comparing && state.colourPicker == null) Pill(stringResource(R.string.original), Modifier.align(Alignment.TopCenter))
                if (state.colourPicker == null && (state.loading || state.saving || state.detecting != null)) CircularProgressIndicator()
            }
            // In a wide window the controls go beside the picture, the full height of the window.
            if (window.sidePanel && state.colourPicker == null) editorPanel(Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight())
        }
    }
    }

    if (showingVersions) {
        VersionsDialog(
            versions = versions,
            onKeep = viewModel::saveVersion,
            onOpen = viewModel::openVersion,
            onDelete = viewModel::deleteVersion,
            onDismiss = { showingVersions = false },
        )
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.discard_question)) },
            text = { Text(stringResource(R.string.discard_detail)) },
            confirmButton = { TextButton(onClick = leave) { Text(stringResource(R.string.close)) } },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(R.string.keep_editing)) } },
        )
    }
}

/**
 * Starting over, carrying tones and colours from one photo to another, marking clipped tones, and
 * showing the histogram over the picture; [histogram] is null where the histogram has a place of its own.
 */
@Composable
private fun MoreMenu(
    enabled: Boolean,
    canPaste: Boolean,
    histogram: Boolean?,
    onHistogram: (Boolean) -> Unit,
    clipping: Boolean,
    onClipping: (Boolean) -> Unit,
    onVersions: () -> Unit,
    onGestures: (() -> Unit)?,
    onReset: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (histogram != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (histogram) R.string.histogram_hide else R.string.histogram_show)) },
                    onClick = {
                        open = false
                        onHistogram(!histogram)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(if (clipping) R.string.clipping_hide else R.string.clipping_show)) },
                onClick = {
                    open = false
                    onClipping(!clipping)
                },
            )
            if (onGestures != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gesture_mode)) },
                    onClick = {
                        open = false
                        onGestures()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.versions)) },
                onClick = {
                    open = false
                    onVersions()
                },
            )
            val items = listOf(
                Triple(stringResource(R.string.reset), true, onReset),
                Triple(stringResource(R.string.copy_edit), true, onCopy),
                Triple(stringResource(R.string.paste_edit), canPaste, onPaste),
            )
            for ((text, available, action) in items) {
                DropdownMenuItem(
                    text = { Text(text) },
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

/** Shows the picture without its corrections for as long as it is held down. */
@Composable
private fun CompareButton(onCompare: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val holdLabel = stringResource(R.string.hold_for_original)
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        shape = CircleShape,
        modifier = modifier
            .padding(12.dp)
            .size(44.dp)
            .semantics { contentDescription = holdLabel }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onCompare(true)
                        tryAwaitRelease()
                        onCompare(false)
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_compare), contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

/** The tools along the bottom of the screen, each its icon over its name; they scroll if there are too many to fit. */
@Composable
private fun ToolStrip(tab: EditorTab, onTab: (EditorTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .selectableGroup(),
            ) {
                EditorTab.entries.forEach { entry -> ToolButton(entry, selected = entry == tab, onClick = { onTab(entry) }, width = 62.dp) }
            }
        }
    }
}

/** The tools down the left of a wide window. */
@Composable
private fun ToolRail(tab: EditorTab, onTab: (EditorTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 8.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            EditorTab.entries.forEach { entry -> ToolButton(entry, selected = entry == tab, onClick = { onTab(entry) }, width = 64.dp, showKey = true) }
        }
    }
}

@Composable
private fun ToolButton(tool: EditorTab, selected: Boolean, onClick: () -> Unit, width: Dp, showKey: Boolean = false) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(tool.icon), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(
            stringResource(tool.label),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        // Beside the picture, in a window wide enough for a keyboard, each tool shows its key.
        if (showKey) {
            Text(
                tool.letter,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp),
            )
        }
    }
}

/** The letter printed on each tool's key. */
private val EditorTab.letter: String
    get() = when (key) {
        Key.P -> "P"
        Key.R -> "R"
        Key.L -> "L"
        Key.C -> "C"
        Key.E -> "E"
        Key.M -> "M"
        Key.H -> "H"
        else -> "D"
    }

/** A short text on a dark, rounded patch that stays readable on top of any picture. */
@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        modifier = modifier.padding(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/**
 * A part of the picture at its full size. Dragging moves on to the part beside it; [onMove] is told
 * how far, in fractions of what is shown.
 */
@Composable
private fun DetailView(part: ImageBitmap, onMove: (across: Float, down: Float) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { input, amount ->
                    input.consume()
                    val shown = min(size.width, size.height).toFloat()
                    onMove(amount.x / shown, amount.y / shown)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(bitmap = part, contentDescription = stringResource(R.string.full_size_view), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Pill(stringResource(R.string.full_size_hint), Modifier.align(Alignment.TopCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorPanel(
    modifier: Modifier,
    /** The panel stands beside the picture and has the window's height, rather than a fixed one. */
    tall: Boolean,
    state: EditorUiState,
    enabled: Boolean,
    tab: EditorTab,
    shape: CropShape,
    onShape: (CropShape) -> Unit,
    selectedLocal: Int,
    onSelectLocal: (Int) -> Unit,
    selectedSpot: Int,
    onSelectSpot: (Int) -> Unit,
    spotRadius: Float,
    onSpotRadius: (Float) -> Unit,
    brushRadius: Float,
    onBrushRadius: (Float) -> Unit,
    erasing: Boolean,
    onErasing: (Boolean) -> Unit,
    viewModel: EditorViewModel,
) {
    var lightTools by rememberSaveable { mutableStateOf(LightTools.SLIDERS) }
    var colorTools by rememberSaveable { mutableStateOf(ColorTools.SLIDERS) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val height by animateDpAsState(if (expanded) EXPANDED_PANEL_HEIGHT else PANEL_HEIGHT, label = "panel height")
    val edit = state.edit
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = if (tall) RectangleShape else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().then(if (tall) Modifier.navigationBarsPadding() else Modifier)) {
            if (tall) {
                // Beside the picture there is room for the histogram all the time, as in desktop editors.
                state.histogram?.let {
                    HistogramView(it, docked = true)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            } else {
                Grabber(expanded, onExpand = { expanded = it })
            }
            Column(
                (if (tall) Modifier.weight(1f) else Modifier.height(height))
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                when (tab) {
                    EditorTab.FILTERS -> FilterPanel(
                        state = state,
                        enabled = enabled,
                        onApply = viewModel::applyFilter,
                        onAmount = viewModel::setFilterAmount,
                        onDone = viewModel::commit,
                        onSave = viewModel::saveFilter,
                        onDelete = viewModel::deleteFilter,
                        onImport = viewModel::importPresets,
                        onApplyLut = viewModel::applyLut,
                        onLutIntensity = viewModel::setLutIntensity,
                        onDeleteLut = viewModel::deleteLut,
                        onImportLuts = viewModel::importLuts,
                    )
                    EditorTab.FRAMING -> FramingControls(
                        framing = edit.framing,
                        pictureWidth = state.pictureWidth.toFloat(),
                        pictureHeight = state.pictureHeight.toFloat(),
                        shape = shape,
                        lensCorrection = edit.lensCorrection.takeIf { state.item?.kind == MediaKind.RAW && state.hasLensProfile },
                        enabled = enabled,
                        onShape = onShape,
                        onFraming = viewModel::setFraming,
                        onLensCorrection = viewModel::setLensCorrection,
                        onLevel = viewModel::levelBy,
                        onDone = viewModel::commit,
                    )
                    EditorTab.LIGHT -> {
                        ToolRow {
                            Choices(LightTools.entries, lightTools, { stringResource(it.label) }, { lightTools = it })
                            OutlinedButton(onClick = viewModel::autoAdjust, enabled = enabled) {
                                Icon(painterResource(R.drawable.ic_auto_fix), contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.auto), modifier = Modifier.padding(start = 6.dp))
                            }
                            // Edits made before auto set the sliders may still have the old correction switched on.
                            if (edit.autoEnhance) {
                                FilterChip(
                                    selected = true,
                                    onClick = { viewModel.setAutoEnhance(false) },
                                    enabled = enabled,
                                    label = { Text(stringResource(R.string.old_auto_off)) },
                                )
                            }
                        }
                        when (lightTools) {
                            LightTools.SLIDERS -> Sliders(tab.controls, edit.adjustments, enabled, viewModel)
                            LightTools.CURVE -> CurveEditor(edit.curves, enabled, viewModel::setCurves, viewModel::commit)
                        }
                    }
                    EditorTab.COLOR -> {
                        ToolRow {
                            Choices(ColorTools.entries, colorTools, { stringResource(it.label) }, { colorTools = it })
                            FilterChip(
                                selected = edit.monochrome,
                                onClick = { viewModel.setMonochrome(!edit.monochrome) },
                                enabled = enabled,
                                label = { Text(stringResource(R.string.monochrome)) },
                            )
                        }
                        when (colorTools) {
                            ColorTools.SLIDERS -> Sliders(tab.controls, edit.adjustments, enabled, viewModel)
                            ColorTools.MIXER -> MixerPanel(edit.mixer, enabled, viewModel::setMixer, viewModel::commit)
                            ColorTools.GRADING -> GradingPanel(edit.grading, enabled, viewModel::setGrading, viewModel::commit)
                        }
                    }
                    EditorTab.EFFECTS -> Sliders(tab.controls, edit.adjustments, enabled, viewModel)
                    EditorTab.LOCAL -> LocalPanel(
                        showColourSwatches = state.showColourSwatches,
                        onShowColourSwatches = viewModel::setShowColourSwatches,
                        onPickColour = { viewModel.beginColourPick(selectedLocal) },
                        locals = edit.locals,
                        selected = selectedLocal,
                        enabled = enabled,
                        onSelect = onSelectLocal,
                        onChange = viewModel::setLocals,
                        onDone = viewModel::commit,
                        brushRadius = brushRadius,
                        onBrushRadius = onBrushRadius,
                        erasing = erasing,
                        onErasing = onErasing,
                        detecting = Detecting(
                            available = state.canDetect,
                            busy = state.detecting,
                            lost = state.lostOutlines,
                            onAdd = { kind, inverted -> viewModel.addDetectedMask(kind, inverted, onAdded = onSelectLocal) },
                            onFindAgain = { viewModel.findOutlineAgain(selectedLocal) },
                        ),
                    )
                    EditorTab.SPOTS -> SpotPanel(
                        spots = edit.spots,
                        selected = selectedSpot,
                        radius = spotRadius,
                        enabled = enabled,
                        onRadius = onSpotRadius,
                        onChange = viewModel::setSpots,
                        onDone = viewModel::commit,
                        onSelect = onSelectSpot,
                    )
                    EditorTab.DETAIL -> {
                        Sliders(tab.controls, edit.adjustments, enabled, viewModel)
                        Text(
                            stringResource(R.string.detail_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A handle on top of the panel: dragging it up shows more of the panel, at the cost of the picture's room. */
@Composable
private fun Grabber(expanded: Boolean, onExpand: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, amount ->
                    change.consume()
                    if (amount < -GRAB_THRESHOLD) onExpand(true) else if (amount > GRAB_THRESHOLD) onExpand(false)
                }
            }
            .clickable(onClickLabel = stringResource(if (expanded) R.string.panel_collapse else R.string.panel_expand)) { onExpand(!expanded) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(50)))
    }
}

/** The switches at the top of a tab, side by side; they scroll if there are too many to fit. */
@Composable
private fun ToolRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** A pill of [options] side by side, of which one is [selected]; for use inside a [ToolRow]. */
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val chosen = option == selected
            Text(
                label(option),
                style = MaterialTheme.typography.labelLarge,
                color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .selectable(selected = chosen, onClick = { onSelect(option) }, role = Role.Tab)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun Sliders(controls: List<Control>, adjustments: Adjustments, enabled: Boolean, viewModel: EditorViewModel) {
    controls.forEach { control ->
        val value = control.setting.get(adjustments)
        SettingSlider(
            label = stringResource(control.label),
            value = value,
            range = control.range,
            valueText = control.describe(value),
            enabled = enabled,
            onChange = { position -> viewModel.adjust { control.setting.set(it, control.valueAt(position)) } },
            onDone = viewModel::commit,
            // Exposure is typed in stops, the others as the percentage the slider shows.
            parseEntry = { text -> readEntry(text, if (control == Control.EXPOSURE) 1f else 0.01f, control.range) },
        )
    }
}

/** Levelling, quarter turns, the lens and the shape of the kept part; the part itself is marked on the picture. */
@Composable
private fun FramingControls(
    framing: Framing,
    pictureWidth: Float,
    pictureHeight: Float,
    shape: CropShape,
    /** Null for pictures whose lens cannot be corrected for. */
    lensCorrection: Boolean?,
    enabled: Boolean,
    onShape: (CropShape) -> Unit,
    onFraming: (Framing) -> Unit,
    onLensCorrection: (Boolean) -> Unit,
    /** Works out how far to turn the picture to level it, and hands that over. */
    onLevel: ((Float) -> Unit) -> Unit,
    onDone: () -> Unit,
) {
    val sideways = framing.quarterTurns % 2 != 0
    val turnedWidth = if (sideways) pictureHeight else pictureWidth
    val turnedHeight = if (sideways) pictureWidth else pictureHeight

    /** The shape of the part that is kept now: its width over its height, in pixels. */
    val kept = framing.width * turnedWidth / (framing.height * turnedHeight)

    // Levelling and tilting take the corners away, so the kept part starts over as the largest of its shape.
    fun reframed(changed: Framing) = onFraming(changed.largestOf(kept, pictureWidth, pictureHeight))

    // Turning, flipping and the lens first: in a short panel they are what is seen without scrolling.
    ToolRow {
        OutlinedButton(
            onClick = {
                onFraming(framing.turnedClockwise())
                onDone()
            },
            enabled = enabled,
        ) { Text(stringResource(R.string.rotate_90)) }
        OutlinedButton(
            onClick = {
                onFraming(framing.mirrored())
                onDone()
            },
            enabled = enabled,
        ) { Text(stringResource(R.string.flip_horizontal)) }
        if (lensCorrection != null) {
            FilterChip(
                selected = lensCorrection,
                onClick = { onLensCorrection(!lensCorrection) },
                enabled = enabled,
                label = { Text(stringResource(R.string.lens_correction)) },
            )
        }
    }
    ToolRow {
        CropShape.entries.forEach { entry ->
            FilterChip(
                selected = shape == entry,
                onClick = {
                    onShape(entry)
                    entry.ratio(turnedWidth, turnedHeight)?.let { onFraming(framing.largestOf(it, pictureWidth, pictureHeight)) }
                    onDone()
                },
                enabled = enabled,
                label = { Text(entry.label) },
            )
        }
    }
    ToolRow {
        OutlinedButton(
            onClick = {
                onLevel { angle ->
                    reframed(framing.copy(angle = angle))
                    onDone()
                }
            },
            enabled = enabled,
        ) { Text(stringResource(R.string.auto_level)) }
    }
    SettingSlider(
        label = stringResource(R.string.straighten),
        value = framing.angle,
        range = -MAX_ANGLE..MAX_ANGLE,
        valueText = if (framing.angle == 0f) "0" else String.format(Locale.US, "%+.1f°", framing.angle),
        enabled = enabled,
        onChange = { position ->
            val angle = if (abs(position) < ANGLE_SNAP) 0f else (position * 10).roundToInt() / 10f
            if (angle != framing.angle) reframed(framing.copy(angle = angle))
        },
        onDone = onDone,
        parseEntry = { text -> readEntry(text, 1f, -MAX_ANGLE..MAX_ANGLE) },
    )
    SettingSlider(
        label = stringResource(R.string.vertical_perspective),
        value = framing.vertical,
        range = -1f..1f,
        valueText = if (framing.vertical == 0f) "0" else signed(framing.vertical * 100),
        enabled = enabled,
        onChange = { position -> snapped(position, 1f, 100).let { if (it != framing.vertical) reframed(framing.copy(vertical = it)) } },
        onDone = onDone,
        parseEntry = { text -> readEntry(text, 0.01f, -1f..1f) },
    )
    SettingSlider(
        label = stringResource(R.string.horizontal_perspective),
        value = framing.horizontal,
        range = -1f..1f,
        valueText = if (framing.horizontal == 0f) "0" else signed(framing.horizontal * 100),
        enabled = enabled,
        onChange = { position -> snapped(position, 1f, 100).let { if (it != framing.horizontal) reframed(framing.copy(horizontal = it)) } },
        onDone = onDone,
        parseEntry = { text -> readEntry(text, 0.01f, -1f..1f) },
    )
}

private val PANEL_HEIGHT = 268.dp
private val EXPANDED_PANEL_HEIGHT = 420.dp
private const val GRAB_THRESHOLD = 4f
/** How much a press of plus or minus zooms. */
private const val ZOOM_KEY_STEP = 1.25f

private const val MAX_ANGLE = 30f
private const val ANGLE_SNAP = 0.3f
