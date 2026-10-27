package com.byeonggon.latent.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.R
import com.byeonggon.latent.ui.common.PictureBackground
import com.byeonggon.latent.ui.common.SIDE_PANEL_WIDTH
import com.byeonggon.latent.ui.common.keys
import com.byeonggon.latent.ui.common.rememberWindowLayout
import kotlin.math.roundToInt

/** A temporary colour choice; no edit is changed until Apply. The preview and cursor use the whole upright photo. */
data class ColourPickerState(
    val preparing: Boolean = true,
    val preview: Bitmap? = null,
    val point: Pair<Float, Float>? = null,
    val hue: Float? = null,
    val colour: Int? = null,
    val invalid: Boolean = false,
)

/** Uses the same fitted upright image as MaskFrame, with checked inverse coordinates. */
internal class ColourPickerLayout(canvas: Size, margin: Float, width: Float, height: Float) {
    private val layout = MaskLayout(canvas, margin, width, height)
    val picture: Rect get() = layout.picture
    fun onCanvas(x: Float, y: Float): Offset = layout.onCanvas(x, y)

    fun pointAt(point: Offset): Pair<Float, Float>? {
        if (picture.width <= 0f || picture.height <= 0f || !point.x.isFinite() || !point.y.isFinite()) return null
        if (point.x < picture.left || point.x > picture.right || point.y < picture.top || point.y > picture.bottom) return null
        return layout.inPicture(point)
    }

