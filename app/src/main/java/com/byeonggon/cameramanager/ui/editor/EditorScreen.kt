package com.byeonggon.cameramanager.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byeonggon.cameramanager.R
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.enhance.Adjustments
import com.byeonggon.cameramanager.enhance.Framing
import com.byeonggon.cameramanager.enhance.Mask
import com.byeonggon.cameramanager.ui.common.ZoomableBox
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private enum class EditorTab(val label: String, val controls: List<Control> = emptyList()) {
    FILTERS("필터"),
    FRAMING("자르기"),
    LIGHT("밝기", listOf(Control.EXPOSURE, Control.CONTRAST, Control.HIGHLIGHTS, Control.SHADOWS, Control.WHITES, Control.BLACKS)),
    COLOR("색상", listOf(Control.TEMPERATURE, Control.TINT, Control.VIBRANCE, Control.SATURATION)),
    EFFECTS("효과", listOf(Control.TEXTURE, Control.CLARITY, Control.DEHAZE, Control.VIGNETTE, Control.GRAIN, Control.GLOW)),
    LOCAL("부분"),
    SPOTS("잡티"),
    DETAIL("세부", listOf(Control.SHARPENING, Control.NOISE_REDUCTION, Control.COLOR_NOISE_REDUCTION));

    /** What of the picture has to be on screen while this tab is open. */
    val view: EditorView
        get() = when (this) {
            FRAMING, LOCAL, SPOTS -> EditorView.WHOLE
            DETAIL -> EditorView.DETAIL
            else -> EditorView.PICTURE
        }
}

private enum class LightTools(val label: String) { SLIDERS("기본"), CURVE("곡선") }

private enum class ColorTools(val label: String) { SLIDERS("기본"), MIXER("색상 혼합"), GRADING("색상 등급") }

/** A shape the kept part of the picture can be held to. */
private enum class CropShape(val label: String, private val longSide: Int, private val shortSide: Int) {
    FREE("자유", 0, 0),
    ORIGINAL("원본", 0, 0),
    SQUARE("1:1", 1, 1),
    FOUR_BY_THREE("4:3", 4, 3),
    THREE_BY_TWO("3:2", 3, 2),
    SIXTEEN_BY_NINE("16:9", 16, 9);

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
    val closeUnlessChanged = { if (state.changed) confirmingDiscard = true else leave() }
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
    LaunchedEffect(tab) { viewModel.show(tab.view) }

    /** Changes the mask of the part that is selected, as dragging it on the picture does. */
    fun setMask(mask: Mask) {
        val locals = state.edit.locals
        if (selectedLocal in locals.indices) {
            viewModel.setLocals(locals.toMutableList().apply { this[selectedLocal] = this[selectedLocal].copy(mask = mask) })
        }
    }

    val ready = state.preview != null && !state.saving
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("보정") },
                navigationIcon = {
                    IconButton(onClick = closeUnlessChanged) { Icon(Icons.Default.Close, contentDescription = "닫기") }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = ready && state.canUndo) {
                        Icon(painterResource(R.drawable.ic_undo), contentDescription = "실행 취소")
                    }
                    IconButton(onClick = viewModel::redo, enabled = ready && state.canRedo) {
                        Icon(painterResource(R.drawable.ic_redo), contentDescription = "다시 실행")
                    }
                    TextButton(onClick = viewModel::save, enabled = ready && state.canSave) { Text("저장") }
                    MoreMenu(
                        enabled = ready,
                        canPaste = state.canPaste,
                        onReset = viewModel::reset,
                        onCopy = viewModel::copyEdit,
                        onPaste = viewModel::pasteEdit,
                    )
                },
            )
        },
        bottomBar = {
            EditorPanel(
                state = state,
                enabled = ready,
                tab = tab,
                onTab = { tab = it },
                shape = shape,
                onShape = { shape = it },
                selectedLocal = selectedLocal,
                onSelectLocal = { selectedLocal = it },
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
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Black,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            state.preview?.let { preview ->
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
                        onSelect = { selectedLocal = it },
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
                    else -> ZoomableBox(resetKey = itemId, onHold = viewModel::setComparing) {
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
            if (tab.view != EditorView.WHOLE) {
                state.histogram?.let { HistogramView(it, Modifier.align(Alignment.TopStart)) }
            }
            if (state.edit.changesColors && tab.view == EditorView.PICTURE) {
                CompareButton(onCompare = viewModel::setComparing, modifier = Modifier.align(Alignment.BottomEnd))
            }
            if (state.comparing) Pill("원본", Modifier.align(Alignment.TopCenter))
            if (state.loading || state.saving) CircularProgressIndicator()
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("저장하지 않고 닫을까요?") },
            text = { Text("바꾼 보정 값이 사라집니다.") },
            confirmButton = { TextButton(onClick = leave) { Text("닫기") } },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text("계속 보정") } },
        )
    }
}

