package com.byeonggon.latent.enhance

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Auto that sets the sliders rather than making a correction of its own, so that what it
 * did can be seen and taken further. It judges the picture by [overview], a small copy of it, as the
 * camera renders it, and only moves what needs moving. A raw's colours are the camera's white balance,
 * which is kept but for a nudge ([WhiteEstimate.nudge]); another picture's are taken half the way to gray.
 */
object AutoSettings {
    private const val TARGET_MIDDLE = 0.46f
    private const val MOST_EXPOSURE = 1.5f
    private const val BRIGHT = 0.92f
    private const val DARK = 0.05f

    fun of(overview: LinearImage, raw: Boolean = false): Adjustments {
        val count = overview.width * overview.height
        val display = CameraCurve.displayOfLevel
        val luma = FloatArray(count)
        var sumRed = 0.0
        var sumGreen = 0.0
        var sumBlue = 0.0
        var midtones = 0
        var vividness = 0.0
        for (index in 0 until count) {
            val red = display[overview.red[index].toInt()]
            val green = display[overview.green[index].toInt()]
            val blue = display[overview.blue[index].toInt()]
            val value = LUMA_RED * red + LUMA_GREEN * green + LUMA_BLUE * blue
            luma[index] = value
            val most = max(red, max(green, blue))
            if (most > 0f) vividness += (most - min(red, min(green, blue))) / most
            if (value in 0.15f..0.85f) {
                sumRed += red
                sumGreen += green
                sumBlue += blue
                midtones++
            }
        }
        val sorted = luma.sortedArray()
        fun percentile(share: Float) = sorted[(share * (count - 1)).toInt()]

        // Exposure brings the middle of the picture to a middle gray, as far as it is off and within limits.
        val middle = percentile(0.5f)
        val exposure = exposureFor(middle, sorted).coerceIn(-MOST_EXPOSURE, MOST_EXPOSURE)
        val brightShare = sorted.count { it > BRIGHT } / count.toFloat()
        val darkShare = sorted.count { it < DARK } / count.toFloat()
        val spread = percentile(0.9f) - percentile(0.1f)

        // Gray-world: the midtones should average out to gray; half the way there is taken.
        val balanced = midtones > count / 20
        val warmth = if (balanced) ln(sumBlue / sumRed).toFloat() * 0.5f else 0f
        val greenness = if (balanced) ln(sumGreen * 2 / (sumRed + sumBlue)).toFloat() * 0.5f else 0f
        val (temperature, tint) = if (raw) {
            WhiteEstimate.nudge(overview)
        } else {
            (warmth / 0.35f).coerceIn(-0.4f, 0.4f) to (greenness / 0.25f).coerceIn(-0.3f, 0.3f)
        }

        return Adjustments(
            exposure = rounded(exposure, 20),
            contrast = rounded(((0.55f - spread) * 0.8f).coerceIn(-0.1f, 0.3f), 100),
            // Blown and crushed parts are recovered as far as they take up much of the picture.
            highlights = rounded(-(brightShare * 4f).coerceIn(0f, 0.6f), 100),
            shadows = rounded((darkShare * 4f).coerceIn(0f, 0.5f), 100),
            whites = rounded(((BRIGHT - percentile(0.995f)) * 3f).coerceIn(-0.3f, 0.5f), 100),
            blacks = rounded(((DARK - percentile(0.005f)) * 4f).coerceIn(-0.5f, 0.2f), 100),
            temperature = rounded(temperature, 100),
            tint = rounded(tint, 100),
            vibrance = rounded(((0.35f - (vividness / count).toFloat()) * 0.8f).coerceIn(0f, 0.3f), 100),
        )
    }

    /** The stops of exposure that take the picture's middle tone to [TARGET_MIDDLE], through the camera's curve. */
    private fun exposureFor(middle: Float, sorted: FloatArray): Float {
        if (middle <= 0f || abs(middle - TARGET_MIDDLE) < 0.04f) return 0f
        // The middle tone back to light, then the light that gives the target, both through the curve.
        val light = levelOf(middle)
        val wanted = levelOf(TARGET_MIDDLE)
        if (light <= 0f) return MOST_EXPOSURE
        // Bright pictures are pulled down less: their brightness is often what was meant.
        val stops = (ln(wanted / light) / ln(2f))
        return if (stops < 0f) stops * 0.6f else stops * if (sorted.last() > 0.98f) 0.7f else 1f
    }

    /** The linear level, from 0 to 1, that the camera's curve shows as [display]. */
    private fun levelOf(display: Float): Float {
        val levels = CameraCurve.displayOfLevel
        var below = 0
        var above = levels.size - 1
        while (above - below > 1) {
            val middle = (below + above) / 2
            if (levels[middle] < display) below = middle else above = middle
        }
        return above / (levels.size - 1f)
    }

    private fun rounded(value: Float, stepsPerUnit: Int): Float = Math.round(value * stepsPerUnit) / stepsPerUnit.toFloat() + 0f
}
