package com.byeonggon.cameramanager.enhance

import kotlin.math.ln
import kotlin.math.pow

/**
 * What auto enhance decided for one picture: a tone curve for each colour, taking display values
 * from 0 to 1 to their corrected values, and how much more vivid colours should become.
 */
class AutoLevels(
    private val black: Float,
    private val range: Float,
    private val gamma: Float,
    private val redGain: Float,
    private val blueGain: Float,
    val saturation: Float,
) {
    fun red(value: Float): Float = curve(balanced(value, redGain))

    fun green(value: Float): Float = curve(value)

    fun blue(value: Float): Float = curve(balanced(value, blueGain))

    /** The white balance fades out towards white, which has no cast to remove and would only take one on. */
    private fun balanced(value: Float, gain: Float): Float {
        val squared = value * value
        return value * (1f + (gain - 1f) * (1f - squared * squared))
    }

    private fun curve(value: Float): Float = ((value - black) / range).coerceIn(0f, 1f).pow(gamma)
}

/** Rec. 709 luma in 8-bit fixed point. */
internal fun lumaOf(r: Int, g: Int, b: Int): Int = (r * 54 + g * 183 + b * 19) shr 8

/**
 * One-tap correction: stretches the tonal range, lifts underexposed midtones, nudges the white
 * balance toward neutral and adds a touch of saturation. Every step is capped so a well-exposed
 * photo is left almost untouched.
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

    /** Judges a picture by its sRGB [pixels]; a small copy of the picture is enough. */
    fun analyze(pixels: IntArray): AutoLevels {
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
        return AutoLevels(
            black = black / 255f,
            range = range / 255f,
            gamma = gamma,
            redGain = if (balanced) dampedGain(sumG.toFloat() / sumR) else 1f,
            blueGain = if (balanced) dampedGain(sumG.toFloat() / sumB) else 1f,
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
}
