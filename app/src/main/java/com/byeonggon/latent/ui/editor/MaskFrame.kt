package com.byeonggon.latent.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.enhance.BrushRaster
import com.byeonggon.latent.enhance.BrushStroke
import com.byeonggon.latent.enhance.LocalEdit
import com.byeonggon.latent.enhance.Mask
import com.byeonggon.latent.enhance.MaskShape
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.R

/** What of a mask a finger has hold of. */
internal enum class MaskGrip {
    /** Where the mask is full; for an ellipse, its centre, which takes the whole mask along. */
    START,

    /** Where the mask has faded out. */
    END,
}

/**
 * Where things are when the whole picture is shown with masks marked on it: the picture fitted into
 * the canvas within a margin, and positions as fractions of the picture on one side and on the canvas
 * on the other.
 */
internal class MaskLayout(canvas: Size, margin: Float, pictureWidth: Float, pictureHeight: Float) {
    private val scale = min((canvas.width - 2 * margin) / pictureWidth, (canvas.height - 2 * margin) / pictureHeight).coerceAtLeast(0f)

    /** The picture on the canvas. */
    val picture = Rect(
        offset = Offset((canvas.width - pictureWidth * scale) / 2, (canvas.height - pictureHeight * scale) / 2),
        size = Size(pictureWidth * scale, pictureHeight * scale),
    )

    fun onCanvas(x: Float, y: Float) = Offset(picture.left + x * picture.width, picture.top + y * picture.height)

    /** Where [position] on the canvas is on the picture, as fractions of its width and height; outside it, beyond 0 to 1. */
    fun inPicture(position: Offset) = (position.x - picture.left) / picture.width to (position.y - picture.top) / picture.height

    fun startOf(mask: Mask) = onCanvas(mask.startX, mask.startY)

    fun endOf(mask: Mask) = onCanvas(mask.endX, mask.endY)

    /** What of [mask] a finger at [position] takes hold of, reaching [reach] far; the nearer of the two if both are in reach. */
    fun gripAt(mask: Mask, position: Offset, reach: Float): MaskGrip? {
        // The whole picture has nothing to take hold of.
        if (mask.shape == MaskShape.EVERYWHERE) return null
        val toStart = (position - startOf(mask)).getDistance()
        val toEnd = (position - endOf(mask)).getDistance()
        return when {
            toStart > reach && toEnd > reach -> null
            toStart <= toEnd -> MaskGrip.START
            else -> MaskGrip.END
        }
    }

    /** [mask] after [grip] was dragged by [amount] on the canvas. Masks may reach a little way out of the picture. */
    fun dragged(mask: Mask, grip: MaskGrip, amount: Offset): Mask {
        val across = amount.x / picture.width
        val down = amount.y / picture.height
        fun moved(value: Float, by: Float) = (value + by).coerceIn(-OUTSIDE, 1f + OUTSIDE)
        return when {
            grip == MaskGrip.END -> mask.copy(endX = moved(mask.endX, across), endY = moved(mask.endY, down))
            mask.shape == MaskShape.RADIAL -> {
                // The centre keeps its ellipse: both points move, and stop together.
                val startX = moved(mask.startX, across)
                val startY = moved(mask.startY, down)
                mask.copy(startX = startX, startY = startY, endX = mask.endX + startX - mask.startX, endY = mask.endY + startY - mask.startY)
            }
            else -> mask.copy(startX = moved(mask.startX, across), startY = moved(mask.startY, down))
        }
    }

    private companion object {
        const val OUTSIDE = 0.5f
    }
}

/**
 * Shows the whole [picture] with the masks of [locals] marked on it. The [selected] one shows where it
 * is full and where it has faded out, and can be dragged by either; tapping the mark of another one
 * selects that.
 */
