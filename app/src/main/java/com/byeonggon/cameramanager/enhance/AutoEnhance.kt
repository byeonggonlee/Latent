package com.byeonggon.cameramanager.enhance

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** A tone curve per channel plus a saturation factor, applied to ARGB pixels in place. */
class ColorTransform(
    private val red: IntArray,
    private val green: IntArray,
    private val blue: IntArray,
    private val saturation: Float,
) {
    fun apply(pixels: IntArray, count: Int = pixels.size) {
        val saturationFixed = (saturation * 256).roundToInt()
        for (i in 0 until count) {
            val pixel = pixels[i]
            var r = red[(pixel ushr 16) and 0xFF]
            var g = green[(pixel ushr 8) and 0xFF]
            var b = blue[pixel and 0xFF]
            if (saturationFixed != 256) {
                val luma = lumaOf(r, g, b)
                r = (luma + (((r - luma) * saturationFixed) shr 8)).coerceIn(0, 255)
                g = (luma + (((g - luma) * saturationFixed) shr 8)).coerceIn(0, 255)
                b = (luma + (((b - luma) * saturationFixed) shr 8)).coerceIn(0, 255)
            }
            pixels[i] = (pixel and ALPHA_MASK) or (r shl 16) or (g shl 8) or b
        }
    }

    private companion object {
        const val ALPHA_MASK = 0xFF shl 24
    }
}

/** Rec. 709 luma in 8-bit fixed point. */
internal fun lumaOf(r: Int, g: Int, b: Int): Int = (r * 54 + g * 183 + b * 19) shr 8

/**
 * One-tap correction for camera JPEGs: stretches the tonal range, lifts underexposed midtones,
 * nudges the white balance toward neutral and adds a touch of saturation. Every step is capped
 * so a well-exposed photo is left almost untouched.
 */
object AutoEnhance {
    private const val CLIP_FRACTION = 0.005f
    private const val MAX_BLACK_POINT = 48
    private const val MIN_WHITE_POINT = 200
    private const val TARGET_MEDIAN = 0.46f
    private const val MIN_GAMMA = 0.75f
    private const val MAX_GAMMA = 1f // midtones are only ever lifted, never darkened
    private const val WHITE_BALANCE_STRENGTH = 0.5f
    private const val MAX_WHITE_BALANCE_SHIFT = 0.1f
    private const val MIDTONE_MIN = 40
    private const val MIDTONE_MAX = 215
    private const val SATURATION = 1.08f

    fun analyze(pixels: IntArray): ColorTransform {
        val histogram = IntArray(256)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var midtones = 0
        for (pixel in pixels) {
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = lumaOf(r, g, b)
            histogram[luma]++
            if (luma in MIDTONE_MIN..MIDTONE_MAX) {
                sumR += r
                sumG += g
                sumB += b
                midtones++
            }
        }

        val black = percentile(histogram, pixels.size, CLIP_FRACTION).coerceAtMost(MAX_BLACK_POINT)
        val white = percentile(histogram, pixels.size, 1f - CLIP_FRACTION).coerceAtLeast(MIN_WHITE_POINT)
        val range = (white - black).toFloat()
        val median = percentile(histogram, pixels.size, 0.5f)
        val stretchedMedian = ((median - black) / range).coerceIn(0.05f, 0.95f)
        val gamma = (ln(TARGET_MEDIAN) / ln(stretchedMedian)).coerceIn(MIN_GAMMA, MAX_GAMMA)

        // Gray-world white balance is only trustworthy with enough midtones to average over.
        val balanced = midtones > pixels.size / 20 && sumR > 0 && sumB > 0
        val gainR = if (balanced) dampedGain(sumG.toFloat() / sumR) else 1f
        val gainB = if (balanced) dampedGain(sumG.toFloat() / sumB) else 1f

        return ColorTransform(
            red = curve(black, range, gamma, gainR),
            green = curve(black, range, gamma, 1f),
            blue = curve(black, range, gamma, gainB),
            saturation = SATURATION,
        )
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val threshold = total * fraction
        var cumulative = 0
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative > threshold) return value
        }
        return histogram.lastIndex
    }

    private fun dampedGain(gain: Float): Float =
        (1f + (gain - 1f) * WHITE_BALANCE_STRENGTH)
            .coerceIn(1f - MAX_WHITE_BALANCE_SHIFT, 1f + MAX_WHITE_BALANCE_SHIFT)

    private fun curve(black: Int, range: Float, gamma: Float, gain: Float) = IntArray(256) { value ->
        val normalized = ((value * gain - black) / range).coerceIn(0f, 1f)
        (normalized.pow(gamma) * 255f).roundToInt()
    }
}