/** Starting over, and carrying tones and colours from one photo to another. */
@Composable
private fun MoreMenu(enabled: Boolean, canPaste: Boolean, onReset: () -> Unit, onCopy: () -> Unit, onPaste: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, contentDescription = "더 보기")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val items = listOf(
                Triple("초기화", true, onReset),
                Triple("보정 값 복사", true, onCopy),
                Triple("보정 값 붙여넣기", canPaste, onPaste),
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
    Pill(
        "누르고 있으면 원본",
        modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    onCompare(true)
                    tryAwaitRelease()
                    onCompare(false)
                },
            )
        },
    )
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
        Image(bitmap = part, contentDescription = "100% 보기", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Pill("100% · 끌어서 이동", Modifier.align(Alignment.TopCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorPanel(
    state: EditorUiState,
    enabled: Boolean,
    tab: EditorTab,
    onTab: (EditorTab) -> Unit,
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
    val edit = state.edit
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            SecondaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                EditorTab.entries.forEach { entry ->
                    Tab(selected = tab == entry, onClick = { onTab(entry) }, text = { Text(entry.label) })
                }
            }
            Column(Modifier.height(PANEL_HEIGHT).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                when (tab) {
                    EditorTab.FILTERS -> FilterPanel(
                        state = state,
                        enabled = enabled,
                        onApply = viewModel::applyFilter,
                        onAmount = viewModel::setFilterAmount,
                        onDone = viewModel::commit,
                        onSave = viewModel::saveFilter,
                        onDelete = viewModel::deleteFilter,
                    )
                    EditorTab.FRAMING -> FramingControls(
                        framing = edit.framing,
                        pictureWidth = state.pictureWidth.toFloat(),
                        pictureHeight = state.pictureHeight.toFloat(),
                        shape = shape,
                        lensCorrection = edit.lensCorrection.takeIf { state.item?.kind == MediaKind.RAW },
                        enabled = enabled,
                        onShape = onShape,
                        onFraming = viewModel::setFraming,
                        onLensCorrection = viewModel::setLensCorrection,
                        onDone = viewModel::commit,
                    )
                    EditorTab.LIGHT -> {
                        ToolRow {
                            Choices(LightTools.entries, lightTools, { it.label }, { lightTools = it })
                            FilterChip(
                                selected = edit.autoEnhance,
                                onClick = { viewModel.setAutoEnhance(!edit.autoEnhance) },
                                enabled = enabled,
                                label = { Text("자동 보정") },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_auto_fix), contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                            )
                        }
                        when (lightTools) {
                            LightTools.SLIDERS -> Sliders(tab.controls, edit.adjustments, enabled, viewModel)
                            LightTools.CURVE -> CurveEditor(edit.curves, enabled, viewModel::setCurves, viewModel::commit)
                        }
                    }
                    EditorTab.COLOR -> {
                        ToolRow {
                            Choices(ColorTools.entries, colorTools, { it.label }, { colorTools = it })
                            FilterChip(
                                selected = edit.monochrome,
                                onClick = { viewModel.setMonochrome(!edit.monochrome) },
                                enabled = enabled,
                                label = { Text("흑백") },
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
                            "위 사진은 100% 크기의 일부분입니다. 선명도와 노이즈는 이 크기에서 보입니다.",
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

/** One chip for each of [options], of which one is [selected]; for use inside a [ToolRow]. */
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    options.forEach { option ->
        FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
    }
}

@Composable
private fun Sliders(controls: List<Control>, adjustments: Adjustments, enabled: Boolean, viewModel: EditorViewModel) {
    controls.forEach { control ->
        val value = control.setting.get(adjustments)
        SettingSlider(
            label = control.label,
            value = value,
            range = control.range,
            valueText = control.describe(value),
            enabled = enabled,
            onChange = { position -> viewModel.adjust { control.setting.set(it, control.valueAt(position)) } },
            onDone = viewModel::commit,
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
    onDone: () -> Unit,
) {
    val sideways = framing.quarterTurns % 2 != 0
    val turnedWidth = if (sideways) pictureHeight else pictureWidth
    val turnedHeight = if (sideways) pictureWidth else pictureHeight

    /** The shape of the part that is kept now: its width over its height, in pixels. */
    val kept = framing.width * turnedWidth / (framing.height * turnedHeight)

    // Levelling and tilting take the corners away, so the kept part starts over as the largest of its shape.
    fun reframed(changed: Framing) = onFraming(changed.largestOf(kept, pictureWidth, pictureHeight))

    SettingSlider(
        label = "수평",
        value = framing.angle,
        range = -MAX_ANGLE..MAX_ANGLE,
        valueText = if (framing.angle == 0f) "0" else String.format(Locale.US, "%+.1f°", framing.angle),
        enabled = enabled,
        onChange = { position ->
            val angle = if (abs(position) < ANGLE_SNAP) 0f else (position * 10).roundToInt() / 10f
            if (angle != framing.angle) reframed(framing.copy(angle = angle))
        },
        onDone = onDone,
    )
    SettingSlider(
        label = "수직 원근",
        value = framing.vertical,
        range = -1f..1f,
        valueText = if (framing.vertical == 0f) "0" else signed(framing.vertical * 100),
        enabled = enabled,
        onChange = { position -> snapped(position, 1f, 100).let { if (it != framing.vertical) reframed(framing.copy(vertical = it)) } },
        onDone = onDone,
    )
    SettingSlider(
        label = "수평 원근",
        value = framing.horizontal,
        range = -1f..1f,
        valueText = if (framing.horizontal == 0f) "0" else signed(framing.horizontal * 100),
        enabled = enabled,
        onChange = { position -> snapped(position, 1f, 100).let { if (it != framing.horizontal) reframed(framing.copy(horizontal = it)) } },
        onDone = onDone,
    )
    ToolRow {
        OutlinedButton(
            onClick = {
                onFraming(framing.turnedClockwise())
                onDone()
            },
            enabled = enabled,
        ) { Text("90° 회전") }
        OutlinedButton(
            onClick = {
                onFraming(framing.mirrored())
                onDone()
            },
            enabled = enabled,
        ) { Text("좌우 반전") }
        if (lensCorrection != null) {
            FilterChip(
                selected = lensCorrection,
                onClick = { onLensCorrection(!lensCorrection) },
                enabled = enabled,
                label = { Text("렌즈 보정") },
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
}

private val PANEL_HEIGHT = 268.dp
private const val MAX_ANGLE = 30f
private const val ANGLE_SNAP = 0.3f
