package com.byeonggon.cameramanager.enhance

import kotlin.math.max
import kotlin.math.min

/**
 * Something that varies gently across a picture but follows the picture's edges: how bright an
 * area is as a whole, or how much haze lies over it. It is worked out on a small copy of the picture
 * and holds, for every pixel of that copy, a `slope` and an `offset`; its value at a pixel of the
 * full picture is `slope * the pixel's brightness + offset`, with both read between the small
 * copy's pixels. Across flat and textured areas the slope is near zero and the area's average counts;
 * at strong edges it follows the pixel itself, so nothing bleeds across the edge as a halo.
 *
 * This is a guided filter, with the picture's brightness as the guide.
 */
internal class GuidedMap private constructor(
    private val width: Int,
    private val height: Int,
    private val slope: FloatArray,
    private val offset: FloatArray,
) {
    /** Where each column of a full-size picture falls between the columns of the small copy. */
    class Columns(val left: IntArray, val right: IntArray, val weight: FloatArray)

    /** Fills in the slope and offset for one row of the full picture, [down] being how far down it is from 0 to 1. */
    fun row(columns: Columns, down: Float, slopes: FloatArray, offsets: FloatArray) {
        val position = (down * height - 0.5f).coerceIn(0f, height - 1f)
        val upper = position.toInt() * width
        val lower = min(position.toInt() + 1, height - 1) * width
        val weight = position - position.toInt()
        for (x in slopes.indices) {
            val left = columns.left[x]
            val right = columns.right[x]
            val across = columns.weight[x]
            val slopeAbove = slope[upper + left] + (slope[upper + right] - slope[upper + left]) * across
            val slopeBelow = slope[lower + left] + (slope[lower + right] - slope[lower + left]) * across
            slopes[x] = slopeAbove + (slopeBelow - slopeAbove) * weight
            val offsetAbove = offset[upper + left] + (offset[upper + right] - offset[upper + left]) * across
            val offsetBelow = offset[lower + left] + (offset[lower + right] - offset[lower + left]) * across
            offsets[x] = offsetAbove + (offsetBelow - offsetAbove) * weight
        }
    }

    companion object {
        /**
         * [values] of a small copy of a picture, [width] by [height] pixels, smoothed over [radius]
         * pixels in every direction but not across the edges of [brightness]. Differences in
         * brightness well above [edgeVariance], squared, count as an edge rather than as texture.
         */
        fun of(brightness: FloatArray, values: FloatArray, width: Int, height: Int, radius: Int, edgeVariance: Float): GuidedMap {
            val size = brightness.size
            val meanBrightness = blurred(brightness, width, height, radius)
            val meanValue = if (values === brightness) meanBrightness else blurred(values, width, height, radius)
            val meanSquare = blurred(FloatArray(size) { brightness[it] * brightness[it] }, width, height, radius)
            val meanProduct =
                if (values === brightness) meanSquare else blurred(FloatArray(size) { brightness[it] * values[it] }, width, height, radius)
            val slope = FloatArray(size) { index ->
                val variance = max(0f, meanSquare[index] - meanBrightness[index] * meanBrightness[index])
                (meanProduct[index] - meanBrightness[index] * meanValue[index]) / (variance + edgeVariance)
            }
            val offset = FloatArray(size) { meanValue[it] - slope[it] * meanBrightness[it] }
            return GuidedMap(width, height, blurred(slope, width, height, radius), blurred(offset, width, height, radius))
        }

        /**
         * [values] of a picture, [width] by [height] pixels, smoothed over [radius] pixels in every
         * direction but not across the edges of [brightness], at the picture's own size.
         */
        fun smoothed(brightness: FloatArray, values: FloatArray, width: Int, height: Int, radius: Int, edgeVariance: Float): FloatArray {
            val map = of(brightness, values, width, height, radius, edgeVariance)
            return FloatArray(values.size) { map.slope[it] * brightness[it] + map.offset[it] }
        }

        /**
         * Where columns of a picture [frameWidth] pixels wide fall in a small copy that is [width]
         * wide: [count] of them, starting at column [first].
         */
        fun columns(width: Int, frameWidth: Int, first: Int = 0, count: Int = frameWidth): Columns {
            val left = IntArray(count)
            val right = IntArray(count)
            val weight = FloatArray(count)
            for (x in 0 until count) {
                val position = ((first + x + 0.5f) * width / frameWidth - 0.5f).coerceIn(0f, width - 1f)
                left[x] = position.toInt()
                right[x] = min(left[x] + 1, width - 1)
                weight[x] = position - left[x]
            }
            return Columns(left, right, weight)
        }

        /** The average of the pixels up to [radius] away in each direction. */
        fun blurred(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
            val across = FloatArray(values.size)
            averageLines(values, across, lines = height, length = width, lineStep = width, step = 1, radius)
            val result = FloatArray(values.size)
            averageLines(across, result, lines = width, length = height, lineStep = 1, step = width, radius)
            return result
        }

        private fun averageLines(
            source: FloatArray,
            target: FloatArray,
            lines: Int,
            length: Int,
            lineStep: Int,
            step: Int,
            radius: Int,
        ) {
            val sums = FloatArray(length + 1)
            for (line in 0 until lines) {
                val start = line * lineStep
                for (i in 0 until length) sums[i + 1] = sums[i] + source[start + i * step]
                for (i in 0 until length) {
                    val from = max(0, i - radius)
                    val until = min(length, i + radius + 1)
                    target[start + i * step] = (sums[until] - sums[from]) / (until - from)
                }
            }
        }
    }
}
