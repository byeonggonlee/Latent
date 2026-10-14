package com.byeonggon.cameramanager.ui.editor

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.byeonggon.cameramanager.enhance.LocalEdit
import com.byeonggon.cameramanager.enhance.Mask
import com.byeonggon.cameramanager.enhance.MaskShape
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

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

    fun startOf(mask: Mask) = onCanvas(mask.startX, mask.startY)

    fun endOf(mask: Mask) = onCanvas(mask.endX, mask.endY)

    /** What of [mask] a finger at [position] takes hold of, reaching [reach] far; the nearer of the two if both are in reach. */
    fun gripAt(mask: Mask, position: Offset, reach: Float): MaskGrip? {
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
) {
    val current by rememberUpdatedState(locals)
    val chosen by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    var grip by remember { mutableStateOf<MaskGrip?>(null) }
    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "마스크 영역" }
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
                    onDragStart = { position -> grip = current.getOrNull(chosen)?.let { layout().gripAt(it.mask, position, REACH.toPx()) } },
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
                        if (held != null && mask != null) change(layout().dragged(mask, held, amount))
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
        locals.forEachIndexed { index, local ->
            if (index == selected) drawMask(layout, local.mask) else drawCircle(Color.White.copy(alpha = 0.7f), 7.dp.toPx(), layout.startOf(local.mask))
        }
    }
}

/** The selected mask: a solid line where it is full, a dashed one where it has faded out, and a handle for each. */
private fun DrawScope.drawMask(layout: MaskLayout, mask: Mask) {
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

private val MARGIN = 20.dp
private val REACH = 32.dp
