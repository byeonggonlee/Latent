package com.byeonggon.cameramanager.enhance

import kotlin.math.exp
import kotlin.math.pow

/**
 * The brightness and contrast of the camera's own JPEGs: how bright it shows each amount of light.
 * Fitted to what a ZV-E10 did to each brightness level, measured pixel by pixel on raw files and the
 * JPEGs of the same shots: the deepest shadows pass through unchanged, midtones are lifted about a
 * stop and a half, and highlights roll off gently instead of clipping. The camera adapts its curve to
 * each scene; this is the middle of what it did.
 */
object CameraCurve {
    private const val MIDTONE_LIFT = 3.46f
    private const val SHADOW_GAIN = 0.29f // times MIDTONE_LIFT is one: shadows keep their brightness
    private const val MIDTONE_ONSET = 0.04f
    private val LIFTED_AT_CLIPPING = lifted(1f)

    /** The display value, from 0 to 1 in sRGB, of [linear] light, where 1 is as much as the sensor can take. */
    fun display(linear: Float): Float = srgb(lifted(linear.coerceAtLeast(0f)) / LIFTED_AT_CLIPPING).coerceIn(0f, 1f)

    /** [display] for every [LinearImage] level. */
    val displayOfLevel = FloatArray(LinearImage.LEVELS) { display(it / LinearImage.MAX_LEVEL.toFloat()) }

    /** For each 8-bit display value, the [LinearImage] level that is shown as that value. */
    val levelOfDisplay = ShortArray(256) { value ->
        val wanted = value / 255f
        var below = 0
        var above = LinearImage.MAX_LEVEL
        while (above - below > 1) {
            val middle = (below + above) / 2
            if (displayOfLevel[middle] < wanted) below = middle else above = middle
        }
        (if (wanted - displayOfLevel[below] < displayOfLevel[above] - wanted) below else above).toShort()
    }

    private fun lifted(linear: Float): Float {
        val squared = linear * linear
        val contrast = SHADOW_GAIN + (1f - SHADOW_GAIN) * squared / (squared + MIDTONE_ONSET * MIDTONE_ONSET)
        return 1f - exp(-MIDTONE_LIFT * linear * contrast)
    }

    private fun srgb(linear: Float): Float =
        if (linear <= 0.0031308f) linear * 12.92f else 1.055f * linear.pow(1f / 2.4f) - 0.055f
}
