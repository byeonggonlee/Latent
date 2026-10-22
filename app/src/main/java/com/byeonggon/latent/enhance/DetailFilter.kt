package com.byeonggon.latent.enhance

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
    internal val texture = adjustments.texture * (if (adjustments.texture > 0f) TEXTURE_AT_FULL else SMOOTHING_AT_FULL)
    internal val textureReach = (TEXTURE_REACH * scale).roundToInt()

    // A preview at half size has half as many pixels to tell an edge by; what is added to each shrinks along.
    internal val sharpening = if (pixelDetail) adjustments.sharpening * SHARPENING_AT_FULL * min(1f, scale) else 0f
    internal val noiseReduction = if (pixelDetail) adjustments.noiseReduction else 0f
    internal val noiseReach = max(1, (NOISE_REACH * scale).roundToInt())
    internal val colorNoiseReduction = if (pixelDetail) adjustments.colorNoiseReduction else 0f
    internal val colorNoiseReach = max(1, (COLOR_NOISE_REACH * scale).roundToInt())

    internal val texturing = texture != 0f && textureReach >= 1
    private val glow = adjustments.glow * GLOW_AT_FULL

    /** Whether there is anything to do at this scale. */
    val isNeeded: Boolean = texturing || sharpening > 0f || noiseReduction > 0f || colorNoiseReduction > 0f || glow > 0f

    /** Rows above and below a band that its result depends on. */
    internal val reach get() = margin

    /**
     * Noise reduction, texture and sharpening work one on the other's result, so what a pixel depends on reaches as far
     * as theirs together: each filter twice (a guided filter's two averages, texture's two blurs), sharpening one row.
     * Colour noise is worked out beside them, from the picture as it was. Two rows more to spare.
     */
    private val margin = max(
        (if (noiseReduction > 0f) 2 * noiseReach else 0) + (if (texturing) 2 * textureReach else 0) + (if (sharpening > 0f) 1 else 0),
        if (colorNoiseReduction > 0f) 2 * colorNoiseReach else 0,
    ) + 2

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
        // The glow spreads far, so it is worked out once for the whole picture, at a small size.
        val glowing = if (glow > 0f) Glow(pixels, width, height) else null
        // Sharpening alone is over on the processor before the picture would be on the graphics processor and back.
        val worthIt = noiseReduction > 0f || colorNoiseReduction > 0f || texturing
        if (useGraphicsProcessor && worthIt && width * height >= SMALLEST_FOR_GRAPHICS_PROCESSOR) {
            val gpu = GpuDetail.instance()
            val laid: (IntArray, Int, Int) -> Unit = { band, top, rows -> glowing?.lay(band, width, top, rows, glow) }
            if (gpu != null && gpu.apply(this, pixels, width, height, laid, write)) return
        }
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
                if (glowing != null) glowing.lay(band, width, top, rows, glow)
                synchronized(lock) { write(top, rows, band) }
                top += rows
            }
        }
    }

    /** Fills [band] with the rows from [top] of the finished picture, reading as far around them as it takes. Internal for tests. */
    internal fun process(pixels: IntArray, width: Int, height: Int, top: Int, rows: Int, band: IntArray) {
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

    /**
     * The light that the bright parts of a picture, [width] by [height] pixels, spread around them:
     * the picture shrunk, blurred far and kept small, with its darker parts left out.
     */
    private class Glow(pixels: IntArray, private val width: Int, private val height: Int) {
        private val block = max(1, max(width, height) / GLOW_SIDE)
        private val smallWidth = max(1, width / block)
        private val smallHeight = max(1, height / block)
        private val red: FloatArray
        private val green: FloatArray
        private val blue: FloatArray

        init {
            val planes = Array(3) { FloatArray(smallWidth * smallHeight) }
            for (y in 0 until smallHeight) for (x in 0 until smallWidth) {
                var sumRed = 0f
                var sumGreen = 0f
                var sumBlue = 0f
                for (row in y * block until minOf(height, (y + 1) * block)) for (column in x * block until minOf(width, (x + 1) * block)) {
                    val pixel = pixels[row * width + column]
                    sumRed += (pixel ushr 16) and 0xFF
                    sumGreen += (pixel ushr 8) and 0xFF
                    sumBlue += pixel and 0xFF
                }
                val count = (minOf(height, (y + 1) * block) - y * block) * (minOf(width, (x + 1) * block) - x * block) * 255f
                planes[0][y * smallWidth + x] = sumRed / count
                planes[1][y * smallWidth + x] = sumGreen / count
                planes[2][y * smallWidth + x] = sumBlue / count
            }
            val reach = max(1, (max(smallWidth, smallHeight) * GLOW_REACH).roundToInt())
            fun spread(plane: FloatArray): FloatArray {
                // Only what is bright gives off light; twice blurred is close to a soft, round spread.
                for (index in plane.indices) plane[index] = ((plane[index] - GLOW_THRESHOLD) / (1f - GLOW_THRESHOLD)).coerceAtLeast(0f)
                return GuidedMap.blurred(GuidedMap.blurred(plane, smallWidth, smallHeight, reach), smallWidth, smallHeight, reach)
            }
            red = spread(planes[0])
            green = spread(planes[1])
            blue = spread(planes[2])
        }

        /** Lays the glow over the rows from [top] in [band], [amount] strong, as light added to light. */
        fun lay(band: IntArray, frameWidth: Int, top: Int, rows: Int, amount: Float) {
            val columns = GuidedMap.columns(smallWidth, frameWidth)
            for (row in 0 until rows) {
                val position = ((top + row + 0.5f) * smallHeight / height - 0.5f).coerceIn(0f, smallHeight - 1f)
                val upper = position.toInt() * smallWidth
                val lower = min(position.toInt() + 1, smallHeight - 1) * smallWidth
                val down = position - position.toInt()
                for (x in 0 until frameWidth) {
                    val left = columns.left[x]
                    val right = columns.right[x]
                    val across = columns.weight[x]
                    fun sample(plane: FloatArray): Float {
                        val above = plane[upper + left] + (plane[upper + right] - plane[upper + left]) * across
                        val below = plane[lower + left] + (plane[lower + right] - plane[lower + left]) * across
                        return (above + (below - above) * down) * amount
                    }
                    val at = row * frameWidth + x
                    val pixel = band[at]
                    // Screening: light adds to light without ever going past white.
                    fun screened(value: Int, light: Float) = (255f - (255f - value) * (1f - light.coerceIn(0f, 1f)) + 0.5f).toInt()
                    band[at] = (pixel and OPAQUE) or
                        (screened((pixel ushr 16) and 0xFF, sample(red)) shl 16) or
                        (screened((pixel ushr 8) and 0xFF, sample(green)) shl 8) or
                        screened(pixel and 0xFF, sample(blue))
                }
            }
        }
    }

    internal companion object {
        /**
         * Whether detail may be worked out on the graphics processor where there is one. Off, the
         * processor does it: for comparing the two.
         */
        @Volatile
        var useGraphicsProcessor = true

        /** Below this many pixels, handing a picture to the graphics processor and back costs more than it saves. */
        private const val SMALLEST_FOR_GRAPHICS_PROCESSOR = 250_000

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

        /** The glow is worked out on a copy this many pixels along its long side, and spreads this share of it. */
        const val GLOW_SIDE = 400
        const val GLOW_REACH = 0.025f
        const val GLOW_THRESHOLD = 0.35f
        const val GLOW_AT_FULL = 1.6f
    }
}
