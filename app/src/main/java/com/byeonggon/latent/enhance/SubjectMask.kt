package com.byeonggon.latent.enhance

import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A picture as it is shown, each colour from 0 to 1 for every pixel, row by row. */
class ShownPicture(val width: Int, val height: Int, val red: FloatArray, val green: FloatArray, val blue: FloatArray)

/** How likely each cell of a [width] by [height] grid over a picture belongs to what was looked for, from 0 to 1. */
class Segmentation(val width: Int, val height: Int, val values: FloatArray)

/**
 * A model that tells the subject of a picture from its background. Kept behind this so that tests can
 * stand in for it and another model can take its place without touching the rest.
 */
interface Segmenter : AutoCloseable {
    /** The model's name and version, kept with every mask it makes. */
    val model: String
    val modelVersion: Int

    /** How likely each part of [picture] belongs to its subject, on a grid of the model's own choosing over the whole picture. */
    @Throws(SegmenterException::class)
    fun segment(picture: ShownPicture): Segmentation

    override fun close() {}
}

/** The model could not be made to run, or failed while running. */
class SegmenterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Finds the subject of a picture and draws its outline. The model answers on a coarse grid; a guided
 * filter works out, at that size, how its answer follows the picture's brightness, and that is applied to
 * the brightness at the outline's full size, so that the edge is as sharp as the picture's own there:
 * the way phones bring up a sky found at low resolution. It sharpens an edge the model put in the right
 * place; one it put in the wrong place stays there.
 */
object SubjectMasks {
    private const val TAG = "SubjectMask"

    /** Long side of the outline kept, in cells: about what the editor shows, a little soft on a full-size picture. */
    const val STORED_EDGE = 1024

    /** Below this, the most the model is sure of anywhere, nothing in the picture stands out. */
    private const val LEAST_PEAK = 0.2f

    /** How far the refinement looks around each cell of the model's grid, as a fraction of its long side. */
    private const val REACH = 0.01f

    /** Brightness differences well above this (squared) count as an edge the outline should follow. */
    private const val EDGE_VARIANCE = 0.0005f

    /**
     * The outline of the subject of [picture], the whole upright picture as the camera made it, at most
     * [STORED_EDGE] cells on its long side; null if nothing in it stands out.
     */
    @Throws(SegmenterException::class)
    fun outline(picture: LinearImage, segmenter: Segmenter): MaskRaster? {
        val started = System.nanoTime()
        val shown = shownAt(picture, STORED_EDGE)
        val asked = System.nanoTime()
        val found = segmenter.segment(shown)
        val answered = System.nanoTime()
        val outline = refined(shown, found) ?: return null.also { Log.i(TAG, "Nothing stands out in a ${shown.width}x${shown.height} picture") }
        Log.i(
            TAG,
            "Outline ${shown.width}x${shown.height} by ${segmenter.model} v${segmenter.modelVersion} in ${millis(started)} ms: " +
                "picture ${(asked - started) / 1_000_000} ms, model ${(answered - asked) / 1_000_000} ms, edges ${millis(answered)} ms",
        )
        return MaskRaster.of(shown.width, shown.height, outline)
    }

    /**
     * The model's [found] grid laid over [shown] and made to follow its edges; null if nothing stands out.
     * The model is read as its authors do: stretched so that its least and most certain cells are 0 and 1.
     */
    internal fun refined(shown: ShownPicture, found: Segmentation): FloatArray? {
        var lowest = Float.MAX_VALUE
        var highest = -Float.MAX_VALUE
        for (value in found.values) {
            if (value.isNaN()) throw SegmenterException("The model answered with numbers that are not numbers")
            lowest = min(lowest, value)
            highest = max(highest, value)
        }
        if (highest < LEAST_PEAK) return null
        // A picture that is all subject, or all of one kind, has nothing to stretch apart: it is taken as it is.
        val stretched = if (highest - lowest < LEAST_PEAK) found.values else FloatArray(found.values.size) { (found.values[it] - lowest) / (highest - lowest) }
        val width = shown.width
        val height = shown.height
        val brightness = FloatArray(width * height) { LUMA_RED * shown.red[it] + LUMA_GREEN * shown.green[it] + LUMA_BLUE * shown.blue[it] }
        // How the answer follows brightness is worked out at about the model's own size, where both are as
        // sharp as each other, and applied to the picture's brightness at full size, where edges are sharper.
        val scale = min(1f, max(found.width, found.height).toFloat() / max(width, height))
        val smallWidth = max(1, (width * scale).roundToInt())
        val smallHeight = max(1, (height * scale).roundToInt())
        val map = GuidedMap.of(
            resampled(brightness, width, height, smallWidth, smallHeight),
            resampled(stretched, found.width, found.height, smallWidth, smallHeight),
            smallWidth,
            smallHeight,
            max(1, (max(smallWidth, smallHeight) * REACH).roundToInt()),
            EDGE_VARIANCE,
        )
        val columns = GuidedMap.columns(smallWidth, width)
        val slopes = FloatArray(width)
        val offsets = FloatArray(width)
        val outline = FloatArray(width * height)
        for (y in 0 until height) {
            map.row(columns, (y + 0.5f) / height, slopes, offsets)
            for (x in 0 until width) {
                val at = y * width + x
                outline[at] = (slopes[x] * brightness[at] + offsets[x]).coerceIn(0f, 1f)
            }
        }
        return outline
    }

