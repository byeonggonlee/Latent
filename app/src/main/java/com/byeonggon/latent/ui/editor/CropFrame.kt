package com.byeonggon.latent.ui.editor

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.enhance.FramedView
import com.byeonggon.latent.enhance.Framing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.R

/** Which sides of the kept rectangle a finger has hold of. All four move the rectangle as a whole. */
internal data class Grip(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    val moves: Boolean get() = left && top && right && bottom
}

/**
 * Where things are when the whole picture is shown, turned and levelled, with the kept rectangle
 * upright on top of it: sizes in the picture's own pixels on one side, positions on the canvas on the other.
 */
internal class CropLayout(
    canvas: Size,
    margin: Float,
    private val framing: Framing,
    private val pictureWidth: Float,
    private val pictureHeight: Float,
) {
    val centre = Offset(canvas.width / 2, canvas.height / 2)
    private val sideways = framing.quarterTurns % 2 != 0
    private val turnedWidth = if (sideways) pictureHeight else pictureWidth
    private val turnedHeight = if (sideways) pictureWidth else pictureHeight

    /** Canvas pixels per picture pixel: the picture as it is seen, corners and all, just fits inside the margin. */
    val scale: Float

    /**
     * Where the corners of the picture as it is stored, before its quarter turns, are on the canvas:
     * top left, top right, bottom right, bottom left, each as x and y.
     */
    val corners: FloatArray

    init {
        val view = FramedView(framing, turnedWidth, turnedHeight)
        val halfWidth = pictureWidth / 2
        val halfHeight = pictureHeight / 2
        // In the view, relative to its centre and in the picture's pixels.
        val seen = FloatArray(8)
        var reach = 0f
        var drop = 0f
        listOf(-halfWidth to -halfHeight, halfWidth to -halfHeight, halfWidth to halfHeight, -halfWidth to halfHeight).forEachIndexed { index, (x, y) ->
            // Through the quarter turns first: where the corner is in the turned picture.
            val turnedX = when (framing.quarterTurns.mod(4)) { 1 -> -y; 2 -> -x; 3 -> y; else -> x }
            val turnedY = when (framing.quarterTurns.mod(4)) { 1 -> x; 2 -> -y; 3 -> -x; else -> y }
            seen[2 * index] = view.viewX(turnedX, turnedY)
            seen[2 * index + 1] = view.viewY(turnedX, turnedY)
            reach = max(reach, abs(seen[2 * index]))
            drop = max(drop, abs(seen[2 * index + 1]))
        }
        scale = min((canvas.width - 2 * margin) / (2 * reach), (canvas.height - 2 * margin) / (2 * drop)).coerceAtLeast(0f)
        corners = FloatArray(8) { index -> (if (index % 2 == 0) centre.x else centre.y) + seen[index] * scale }
    }

    /** The kept rectangle on the canvas. */
    val kept = Rect(
        left = centre.x + (framing.left - 0.5f) * turnedWidth * scale,
        top = centre.y + (framing.top - 0.5f) * turnedHeight * scale,
        right = centre.x + (framing.right - 0.5f) * turnedWidth * scale,
        bottom = centre.y + (framing.bottom - 0.5f) * turnedHeight * scale,
    )

    /**
     * What a finger put down at [position] gets hold of, reaching [reach] far: a corner, a side, the
     * whole rectangle from inside it, or nothing. Sides cannot be pulled alone when the shape is to be kept.
     */
    fun gripAt(position: Offset, reach: Float, keepShape: Boolean): Grip? {
        val alongside = position.x in kept.left - reach..kept.right + reach && position.y in kept.top - reach..kept.bottom + reach
        if (!alongside) return null
        val left = abs(position.x - kept.left) < reach && position.x - kept.left <= kept.right - position.x
        val right = abs(position.x - kept.right) < reach && !left
        val top = abs(position.y - kept.top) < reach && position.y - kept.top <= kept.bottom - position.y
        val bottom = abs(position.y - kept.bottom) < reach && !top
        val corner = (left || right) && (top || bottom)
        return when {
            corner || (!keepShape && (left || right || top || bottom)) -> Grip(left, top, right, bottom)
            kept.contains(position) -> Grip(left = true, top = true, right = true, bottom = true)
            else -> null
        }
    }

    /** The framing after [grip] was dragged by [amount] on the canvas, stopped where the picture ends. */
    fun dragged(grip: Grip, amount: Offset, keepShape: Boolean): Framing {
        val across = amount.x / (turnedWidth * scale)
        val down = amount.y / (turnedHeight * scale)
        var left = framing.left
        var top = framing.top
        var right = framing.right
        var bottom = framing.bottom
        if (grip.moves) {
            left += across
            right += across
            top += down
            bottom += down
        } else {
            if (grip.left) left = min(left + across, right - SMALLEST)
            if (grip.right) right = max(right + across, left + SMALLEST)
            if (grip.top) top = min(top + down, bottom - SMALLEST)
            if (grip.bottom) bottom = max(bottom + down, top + SMALLEST)
            if (keepShape) {
                // The side that was pulled further sets the size; the other follows to keep the shape.
                val shape = framing.width / framing.height
                var width = right - left
                var height = bottom - top
                if (abs(amount.x) >= abs(amount.y)) height = width / shape else width = height * shape
                if (grip.left) left = right - width else right = left + width
                if (grip.top) top = bottom - height else bottom = top + height
            }
        }
        val wanted = framing.copy(left = left, top = top, right = right, bottom = bottom)
        if (keepShape && !grip.moves) return framing.towards(wanted, pictureWidth, pictureHeight)
        // One direction after the other, so that reaching the picture's edge in one leaves the other free.
        val widened = framing.towards(framing.copy(left = left, right = right), pictureWidth, pictureHeight)
        return widened.towards(widened.copy(top = top, bottom = bottom), pictureWidth, pictureHeight)
    }

    private companion object {
        /** The kept part cannot be made smaller than this fraction of the picture's width or height. */
        const val SMALLEST = 0.08f
    }
}

