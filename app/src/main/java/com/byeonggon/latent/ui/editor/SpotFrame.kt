package com.byeonggon.latent.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.enhance.Spot
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.R

/** The radius of a new spot, as a fraction of the picture's long side, unless another size is chosen. */
internal const val DEFAULT_SPOT_RADIUS = 0.02f

/**
 * Shows the whole [picture] with the blemishes taken out of it marked: each as a circle, and the
 * [selected] one also with the circle it is patched from. Tapping the picture takes out what is
 * there, tapping a circle selects it, and the patch of the selected one can be dragged elsewhere.
 */
@Composable
internal fun SpotFrame(
    picture: ImageBitmap,
    spots: List<Spot>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: (x: Float, y: Float) -> Unit,
    onMoveSource: (Spot) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val areaLabel = stringResource(R.string.spot_area)
    val current by rememberUpdatedState(spots)
    val chosen by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val add by rememberUpdatedState(onAdd)
    val move by rememberUpdatedState(onMoveSource)
    val done by rememberUpdatedState(onDone)
    var dragging by remember { mutableStateOf(false) }
    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = areaLabel }
            .pointerInput(picture.width, picture.height) {
                fun layout() = MaskLayout(Size(size.width.toFloat(), size.height.toFloat()), MARGIN.toPx(), picture.width.toFloat(), picture.height.toFloat())
                detectTapGestures { position ->
                    val layout = layout()
                    val longSide = max(layout.picture.width, layout.picture.height)
                    val touched = current.indices.lastOrNull { index ->
                        val spot = current[index]
                        (position - layout.onCanvas(spot.x, spot.y)).getDistance() <= max(spot.radius * longSide, REACH.toPx())
                    }
                    if (touched != null) {
                        select(touched)
                    } else if (layout.picture.contains(position)) {
                        add((position.x - layout.picture.left) / layout.picture.width, (position.y - layout.picture.top) / layout.picture.height)
                    }
                }
            }
            .pointerInput(picture.width, picture.height) {
                fun layout() = MaskLayout(Size(size.width.toFloat(), size.height.toFloat()), MARGIN.toPx(), picture.width.toFloat(), picture.height.toFloat())
                detectDragGestures(
                    onDragStart = { position ->
                        val spot = current.getOrNull(chosen)
                        val layout = layout()
                        dragging = spot != null && (position - layout.onCanvas(spot.fromX, spot.fromY)).getDistance() <=
                            max(spot.radius * max(layout.picture.width, layout.picture.height), REACH.toPx())
                    },
                    onDragEnd = {
                        if (dragging) done()
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                    onDrag = { input, amount ->
                        val spot = current.getOrNull(chosen)
                        if (dragging && spot != null) {
                            input.consume()
                            val layout = layout()
                            move(
                                spot.copy(
                                    fromX = (spot.fromX + amount.x / layout.picture.width).coerceIn(0f, 1f),
                                    fromY = (spot.fromY + amount.y / layout.picture.height).coerceIn(0f, 1f),
                                ),
                            )
                        }
                    },
                )
            },
    ) {
        val layout = MaskLayout(size, MARGIN.toPx(), picture.width.toFloat(), picture.height.toFloat())
        drawImage(
            picture,
            dstOffset = IntOffset(layout.picture.left.roundToInt(), layout.picture.top.roundToInt()),
            dstSize = IntSize(layout.picture.width.roundToInt(), layout.picture.height.roundToInt()),
        )
        val longSide = max(layout.picture.width, layout.picture.height)
        val outline = Stroke(1.5.dp.toPx())
        spots.forEachIndexed { index, spot ->
            val radius = spot.radius * longSide
            val at = layout.onCanvas(spot.x, spot.y)
            if (index == selected) {
                val from = layout.onCanvas(spot.fromX, spot.fromY)
                drawLine(Color.White.copy(alpha = 0.7f), at, from, strokeWidth = 1.dp.toPx())
                drawCircle(Color.White, radius, from, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                drawCircle(Color.White, radius, at, style = Stroke(2.5.dp.toPx()))
            } else {
                drawCircle(Color.White.copy(alpha = 0.75f), radius, at, style = outline)
            }
            drawCircle(Color.Black.copy(alpha = 0.35f), radius + 1.dp.toPx(), at, style = Stroke(1.dp.toPx()))
        }
    }
}

/** The size of new spots, and a way to take spots back out. */
@Composable
internal fun SpotPanel(
    spots: List<Spot>,
    selected: Int,
    radius: Float,
    enabled: Boolean,
    onRadius: (Float) -> Unit,
    onChange: (List<Spot>) -> Unit,
    onDone: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    SettingSlider(
        label = stringResource(R.string.size),
        value = radius,
        range = SMALLEST_SPOT..LARGEST_SPOT,
        valueText = (radius * 1000).roundToInt().toString(),
        enabled = enabled,
        onChange = { onRadius((it * 1000).roundToInt() / 1000f) },
        onDone = {},
        untouched = DEFAULT_SPOT_RADIUS,
    )
    Row(modifier = Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                onChange(spots.filterIndexed { index, _ -> index != selected })
                onDone()
                onSelect((selected - 1).coerceAtLeast(0))
            },
            enabled = enabled && selected in spots.indices,
        ) { Text(stringResource(R.string.undo_spot)) }
        TextButton(
            onClick = {
                onChange(emptyList())
                onDone()
            },
            enabled = enabled && spots.isNotEmpty(),
        ) { Text(stringResource(R.string.undo_all_spots)) }
    }
    Text(
        stringResource(R.string.spots_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private val MARGIN = 20.dp
private val REACH = 20.dp
private const val SMALLEST_SPOT = 0.005f
private const val LARGEST_SPOT = 0.08f