    /** [picture] as the camera shows it, with nothing changed, no larger than [longSide] on its long side. */
    internal fun shownAt(picture: LinearImage, longSide: Int): ShownPicture {
        val pixels = ToneRenderer(PhotoEdit(), picture.downscaled(ToneRenderer.OVERVIEW_EDGE)).render(picture)
        val size = picture.width * picture.height
        val red = FloatArray(size) { ((pixels[it] ushr 16) and 0xFF) / 255f }
        val green = FloatArray(size) { ((pixels[it] ushr 8) and 0xFF) / 255f }
        val blue = FloatArray(size) { (pixels[it] and 0xFF) / 255f }
        val scale = min(1f, longSide.toFloat() / max(picture.width, picture.height))
        if (scale >= 1f) return ShownPicture(picture.width, picture.height, red, green, blue)
        val width = max(1, (picture.width * scale).roundToInt())
        val height = max(1, (picture.height * scale).roundToInt())
        return ShownPicture(
            width,
            height,
            resampled(red, picture.width, picture.height, width, height),
            resampled(green, picture.width, picture.height, width, height),
            resampled(blue, picture.width, picture.height, width, height),
        )
    }

    private fun millis(since: Long) = (System.nanoTime() - since) / 1_000_000
}

/**
 * [values] of a [width] by [height] grid on a grid [toWidth] by [toHeight] over the same area: where it
 * shrinks, each new cell is the average of the old cells it covers, in part or in whole; where it grows,
 * the old cells are read between.
 */
internal fun resampled(values: FloatArray, width: Int, height: Int, toWidth: Int, toHeight: Int): FloatArray {
    val across = FloatArray(toWidth * height)
    resampleLines(values, across, lines = height, length = width, toLength = toWidth, lineStep = width, step = 1, toLineStep = toWidth, toStep = 1)
    val result = FloatArray(toWidth * toHeight)
    resampleLines(across, result, lines = toWidth, length = height, toLength = toHeight, lineStep = 1, step = toWidth, toLineStep = 1, toStep = toWidth)
    return result
}

private fun resampleLines(
    source: FloatArray,
    target: FloatArray,
    lines: Int,
    length: Int,
    toLength: Int,
    lineStep: Int,
    step: Int,
    toLineStep: Int,
    toStep: Int,
) {
    val ratio = length.toFloat() / toLength
    for (line in 0 until lines) {
        val from = line * lineStep
        val to = line * toLineStep
        for (i in 0 until toLength) {
            target[to + i * toStep] = if (ratio > 1f) {
                // The old cells from start to end, the first and last only in part.
                val start = i * ratio
                val end = start + ratio
                var sum = 0f
                var cell = start.toInt()
                while (cell < end && cell < length) {
                    val covered = min(end, cell + 1f) - max(start, cell.toFloat())
                    sum += source[from + cell * step] * covered
                    cell++
                }
                sum / ratio
            } else {
                val position = ((i + 0.5f) * ratio - 0.5f).coerceIn(0f, length - 1f)
                val below = position.toInt()
                val above = min(below + 1, length - 1)
                val share = position - below
                source[from + below * step] + (source[from + above * step] - source[from + below * step]) * share
            }
        }
    }
}
