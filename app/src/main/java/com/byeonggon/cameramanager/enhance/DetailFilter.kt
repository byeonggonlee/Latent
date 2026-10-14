package com.byeonggon.cameramanager.enhance

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fine detail: texture, sharpening and noise reduction. Unlike the other corrections these look at a
 * pixel's neighbours, so they work on finished pixels, a band of rows at a time.
 *
 * [scale] is how many pixels of the picture at hand make up one pixel of the original: 1 for the
 * full-size picture, a quarter for a small preview of it. Sharpening and noise reduction happen at
 * the scale of single pixels of the original; in a preview much smaller than that they cannot be
 * seen, and are left out.
 */
class DetailFilter(adjustments: Adjustments, scale: Float) {
    private val pixelDetail = scale >= SMALLEST_SCALE_FOR_PIXEL_DETAIL
    private val texture = adjustments.texture * (if (adjustments.texture > 0f) TEXTURE_AT_FULL else SMOOTHING_AT_FULL)
    private val textureReach = (TEXTURE_REACH * scale).roundToInt()

    // A preview at half size has half as many pixels to tell an edge by; what is added to each shrinks along.
    private val sharpening = if (pixelDetail) adjustments.sharpening * SHARPENING_AT_FULL * min(1f, scale) else 0f
    private val noiseReduction = if (pixelDetail) adjustments.noiseReduction else 0f
    private val noiseReach = max(1, (NOISE_REACH * scale).roundToInt())
    private val colorNoiseReduction = if (pixelDetail) adjustments.colorNoiseReduction else 0f
    private val colorNoiseReach = max(1, (COLOR_NOISE_REACH * scale).roundToInt())

    private val texturing = texture != 0f && textureReach >= 1

    /** Whether there is anything to do at this scale. */
    val isNeeded: Boolean = texturing || sharpening > 0f || noiseReduction > 0f || colorNoiseReduction > 0f

    /** Rows above and below a band that its result depends on. */
    private val margin = max(if (texturing) 2 * textureReach else 0, max(2 * noiseReach, 2 * colorNoiseReach)) + 2

    /** The picture in [pixels] with its detail changed; [pixels] themselves are left as they are. */
    fun apply(pixels: IntArray, width: Int, height: Int): IntArray {
        val result = IntArray(width * height)
        apply(pixels, width, height) { top, rows, band -> band.copyInto(result, top * width, 0, rows * width) }
        return result
    }

    /**
     * Works out the picture in [pixels] with its detail changed and hands it to [write] band by band:
     * the rows from `top`, `rows` of them, in the first part of `band`. A full-size picture is too
     * large to hold a second copy of; this way the result can go straight to where it is needed.
     * [write] is called from several threads, but never by two at once.
     */
    fun apply(pixels: IntArray, width: Int, height: Int, write: (top: Int, rows: Int, band: IntArray) -> Unit) {
        // A few threads, each working its way down its own part of the picture: every thread needs room for a band.
        val parts = min(MOST_THREADS, max(1, height / BAND_ROWS))
        val lock = Any()
        IntStream.range(0, parts).parallel().forEach { part ->
            val partTop = (height.toLong() * part / parts).toInt()
            val partBottom = (height.toLong() * (part + 1) / parts).toInt()
            val band = IntArray(width * BAND_ROWS)
            var top = partTop
            while (top < partBottom) {
                val rows = min(BAND_ROWS, partBottom - top)
                process(pixels, width, height, top, rows, band)
                synchronized(lock) { write(top, rows, band) }
                top += rows
            }
        }
    }