    fun magnifierAt(point: Pair<Float, Float>, edge: Float, gap: Float): Rect? {
        val side = minOf(edge, minOf(picture.width, picture.height) * 0.4f)
        if (side <= 0f) return null
        val at = onCanvas(point.first, point.second)
        fun fitted(left: Float, top: Float): Rect = Rect(
            Offset(left.coerceIn(picture.left, picture.right - side), top.coerceIn(picture.top, picture.bottom - side)), Size(side, side),
        )
        val candidates = listOf(
            fitted(at.x - side / 2, at.y - gap - side), fitted(at.x - side / 2, at.y + gap),
            fitted(at.x - gap - side, at.y - side / 2), fitted(at.x + gap, at.y - side / 2),
        )
        return candidates.firstOrNull { !it.inflate(gap / 2).contains(at) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColourPickerFrame(
    state: ColourPickerState,
    onPick: (Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = rememberWindowLayout()
    val applyFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    var dragging by remember { mutableStateOf(false) }
    val value = state.hue?.let { stringResource(R.string.colour_hue_value, it.roundToInt()) }
    val invalid = stringResource(R.string.colour_pick_invalid)
    val preparing = stringResource(R.string.colour_pick_preparing)
    val announcement = if (state.preparing) preparing else if (state.invalid) invalid else value.orEmpty()
    var spoken by remember { mutableStateOf(announcement) }
    LaunchedEffect(announcement, dragging) { if (!dragging) spoken = announcement }
    val label = stringResource(R.string.colour_pick_area)
    val left = stringResource(R.string.colour_pick_left)
    val right = stringResource(R.string.colour_pick_right)
    val up = stringResource(R.string.colour_pick_up)
    val down = stringResource(R.string.colour_pick_down)
    val pick by rememberUpdatedState(onPick)
    val move by rememberUpdatedState(onMove)
    val preview = state.preview
    val image = remember(preview) { preview?.asImageBitmap() }
    val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
        Box(canvasModifier.background(PictureBackground), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.fillMaxSize()
                    .semantics {
                        contentDescription = label
                        stateDescription = spoken
                        liveRegion = LiveRegionMode.Polite
                        if (!state.preparing) customActions = listOf(
                            CustomAccessibilityAction(left) { move(-0.01f, 0f); true }, CustomAccessibilityAction(right) { move(0.01f, 0f); true },
                            CustomAccessibilityAction(up) { move(0f, -0.01f); true }, CustomAccessibilityAction(down) { move(0f, 0.01f); true },
                        )
                    }
                    .keys(onRelease = { event ->
                        if (event.key == Key.Enter || event.key == Key.DirectionCenter) {
                            if (!state.preparing && state.hue != null) applyFocus.requestFocus() else cancelFocus.requestFocus()
                            true
                        } else false
                    }) { event ->
                        when (event.key) {
                            Key.Enter, Key.DirectionCenter -> true
                            Key.DirectionLeft -> { move(-0.01f, 0f); true }
                            Key.DirectionRight -> { move(0.01f, 0f); true }
                            Key.DirectionUp -> { move(0f, -0.01f); true }
                            Key.DirectionDown -> { move(0f, 0.01f); true }
                            else -> false
                        }
                    }
                    .pointerInput(preview) {
                        detectTapGestures(onPress = { position ->
                            if (preview != null) ColourPickerLayout(Size(size.width.toFloat(), size.height.toFloat()), 20.dp.toPx(), preview.width.toFloat(), preview.height.toFloat())
                                .pointAt(position)?.let { (x, y) -> pick(x, y) }
                            tryAwaitRelease()
                        })
                    }
                    .pointerInput(preview) {
                        fun sample(position: Offset) {
                            if (preview != null) ColourPickerLayout(Size(size.width.toFloat(), size.height.toFloat()), 20.dp.toPx(), preview.width.toFloat(), preview.height.toFloat())
                                .pointAt(position)?.let { (x, y) -> pick(x, y) }
                        }
                        detectDragGestures(
                            onDragStart = { dragging = true; sample(it) },
                            onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                            onDrag = { change, _ -> change.consume(); sample(change.position) },
                        )
                    },
            ) {
                if (image != null) {
                    val layout = ColourPickerLayout(size, 20.dp.toPx(), image.width.toFloat(), image.height.toFloat())
                    val rect = layout.picture
                    if (rect.width > 0 && rect.height > 0) {
                        drawImage(image, dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()), dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
                        state.point?.let { point ->
                            crosshair(layout.onCanvas(point.first, point.second), 10.dp.toPx())
                            layout.magnifierAt(point, 96.dp.toPx(), 24.dp.toPx())?.let { lens ->
                                val crop = (lens.width * image.width / rect.width / 3f).roundToInt().coerceIn(1, minOf(image.width, image.height))
                                val x = (point.first * image.width).toInt().coerceAtMost(image.width - 1)
                                val y = (point.second * image.height).toInt().coerceAtMost(image.height - 1)
                                val fromX = (x - crop / 2).coerceIn(0, image.width - crop)
                                val fromY = (y - crop / 2).coerceIn(0, image.height - crop)
                                clipPath(Path().apply { addOval(lens) }) {
                                    drawImage(image, srcOffset = IntOffset(fromX, fromY), srcSize = IntSize(crop, crop), dstOffset = IntOffset(lens.left.roundToInt(), lens.top.roundToInt()), dstSize = IntSize(lens.width.roundToInt(), lens.height.roundToInt()))
                                    crosshair(Offset(lens.left + (x - fromX + 0.5f) / crop * lens.width, lens.top + (y - fromY + 0.5f) / crop * lens.height), 8.dp.toPx())
                                }
                                drawCircle(Color.Black, lens.width / 2, lens.center, style = Stroke(6.dp.toPx()))
                                drawCircle(Color.White, lens.width / 2, lens.center, style = Stroke(2.dp.toPx()))
                            }
                        }
                    }
                }
            }
            if (state.preparing) CircularProgressIndicator(Modifier.semantics { contentDescription = preparing })
        }
    }
    val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
        Surface(modifier = controlsModifier, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Column(Modifier.weight(1f, fill = window.sidePanel).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.preparing) preparing else stringResource(R.string.colour_pick_hint), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.colour_pick_keys), style = MaterialTheme.typography.bodySmall)
                    if (state.invalid) Text(invalid, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                }
                if (value != null) Text(value, style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = onApply, enabled = !state.preparing && state.hue != null, modifier = Modifier.focusProperties { canFocus = true }.focusRequester(applyFocus).heightIn(min = 48.dp)) { Text(stringResource(R.string.colour_pick_apply)) }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.focusProperties { canFocus = true }.focusRequester(cancelFocus).heightIn(min = 48.dp)) { Text(stringResource(R.string.colour_pick_cancel)) }
                }
            }
        }
    }
    if (window.sidePanel) {
        Row(modifier.fillMaxSize()) {
            canvas(Modifier.weight(1f).fillMaxHeight())
            controls(Modifier.width(minOf(SIDE_PANEL_WIDTH, (window.widthDp * 0.45f).dp)).fillMaxHeight())
        }
    } else {
        Column(modifier.fillMaxSize()) {
            canvas(Modifier.weight(1f).fillMaxWidth())
            controls(Modifier.fillMaxWidth().heightIn(max = 260.dp))
        }
    }
}

private fun DrawScope.crosshair(at: Offset, reach: Float) {
    for ((colour, width) in listOf(Color.Black to 5.dp.toPx(), Color.White to 2.dp.toPx())) {
        drawLine(colour, Offset(at.x - reach, at.y), Offset(at.x + reach, at.y), width)
        drawLine(colour, Offset(at.x, at.y - reach), Offset(at.x, at.y + reach), width)
    }
}