@Composable
internal fun MaskFrame(
    picture: ImageBitmap,
    locals: List<LocalEdit>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onChange: (Mask) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    brushRadius: Float = DEFAULT_BRUSH_RADIUS,
    erasing: Boolean = false,
) {
    val areaLabel = stringResource(R.string.mask_area)
    val brush by rememberUpdatedState(brushRadius)
    val wiping by rememberUpdatedState(erasing)
    val aspect = picture.width.toFloat() / picture.height
    // What a painted mask covers, shown in red over the picture.
    val selectedMask = locals.getOrNull(selected)?.mask
    val painted = remember(selectedMask, aspect) {
        selectedMask?.takeIf { it.shape == MaskShape.BRUSH }?.let { paintedOverlay(it, aspect) }
    }
    val current by rememberUpdatedState(locals)
    val chosen by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    var grip by remember { mutableStateOf<MaskGrip?>(null) }
    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = areaLabel }
            .pointerInput(picture.width, picture.height) {
                fun layout() = MaskLayout(Size(size.width.toFloat(), size.height.toFloat()), MARGIN.toPx(), picture.width.toFloat(), picture.height.toFloat())
                detectTapGestures { position ->
                    val layout = layout()
                    current.indices
                        .filter { it != chosen && layout.gripAt(current[it].mask, position, REACH.toPx()) != null }
                        .minByOrNull { (position - layout.startOf(current[it].mask)).getDistance() }
                        ?.let(select)
                }
            }
            .pointerInput(picture.width, picture.height) {
                fun layout() = MaskLayout(Size(size.width.toFloat(), size.height.toFloat()), MARGIN.toPx(), picture.width.toFloat(), picture.height.toFloat())
                detectDragGestures(
                    onDragStart = { position ->
                        val mask = current.getOrNull(chosen)?.mask
                        if (mask?.shape == MaskShape.BRUSH) {
                            // A new stroke of the brush, starting where the finger went down.
                            val (x, y) = layout().inPicture(position)
                            change(mask.copy(strokes = mask.strokes + BrushStroke(listOf(x, y), brush, wiping)))
                            grip = null
                        } else {
                            grip = mask?.let { layout().gripAt(it, position, REACH.toPx()) }
                        }
                    },
                    onDragEnd = {
                        grip = null
                        done()
                    },
                    onDragCancel = {
                        grip = null
                        done()
                    },
                    onDrag = { input, amount ->
                        input.consume()
                        val held = grip
                        val mask = current.getOrNull(chosen)?.mask
                        if (mask?.shape == MaskShape.BRUSH) {
                            val stroke = mask.strokes.lastOrNull() ?: return@detectDragGestures
                            val layout = layout()
                            val (x, y) = layout.inPicture(input.position)
                            // Points closer together than a quarter of the brush add nothing but work.
                            val lastX = stroke.points[stroke.points.size - 2]
                            val lastY = stroke.points[stroke.points.size - 1]
                            val apart = Offset((x - lastX) * layout.picture.width, (y - lastY) * layout.picture.height).getDistance()
                            if (apart >= stroke.radius * maxOf(layout.picture.width, layout.picture.height) / 4) {
                                change(mask.copy(strokes = mask.strokes.dropLast(1) + stroke.copy(points = stroke.points + listOf(x, y))))
                            }
                        } else if (held != null && mask != null) {
                            change(layout().dragged(mask, held, amount))
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
        if (painted != null) {
            drawImage(
                painted,
                dstOffset = IntOffset(layout.picture.left.roundToInt(), layout.picture.top.roundToInt()),
                dstSize = IntSize(layout.picture.width.roundToInt(), layout.picture.height.roundToInt()),
            )
        }
        locals.forEachIndexed { index, local ->
            val mask = local.mask
            when {
                mask.shape == MaskShape.EVERYWHERE -> Unit
                mask.shape == MaskShape.BRUSH -> if (index != selected) {
                    mask.strokes.firstOrNull()?.let { drawCircle(Color.White.copy(alpha = 0.7f), 7.dp.toPx(), layout.onCanvas(it.points[0], it.points[1])) }
                }
                index == selected -> drawMask(layout, mask)
                else -> drawCircle(Color.White.copy(alpha = 0.7f), 7.dp.toPx(), layout.startOf(mask))
            }
        }
    }
}

/** The selected mask: a solid line where it is full, a dashed one where it has faded out, and a handle for each. */
private fun DrawScope.drawMask(layout: MaskLayout, mask: Mask) {
    if (mask.shape == MaskShape.EVERYWHERE) return
    val start = layout.startOf(mask)
    val end = layout.endOf(mask)
    val solid = Stroke(2.dp.toPx())
    val dashed = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
    clipRect(layout.picture.left, layout.picture.top, layout.picture.right, layout.picture.bottom) {
        when (mask.shape) {
            MaskShape.LINEAR -> {
                // Lines at right angles to the way from start to end, long enough to cross the picture wherever they are.
                val along = end - start
                val length = hypot(along.x, along.y)
                if (length > 0f) {
                    val reach = hypot(size.width, size.height)
                    val side = Offset(-along.y / length * reach, along.x / length * reach)
                    drawLine(Color.White, start - side, start + side, strokeWidth = solid.width)
                    drawLine(Color.White, end - side, end + side, strokeWidth = dashed.width, pathEffect = dashed.pathEffect)
                }
            }
            MaskShape.BRUSH, MaskShape.EVERYWHERE -> Unit
            MaskShape.RADIAL -> {
                val radii = Size(abs(end.x - start.x), abs(end.y - start.y))
                val inner = Size(radii.width * (1f - mask.feather), radii.height * (1f - mask.feather))
                drawOval(Color.White, Offset(start.x - inner.width, start.y - inner.height), Size(inner.width * 2, inner.height * 2), style = solid)
                drawOval(Color.White, Offset(start.x - radii.width, start.y - radii.height), Size(radii.width * 2, radii.height * 2), style = dashed)
            }
        }
    }
    drawLine(Color.White.copy(alpha = 0.6f), start, end, strokeWidth = 1.dp.toPx())
    drawCircle(Color.White, 9.dp.toPx(), start)
    drawCircle(Color.Black.copy(alpha = 0.5f), 9.dp.toPx(), start, style = Stroke(1.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = 0.4f), 8.dp.toPx(), end)
    drawCircle(Color.White, 8.dp.toPx(), end, style = solid)
}

/** What a painted mask covers, as a red veil that is the denser the more of the mask covers a place. */
private fun paintedOverlay(mask: Mask, aspect: Float): ImageBitmap {
    val raster = BrushRaster.of(mask, aspect)
    val pixels = IntArray(raster.coverage.size) { index ->
        val covered = raster.coverage[index].let { if (mask.inverted) 1f - it else it }
        ((covered * 110).roundToInt() shl 24) or 0xFF3030
    }
    return Bitmap.createBitmap(pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The width of a new brush stroke, as a fraction of the picture's long side, unless another is chosen. */
internal const val DEFAULT_BRUSH_RADIUS = 0.04f

private val MARGIN = 20.dp
private val REACH = 32.dp