    /** Fills [band] with the rows from [top] of the finished picture, reading as far around them as it takes. */
    private fun process(pixels: IntArray, width: Int, height: Int, top: Int, rows: Int, band: IntArray) {
        val first = max(0, top - margin)
        val last = min(height, top + rows + margin)
        val count = (last - first) * width
        val start = first * width
        val originalLuma = FloatArray(count) { index ->
            val pixel = pixels[start + index]
            (LUMA_RED * ((pixel ushr 16) and 0xFF) + LUMA_GREEN * ((pixel ushr 8) and 0xFF) + LUMA_BLUE * (pixel and 0xFF)) / 255f
        }
        var luma = originalLuma
        val bandHeight = last - first

        if (noiseReduction > 0f) {
            // Differences smaller than the noise are evened out; anything larger counts as part of the picture.
            val noise = NOISE_AT_FULL * noiseReduction
            luma = GuidedMap.smoothed(luma, luma, width, bandHeight, noiseReach, noise * noise)
        }

        var redLess: FloatArray? = null
        var blueLess: FloatArray? = null
        if (colorNoiseReduction > 0f) {
            // Colour, as how far red and blue are from the brightness, is smoothed wherever the brightness shows no edge.
            fun smoothed(shift: Int): FloatArray {
                val colour = FloatArray(count) { ((pixels[start + it] ushr shift) and 0xFF) / 255f - originalLuma[it] }
                val smooth = GuidedMap.smoothed(originalLuma, colour, width, bandHeight, colorNoiseReach, COLOR_EDGE_VARIANCE)
                for (index in 0 until count) colour[index] += (smooth[index] - colour[index]) * colorNoiseReduction
                return colour
            }
            redLess = smoothed(16)
            blueLess = smoothed(0)
        }

        if (texturing) {
            // What sets a pixel apart from the area around it, over a distance that skin, bark or fabric show their structure at.
            val area = GuidedMap.blurred(GuidedMap.blurred(luma, width, bandHeight, textureReach), width, bandHeight, textureReach)
            val textured = FloatArray(count)
            for (index in 0 until count) {
                val value = luma[index]
                val middle = (4f * value * (1f - value)).coerceIn(0f, 1f)
                textured[index] = value + texture * middle * (value - area[index])
            }
            luma = textured
        }

        if (sharpening > 0f) luma = sharpened(luma, width, bandHeight)

        val offset = (top - first) * width
        for (index in 0 until rows * width) {
            val at = offset + index
            val pixel = pixels[start + at]
            val r: Float
            val g: Float
            val b: Float
            if (redLess != null && blueLess != null) {
                r = luma[at] + redLess[at]
                b = luma[at] + blueLess[at]
                g = (luma[at] - LUMA_RED * r - LUMA_BLUE * b) / LUMA_GREEN
            } else {
                // Brightness is all that changed: every colour moves by the same amount.
                val change = luma[at] - originalLuma[at]
                r = ((pixel ushr 16) and 0xFF) / 255f + change
                g = ((pixel ushr 8) and 0xFF) / 255f + change
                b = (pixel and 0xFF) / 255f + change
            }
            band[index] = (pixel and OPAQUE) or (toByte(r) shl 16) or (toByte(g) shl 8) or toByte(b)
        }
    }

    /** Adds to every pixel what sets it apart from its immediate neighbours, unless that is too little to be more than noise. */
    private fun sharpened(luma: FloatArray, width: Int, height: Int): FloatArray {
        val across = FloatArray(luma.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val left = luma[row + max(0, x - 1)]
                val right = luma[row + min(width - 1, x + 1)]
                across[row + x] = (left + 2f * luma[row + x] + right) / 4f
            }
        }
        val result = FloatArray(luma.size)
        for (y in 0 until height) {
            val row = y * width
            val above = max(0, y - 1) * width
            val below = min(height - 1, y + 1) * width
            for (x in 0 until width) {
                val soft = (across[above + x] + 2f * across[row + x] + across[below + x]) / 4f
                val apart = luma[row + x] - soft
                val strength = abs(apart)
                result[row + x] = luma[row + x] + sharpening * apart * strength / (strength + FAINTEST_EDGE)
            }
        }
        return result
    }

    private fun toByte(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)

    private companion object {
        const val OPAQUE = 0xFF shl 24
        const val BAND_ROWS = 96
        const val MOST_THREADS = 4
        const val SMALLEST_SCALE_FOR_PIXEL_DETAIL = 0.4f

        /** Distances in pixels of the original picture. */
        const val TEXTURE_REACH = 8f
        const val NOISE_REACH = 2f
        const val COLOR_NOISE_REACH = 4f

        const val TEXTURE_AT_FULL = 1.2f
        const val SMOOTHING_AT_FULL = 0.8f
        const val SHARPENING_AT_FULL = 2.5f

        /** Differences in brightness below this are not sharpened to their full extent: they may be noise. */
        const val FAINTEST_EDGE = 0.012f

        /** How large differences in brightness are evened out by full noise reduction. */
        const val NOISE_AT_FULL = 0.05f
        const val COLOR_EDGE_VARIANCE = 0.0004f
    }
}
