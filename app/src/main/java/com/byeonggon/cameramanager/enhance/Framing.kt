package com.byeonggon.cameramanager.enhance

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Which part of a picture is kept, and how it is turned.
 *
 * The picture is turned clockwise by [quarterTurns], mirrored if [flipped], tilted for perspective,
 * and then levelled: turned by another [angle] degrees about its centre. The kept rectangle is given
 * in that last view, as fractions of the width and height the picture has after its quarter turns.
 * Once tilted or levelled, the picture no longer fills an upright rectangle, so not every rectangle
 * lies within it: see [fits].
 *
 * [vertical] and [horizontal], from -1 to 1, tilt the picture as if it were looked at from further
 * up or down, or from one side: above zero, what is at the bottom, or on the right, comes out larger.
 * That is what straightens the leaning walls of a building photographed from below.
 */
data class Framing(
    val quarterTurns: Int = 0,
    val angle: Float = 0f,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val flipped: Boolean = false,
    val vertical: Float = 0f,
    val horizontal: Float = 0f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Whether the kept rectangle lies within the picture, which is [pictureWidth] by [pictureHeight] before any turn. */
    fun fits(pictureWidth: Float, pictureHeight: Float): Boolean {
        val sideways = quarterTurns % 2 != 0
        val turnedWidth = if (sideways) pictureHeight else pictureWidth
        val turnedHeight = if (sideways) pictureWidth else pictureHeight
        val view = FramedView(this, turnedWidth, turnedHeight)
        // A little slack, so that a rectangle fitted by one calculation is not rejected by the rounding of another.
        val slack = max(turnedWidth, turnedHeight) * 5e-5f
        for (u in floatArrayOf(left, right)) for (v in floatArrayOf(top, bottom)) {
            val x = (u - 0.5f) * turnedWidth
            val y = (v - 0.5f) * turnedHeight
            if (!view.reaches(x, y)) return false
            if (abs(view.turnedX(x, y)) > turnedWidth / 2 + slack || abs(view.turnedY(x, y)) > turnedHeight / 2 + slack) return false
        }
        return true
    }

    /** This framing, with its rectangle shrunk about its own centre as far as it takes to fit the picture. */
    fun shrunkToFit(pictureWidth: Float, pictureHeight: Float): Framing {
        if (fits(pictureWidth, pictureHeight)) return this
        val centre = copy(left = (left + right) / 2, top = (top + bottom) / 2, right = (left + right) / 2, bottom = (top + bottom) / 2)
        return centre.towards(this, pictureWidth, pictureHeight)
    }

    /**
     * As much of the way from this framing's rectangle to that of [wanted] as stays within the
     * picture. This one has to fit. Everything but the rectangle is taken from [wanted].
     */
    fun towards(wanted: Framing, pictureWidth: Float, pictureHeight: Float): Framing {
        if (wanted.fits(pictureWidth, pictureHeight)) return wanted
        fun partWay(share: Float) = wanted.copy(
            left = left + (wanted.left - left) * share,
            top = top + (wanted.top - top) * share,
            right = right + (wanted.right - right) * share,
            bottom = bottom + (wanted.bottom - bottom) * share,
        )
        // The rectangles that fit form a convex set, so everything up to the furthest fitting one fits too.
        var fitting = 0f
        var beyond = 1f
        repeat(SEARCH_STEPS) {
            val middle = (fitting + beyond) / 2
            if (partWay(middle).fits(pictureWidth, pictureHeight)) fitting = middle else beyond = middle
        }
        return partWay(fitting)
    }

    /** Another quarter turn clockwise of the picture as it is seen, keeping the same part of it. */
    fun turnedClockwise(): Framing = copy(
        // A mirrored picture turns the other way round underneath.
        quarterTurns = (quarterTurns + if (flipped) 3 else 1) % 4,
        left = 1f - bottom,
        top = left,
        right = 1f - top,
        bottom = right,
        // What was up and down is now right and left.
        vertical = horizontal,
        horizontal = -vertical,
    )

    /** The picture as it is seen, mirrored left to right, keeping the same part of it. */
    fun mirrored(): Framing = copy(
        flipped = !flipped,
        left = 1f - right,
        right = 1f - left,
        angle = -angle + 0f,
        horizontal = -horizontal + 0f,
    )

    /**
     * The largest rectangle around the picture's centre that is [ratio] times as wide as it is high,
     * with the picture turned, mirrored, tilted and levelled as it is in this framing.
     */
    fun largestOf(ratio: Float, pictureWidth: Float, pictureHeight: Float): Framing {
        val sideways = quarterTurns % 2 != 0
        val turnedWidth = if (sideways) pictureHeight else pictureWidth
        val turnedHeight = if (sideways) pictureWidth else pictureHeight
        // As high as the picture, or as wide where that is the limit; tilting and levelling can only take away from this.
        val height = minOf(turnedHeight, turnedWidth / ratio)
        val halfWidth = height * ratio / turnedWidth / 2
        val halfHeight = height / turnedHeight / 2
        return copy(left = 0.5f - halfWidth, top = 0.5f - halfHeight, right = 0.5f + halfWidth, bottom = 0.5f + halfHeight)
            .shrunkToFit(pictureWidth, pictureHeight)
    }

    companion object {
        private const val SEARCH_STEPS = 24

        /** [largestOf] for a picture that is only turned by [quarterTurns] and levelled by [angle]. */
        fun largest(quarterTurns: Int, angle: Float, ratio: Float, pictureWidth: Float, pictureHeight: Float): Framing =
            Framing(quarterTurns, angle).largestOf(ratio, pictureWidth, pictureHeight)
    }
}

