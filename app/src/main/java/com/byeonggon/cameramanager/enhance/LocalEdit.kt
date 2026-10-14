package com.byeonggon.cameramanager.enhance

import kotlin.math.sqrt

enum class MaskShape {
    /** Full on one side of the picture, fading out along a line towards the other. */
    LINEAR,

    /** Painted on with a brush. */
    BRUSH,

    /** Full inside an ellipse, fading out towards its edge. */
    RADIAL,
}

/**
 * One movement of the brush: the places it went through, as pairs of fractions of the whole picture's
 * width and height, and how wide it was, as a fraction of the picture's long side. An [erasing]
 * stroke takes away what earlier strokes painted.
 */
data class BrushStroke(val points: List<Float>, val radius: Float, val erasing: Boolean = false)

/**
 * The part of a picture that a local edit applies to. Positions are fractions of the whole upright
 * picture's width and height, so that a mask stays on what it was put on when the picture is cut.
 *
 * A [MaskShape.LINEAR] mask is full at the start point and everywhere behind it, and fades to nothing
 * at the end point. A [MaskShape.RADIAL] mask is an ellipse around the start point whose edge reaches
 * the end point's distance across and down; [feather] is the share of the way from its centre to its
 * edge over which it fades out. [inverted] swaps the part it covers with the part it leaves alone.
 */
data class Mask(
    val shape: MaskShape,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val feather: Float = 0.5f,
    val inverted: Boolean = false,
    /** What was painted, for a [MaskShape.BRUSH] mask. */
    val strokes: List<BrushStroke> = emptyList(),
) {
    /**
     * How much of a local edit applies at ([x], [y]), from 0 to 1. The whole picture's size, in any
     * unit, gives distances across and down their true proportion.
     */
    fun weightAt(x: Float, y: Float, pictureWidth: Float, pictureHeight: Float): Float {
        val across = (endX - startX) * pictureWidth
        val down = (endY - startY) * pictureHeight
        val covered = when (shape) {
            MaskShape.LINEAR -> {
                val length = across * across + down * down
                if (length <= 0f) 0f else 1f - eased(((x - startX) * pictureWidth * across + (y - startY) * pictureHeight * down) / length)
            }
            MaskShape.BRUSH -> BrushRaster.of(this, pictureWidth / pictureHeight).weightAt(x, y)
            MaskShape.RADIAL -> {
                if (across == 0f || down == 0f) {
                    0f
                } else {
                    val fromCentreX = (x - startX) * pictureWidth / across
                    val fromCentreY = (y - startY) * pictureHeight / down
                    val distance = sqrt(fromCentreX * fromCentreX + fromCentreY * fromCentreY)
                    if (feather <= 0f) (if (distance <= 1f) 1f else 0f) else 1f - eased((distance - (1f - feather)) / feather)
                }
            }
        }
        return if (inverted) 1f - covered else covered
    }

    /** From 0 to 1 as [share] goes from 0 to 1, starting and ending gently. */
    private fun eased(share: Float): Float {
        val within = share.coerceIn(0f, 1f)
        return within * within * (3f - 2f * within)
    }
}

/**
 * Looks up how much of [mask] covers each pixel of a picture [pictureWidth] by [pictureHeight] in any
 * unit. Made once for a rendering: a painted mask is laid out on its grid here, not for every pixel.
 */
internal class MaskWeights(private val mask: Mask, private val pictureWidth: Float, private val pictureHeight: Float) {
    private val raster = if (mask.shape == MaskShape.BRUSH) BrushRaster.of(mask, pictureWidth / pictureHeight) else null

    fun at(x: Float, y: Float): Float {
        if (raster == null) return mask.weightAt(x, y, pictureWidth, pictureHeight)
        val covered = raster.weightAt(x, y)
        return if (mask.inverted) 1f - covered else covered
    }
}

/**
 * Settings for a part of the picture only, on top of what applies to all of it. Of [adjustments],
 * those that act on single pixels' tones and colours count; detail, vignette and grain do not.
 */
data class LocalEdit(val mask: Mask, val adjustments: Adjustments = Adjustments())

/**
 * How a framed picture, [frameWidth] by [frameHeight] pixels, lies in the whole upright picture it was
 * cut from: for any of its pixels, where that is in the whole picture, as fractions of its width and height.
 */
internal class Placement(private val framing: Framing, frameWidth: Int, frameHeight: Int) {
    private val turns = framing.quarterTurns.mod(4)
    private val turnedWidth = frameWidth / framing.width
    private val turnedHeight = frameHeight / framing.height
    private val view = FramedView(framing, turnedWidth, turnedHeight)

    /** The size of the whole picture, in the framed picture's pixels. */
    val wholeWidth: Float = if (turns % 2 == 1) turnedHeight else turnedWidth
    val wholeHeight: Float = if (turns % 2 == 1) turnedWidth else turnedHeight

    /** Where the middle of the framed picture's pixel ([x], [y]) is across the whole picture. */
    fun xOf(x: Int, y: Int): Float {
        val viewX = viewX(x)
        val viewY = viewY(y)
        // The same way back as when the picture was framed: out of the view, then through the quarter turns.
        return when (turns) {
            1 -> view.turnedY(viewX, viewY) / wholeWidth + 0.5f
            2 -> 0.5f - view.turnedX(viewX, viewY) / wholeWidth
            3 -> 0.5f - view.turnedY(viewX, viewY) / wholeWidth
            else -> view.turnedX(viewX, viewY) / wholeWidth + 0.5f
        }
    }

    /** Where the middle of the framed picture's pixel ([x], [y]) is down the whole picture. */
    fun yOf(x: Int, y: Int): Float {
        val viewX = viewX(x)
        val viewY = viewY(y)
        return when (turns) {
            1 -> 0.5f - view.turnedX(viewX, viewY) / wholeHeight
            2 -> 0.5f - view.turnedY(viewX, viewY) / wholeHeight
            3 -> view.turnedX(viewX, viewY) / wholeHeight + 0.5f
            else -> view.turnedY(viewX, viewY) / wholeHeight + 0.5f
        }
    }

    private fun viewX(x: Int) = framing.left * turnedWidth + x + 0.5f - turnedWidth / 2

    private fun viewY(y: Int) = framing.top * turnedHeight + y + 0.5f - turnedHeight / 2
}
