package com.byeonggon.cameramanager.enhance

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Brightens or darkens shadows and highlights by a factor that depends on how bright a pixel's
 * surroundings are rather than the pixel itself. Neighbouring pixels then change by the same factor,
 * which keeps the detail between them where a plain tone curve would flatten it.
 */
internal class ShadowsAndHighlights(shadows: Float, highlights: Float) {
    /** The factor for surroundings of every brightness, from black (first) to white (last). */
    private val gain = FloatArray(STEPS + 1) { step ->
        val level = step / STEPS.toFloat()
        if (step == 0) SHADOW_GAIN.pow(shadows) else adjusted(level, shadows, highlights) / level
    }

    /** How much of the factor applies to a pixel of every brightness: all of it, except close to white. */
    private val share = FloatArray(STEPS + 1) { step ->
        val nearWhite = ((step / STEPS.toFloat() - WHITE_ONSET) / (1f - WHITE_ONSET)).coerceAtLeast(0f)
        1f - nearWhite * nearWhite
    }

    /**
     * The factor for a pixel that is [luma] bright in surroundings that are [around] bright. White
     * stays white whatever surrounds it: a blown sky would turn gray otherwise, and a lamp in the dark
     * would be changed along with the shadows around it.
     */
    fun gainFor(around: Float, luma: Float): Float =
        1f + (gain[step(around)] - 1f) * share[step(luma)]

    private fun step(level: Float) = (level * STEPS).toInt().coerceIn(0, STEPS)

    /**
     * The tone a brightness [level] is taken to. Shadows are scaled the more the darker they are, and
     * black stays black; highlights are moved against white the same way, and white stays white.
     */
    private fun adjusted(level: Float, shadows: Float, highlights: Float): Float {
        val dark = if (level < SHADOWS_END) 1f - level / SHADOWS_END else 0f
        val lifted = level * SHADOW_GAIN.pow(shadows * dark * dark)
        val bright = if (lifted > HIGHLIGHTS_START) (lifted - HIGHLIGHTS_START) / (1f - HIGHLIGHTS_START) else 0f
        return 1f - (1f - lifted) * HIGHLIGHT_GAIN.pow(-highlights * bright * bright)
    }

    private companion object {
        const val STEPS = 1024
        const val SHADOW_GAIN = 2f
        const val SHADOWS_END = 0.5f
        const val HIGHLIGHT_GAIN = 3f
        const val HIGHLIGHTS_START = 0.5f
        const val WHITE_ONSET = 0.8f
    }
}

/**
 * Haze: light scattered on its way to the camera, which lays a veil of its own colour over the
 * picture, the thicker the further away things are. Where a picture has haze, even its darkest
 * colour channel is bright; that is how the veil's thickness is told (the "dark channel prior").
 * Taking the veil away, or laying more of it on, is done pixel by pixel in [ToneRenderer].
 *
 * The picture is given as a small copy, [width] by [height] pixels, as it is shown.
 */
internal class Haze(
    red: FloatArray,
    green: FloatArray,
    blue: FloatArray,
    brightness: FloatArray,
    width: Int,
    height: Int,
    amount: Float,
) {
    /** The colour of the veil. */
    val airRed: Float
    val airGreen: Float
    val airBlue: Float

    /** How much of each place's own light comes through the veil, from 0 to 1; null when haze is added evenly. */
    val clearness: GuidedMap?

    /** For every clearness, how far a colour has to be moved away from the veil's colour, as a multiple of their distance. */
    private val stretch = FloatArray(STEPS + 1) { step ->
        val clear = max(step / STEPS.toFloat(), LEAST_CLEARNESS)
        if (amount >= 0f) 1f / (1f - amount * (1f - clear)) - 1f else 1f / (1f - amount * ADDED_HAZE) - 1f
    }

    init {
        val size = red.size
        val reach = max(1, (max(width, height) * PATCH).toInt())
        val darkest = lowestNearby(FloatArray(size) { min(red[it], min(green[it], blue[it])) }, width, height, reach)
        // The veil shows its own colour where it is thickest: among the brightest of the dark channel.
        val threshold = darkest.sortedArray()[(size * (1f - THICKEST_SHARE)).toInt().coerceIn(0, size - 1)]
        var sumRed = 0f
        var sumGreen = 0f
        var sumBlue = 0f
        var count = 0
        for (index in 0 until size) {
            if (darkest[index] >= threshold) {
                sumRed += red[index]
                sumGreen += green[index]
                sumBlue += blue[index]
                count++
            }
        }
        // Never darker than a middle gray: what is taken for a veil in a dark picture is not haze.
        airRed = max(sumRed / count, DARKEST_VEIL)
        airGreen = max(sumGreen / count, DARKEST_VEIL)
        airBlue = max(sumBlue / count, DARKEST_VEIL)

        clearness = if (amount > 0f) {
            val veiled = FloatArray(size) { min(red[it] / airRed, min(green[it] / airGreen, blue[it] / airBlue)).coerceAtMost(1f) }
            val thickness = lowestNearby(veiled, width, height, reach)
            val clear = FloatArray(size) { 1f - KEPT_HAZE * thickness[it] }
            GuidedMap.of(brightness, clear, width, height, reach * 2, EDGE_VARIANCE)
        } else {
            null
        }
    }

    fun stretchFor(clearness: Float): Float = stretch[(clearness * STEPS).toInt().coerceIn(0, STEPS)]

    /** For every pixel, the lowest value up to [reach] pixels away in each direction. */
    private fun lowestNearby(values: FloatArray, width: Int, height: Int, reach: Int): FloatArray {
        val across = FloatArray(values.size)
        for (y in 0 until height) for (x in 0 until width) {
            var lowest = Float.MAX_VALUE
            for (near in max(0, x - reach)..min(width - 1, x + reach)) lowest = min(lowest, values[y * width + near])
            across[y * width + x] = lowest
        }
        val result = FloatArray(values.size)
        for (y in 0 until height) for (x in 0 until width) {
            var lowest = Float.MAX_VALUE
            for (near in max(0, y - reach)..min(height - 1, y + reach)) lowest = min(lowest, across[near * width + x])
            result[y * width + x] = lowest
        }
        return result
    }

    private companion object {
        const val STEPS = 256

        /** The size of the patches the dark channel is taken over, as a fraction of the picture's long side. */
        const val PATCH = 0.02f
        const val THICKEST_SHARE = 0.01f
        const val DARKEST_VEIL = 0.5f

        /** A trace of haze is left even at full strength; a picture with none at all looks unnatural. */
        const val KEPT_HAZE = 0.9f
        const val LEAST_CLEARNESS = 0.35f
        const val ADDED_HAZE = 1.2f
        const val EDGE_VARIANCE = 0.001f
    }
}