/**
 * The view that a framing's rectangle is cut from: the turned picture, [turnedWidth] by [turnedHeight]
 * pixels, mirrored, tilted and levelled. Positions in the view and in the turned picture are both in
 * the picture's pixels, measured from the centre.
 */
internal class FramedView(framing: Framing, turnedWidth: Float, turnedHeight: Float) {
    private val cos = cos(Math.toRadians(framing.angle.toDouble())).toFloat()
    private val sin = sin(Math.toRadians(framing.angle.toDouble())).toFloat()
    private val tiltAcross = TILT_AT_FULL * framing.horizontal / (turnedWidth / 2)
    private val tiltDown = TILT_AT_FULL * framing.vertical / (turnedHeight / 2)
    private val mirror = if (framing.flipped) -1f else 1f

    /**
     * How large the picture comes out at a place in the view relative to its centre. Tilting makes
     * one side recede; far enough out it would fold over, and nothing of the picture is there.
     */
    private fun size(viewX: Float, viewY: Float): Float {
        val levelX = viewX * cos + viewY * sin
        val levelY = -viewX * sin + viewY * cos
        return 1f + tiltAcross * levelX + tiltDown * levelY
    }

    /** Whether the picture reaches as far as this place in the view at all. */
    fun reaches(viewX: Float, viewY: Float): Boolean = size(viewX, viewY) > SMALLEST

    /** Where across the turned picture the place at ([viewX], [viewY]) in the view comes from. */
    fun turnedX(viewX: Float, viewY: Float): Float = mirror * (viewX * cos + viewY * sin) / size(viewX, viewY)

    /** Where down the turned picture the place at ([viewX], [viewY]) in the view comes from. */
    fun turnedY(viewX: Float, viewY: Float): Float = (-viewX * sin + viewY * cos) / size(viewX, viewY)

    /** Where across the view the place at ([turnedX], [turnedY]) of the turned picture is shown. */
    fun viewX(turnedX: Float, turnedY: Float): Float {
        val size = 1f / (1f - tiltAcross * mirror * turnedX - tiltDown * turnedY)
        return (mirror * turnedX * cos - turnedY * sin) * size
    }

    /** Where down the view the place at ([turnedX], [turnedY]) of the turned picture is shown. */
    fun viewY(turnedX: Float, turnedY: Float): Float {
        val size = 1f / (1f - tiltAcross * mirror * turnedX - tiltDown * turnedY)
        return (mirror * turnedX * sin + turnedY * cos) * size
    }

    companion object {
        /** At a full setting, one edge of the picture comes out this much larger, and the opposite one as much smaller. */
        const val TILT_AT_FULL = 0.35f
        private const val SMALLEST = 0.2f
    }
}