/**
 * Shows the whole [picture], turned and levelled as [framing] says, with the kept rectangle marked on
 * it. Dragging a corner or a side resizes the rectangle, dragging inside moves it.
 */
@Composable
internal fun CropFrame(
    picture: ImageBitmap,
    framing: Framing,
    keepShape: Boolean,
    onChange: (Framing) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val areaLabel = stringResource(R.string.crop_area)
    val current by rememberUpdatedState(framing)
    val keep by rememberUpdatedState(keepShape)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    var grip by remember { mutableStateOf<Grip?>(null) }
    val bitmap = remember(picture) { picture.asAndroidBitmap() }
    val smooth = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = areaLabel }
            .pointerInput(picture.width, picture.height) {
                fun layout() = CropLayout(
                    Size(size.width.toFloat(), size.height.toFloat()),
                    MARGIN.toPx(),
                    current,
                    picture.width.toFloat(),
                    picture.height.toFloat(),
                )
                detectDragGestures(
                    onDragStart = { grip = layout().gripAt(it, REACH.toPx(), keep) },
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
                        grip?.let { change(layout().dragged(it, amount, keep)) }
                    },
                )
            },
    ) {
        val layout = CropLayout(size, MARGIN.toPx(), framing, picture.width.toFloat(), picture.height.toFloat())
        // Turned, mirrored, tilted and levelled in one go: the picture's four corners are put where they belong.
        val width = picture.width.toFloat()
        val height = picture.height.toFloat()
        val placed = Matrix().apply { setPolyToPoly(floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height), 0, layout.corners, 0, 4) }
        drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, placed, smooth) }
        drawKeptRectangle(layout.kept)
    }
}

/** Darkens everything outside [kept] and draws its outline, a grid of thirds and corners to hold it by. */
private fun DrawScope.drawKeptRectangle(kept: Rect) {
    val shade = Color.Black.copy(alpha = 0.6f)
    drawRect(shade, Offset.Zero, Size(size.width, kept.top))
    drawRect(shade, Offset(0f, kept.bottom), Size(size.width, size.height - kept.bottom))
    drawRect(shade, Offset(0f, kept.top), Size(kept.left, kept.height))
    drawRect(shade, Offset(kept.right, kept.top), Size(size.width - kept.right, kept.height))

    val thirds = Color.White.copy(alpha = 0.45f)
    for (part in 1..2) {
        val x = kept.left + kept.width * part / 3
        val y = kept.top + kept.height * part / 3
        drawLine(thirds, Offset(x, kept.top), Offset(x, kept.bottom), strokeWidth = 1.dp.toPx())
        drawLine(thirds, Offset(kept.left, y), Offset(kept.right, y), strokeWidth = 1.dp.toPx())
    }
    drawRect(Color.White, kept.topLeft, kept.size, style = Stroke(1.5.dp.toPx()))

    val thickness = 4.dp.toPx()
    val length = min(20.dp.toPx(), min(kept.width, kept.height) / 3)
    for (x in listOf(kept.left, kept.right)) for (y in listOf(kept.top, kept.bottom)) {
        val inwardsX = if (x == kept.left) length else -length
        val inwardsY = if (y == kept.top) length else -length
        drawLine(Color.White, Offset(x, y), Offset(x + inwardsX, y), strokeWidth = thickness)
        drawLine(Color.White, Offset(x, y), Offset(x, y + inwardsY), strokeWidth = thickness)
    }
}

private val MARGIN = 20.dp
private val REACH = 28.dp