/**
 * The part of this picture that [framing] keeps, turned, tilted and levelled. [uprightTurns] are the
 * quarter turns that bring this picture upright in the first place, for one that is stored on its
 * side; the framing applies to the upright picture.
 */
fun LinearImage.framed(framing: Framing, uprightTurns: Int = 0): LinearImage {
    val turns = (uprightTurns + framing.quarterTurns).mod(4)
    if (turns == 0 && framing.copy(quarterTurns = 0) == Framing()) return this
    val turnedWidth = if (turns % 2 == 1) height else width
    val turnedHeight = if (turns % 2 == 1) width else height
    val result = LinearImage(
        max(1, (framing.width * turnedWidth).roundToInt()),
        max(1, (framing.height * turnedHeight).roundToInt()),
    )
    val tilted = framing.vertical != 0f || framing.horizontal != 0f
    val straight = framing.angle == 0f && !tilted
    // Neither tilted nor levelled, the kept part starts on a whole pixel and is copied as it is, not blended from neighbours.
    val startX = (framing.left * turnedWidth).toDouble().let { if (straight) Math.rint(it) else it } + 0.5 - turnedWidth / 2.0
    val startY = (framing.top * turnedHeight).toDouble().let { if (straight) Math.rint(it) else it } + 0.5 - turnedHeight / 2.0
    val radians = Math.toRadians(framing.angle.toDouble())
    val cos = cos(radians)
    val sin = sin(radians)
    val tiltAcross = FramedView.TILT_AT_FULL * framing.horizontal / (turnedWidth / 2.0)
    val tiltDown = FramedView.TILT_AT_FULL * framing.vertical / (turnedHeight / 2.0)
    val mirror = if (framing.flipped) -1.0 else 1.0
    forRowBands(result.height) { from, until ->
        for (y in from until until) for (x in 0 until result.width) {
            // The way the picture was framed, backwards: out of the levelled view, the tilt and the mirror, then back through the quarter turns.
            val viewX = startX + x
            val viewY = startY + y
            var levelX = viewX * cos + viewY * sin
            var levelY = -viewX * sin + viewY * cos
            if (tilted) {
                val size = 1.0 + tiltAcross * levelX + tiltDown * levelY
                levelX /= size
                levelY /= size
            }
            val turnedX = mirror * levelX + turnedWidth / 2.0
            val turnedY = levelY + turnedHeight / 2.0
            val sourceX: Double
            val sourceY: Double
            when (turns) {
                0 -> { sourceX = turnedX; sourceY = turnedY }
                1 -> { sourceX = turnedY; sourceY = height - turnedX }
                2 -> { sourceX = width - turnedX; sourceY = height - turnedY }
                else -> { sourceX = width - turnedY; sourceY = turnedX }
            }
            copyBlended(sourceX - 0.5, sourceY - 0.5, result, y * result.width + x)
        }
    }
    return result
}

/** Writes the pixel at a position between pixels, blended from the four around it, to [target] at [index]. */
private fun LinearImage.copyBlended(x: Double, y: Double, target: LinearImage, index: Int) {
    val fx = x.coerceIn(0.0, width - 1.0)
    val fy = y.coerceIn(0.0, height - 1.0)
    val left = fx.toInt().coerceAtMost(max(0, width - 2))
    val top = fy.toInt().coerceAtMost(max(0, height - 2))
    val right = if (width > 1) 1 else 0
    val down = if (height > 1) width else 0
    val wx = (fx - left).toFloat()
    val wy = (fy - top).toFloat()
    val at = top * width + left
    target.red[index] = blend(red, at, right, down, wx, wy)
    target.green[index] = blend(green, at, right, down, wx, wy)
    target.blue[index] = blend(blue, at, right, down, wx, wy)
}

private fun blend(plane: ShortArray, at: Int, right: Int, down: Int, wx: Float, wy: Float): Short {
    val upper = plane[at] + (plane[at + right] - plane[at]) * wx
    val lower = plane[at + down] + (plane[at + down + right] - plane[at + down]) * wx
    return (upper + (lower - upper) * wy + 0.5f).toInt().toShort()
}
