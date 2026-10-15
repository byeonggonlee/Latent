package com.byeonggon.latent.enhance

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds how far a picture leans: most things people photograph on purpose, such as the horizon,
 * the walls of buildings and the edges of tables, run straight across or straight up. Where the strong
 * edges of a picture agree on a direction a little off true, that is how far it is tilted.
 */
object AutoLevel {
    /** Size of the picture looked at, on its long side: enough to follow the lines, and quick. */
    private const val EDGE = 640

    /** Tilts further than this are left to the person: they are more likely meant. */
    private const val WINDOW_DEGREES = 15f

    private const val BIN_DEGREES = 0.25f

    /** Edges weaker than this many times the average are ignored. */
    private const val STRONG = 2f

    /**
     * The directions within this of the strongest one count as agreeing with it. Single pixels give
     * the direction of an edge only roughly; it is the many of them that settle it.
     */
    private const val AGREEING_DEGREES = 2f

    /** What share of the edges leaning less than the window that must agree for the picture to count as having a direction. */
    private const val MIN_AGREEING_SHARE = 0.25

    /** What share of all strong edges must lean less than the window, or the picture is mostly diagonals and curves. */
    private const val MIN_WINDOW_SHARE = 0.15

    /**
     * How far the picture is turned clockwise from true, in degrees, between -15 and 15: a horizon that
     * falls towards the right gives a positive number. Null where the picture shows no clear direction.
     */
    fun tiltOf(picture: LinearImage): Float? {
        val small = picture.downscaled(EDGE)
        val width = small.width
        val height = small.height
        if (width < 16 || height < 16) return null
        val brightness = FloatArray(width * height) { index ->
            val light = 0.3f * small.red[index] + 0.59f * small.green[index] + 0.11f * small.blue[index]
            // The square root is a rough way to what the eye sees as even steps of brightness.
            sqrt(light.coerceAtLeast(0f))
        }
        // Seen from a few pixels away, a nearly level edge is a smooth slope and not a staircase of single steps.
        blur(brightness, width, height)
        blur(brightness, width, height)
        val across = FloatArray(width * height)
        val down = FloatArray(width * height)
        var sum = 0.0
        var count = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val at = y * width + x
            val gx = (brightness[at - width + 1] + 2 * brightness[at + 1] + brightness[at + width + 1]) -
                (brightness[at - width - 1] + 2 * brightness[at - 1] + brightness[at + width - 1])
            val gy = (brightness[at + width - 1] + 2 * brightness[at + width] + brightness[at + width + 1]) -
                (brightness[at - width - 1] + 2 * brightness[at - width] + brightness[at - width + 1])
            across[at] = gx
            down[at] = gy
            sum += sqrt(gx * gx + gy * gy)
            count++
        }
        if (count == 0 || sum <= 0.0) return null
        val threshold = STRONG * (sum / count).toFloat()

        val bins = (2 * WINDOW_DEGREES / BIN_DEGREES).roundToInt()
        val weights = DoubleArray(bins)
        var all = 0.0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val at = y * width + x
            val strength = sqrt(across[at] * across[at] + down[at] * down[at])
            if (strength < threshold) continue
            all += strength
            // An edge runs at right angles to the way brightness changes; modulo a quarter turn the two are the same.
            val lean = lean(Math.toDegrees(atan2(down[at].toDouble(), across[at].toDouble())).toFloat())
            if (abs(lean) >= WINDOW_DEGREES) continue
            weights[((lean + WINDOW_DEGREES) / BIN_DEGREES).toInt().coerceIn(0, bins - 1)] += strength.toDouble()
        }
        val inWindow = weights.sum()
        if (all <= 0.0 || inWindow < MIN_WINDOW_SHARE * all) return null

        // The strongest direction, seen through some smoothing, and then the average of what agrees with it.
        val reach = (AGREEING_DEGREES / BIN_DEGREES).roundToInt()
        var best = 0
        var bestWeight = -1.0
        for (bin in 0 until bins) {
            val near = weightAround(weights, bin, reach)
            if (near > bestWeight) {
                bestWeight = near
                best = bin
            }
        }
        if (bestWeight < MIN_AGREEING_SHARE * inWindow) return null
        // Averaging within reach of the average, so that the answer rests on all that lean the same way.
        var centre = -WINDOW_DEGREES + (best + 0.5f) * BIN_DEGREES
        repeat(3) {
            var weighted = 0.0
            var total = 0.0
            val from = ((centre - AGREEING_DEGREES + WINDOW_DEGREES) / BIN_DEGREES).toInt().coerceIn(0, bins - 1)
            val to = ((centre + AGREEING_DEGREES + WINDOW_DEGREES) / BIN_DEGREES).toInt().coerceIn(0, bins - 1)
            for (bin in from..to) {
                weighted += weights[bin] * (-WINDOW_DEGREES + (bin + 0.5f) * BIN_DEGREES)
                total += weights[bin]
            }
            if (total > 0.0) centre = (weighted / total).toFloat()
        }
        return (centre * 10).roundToInt() / 10f
    }

    private fun weightAround(weights: DoubleArray, bin: Int, reach: Int): Double {
        var sum = 0.0
        for (other in (bin - reach).coerceAtLeast(0)..(bin + reach).coerceAtMost(weights.size - 1)) sum += weights[other]
        return sum
    }

    /** Smooths [values] in place with a weighted average of each pixel and the two on each side, first along rows, then down columns. */
    private fun blur(values: FloatArray, width: Int, height: Int) {
        val kernel = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)
        val line = FloatArray(maxOf(width, height))
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (k in -2..2) sum += kernel[k + 2] * values[y * width + (x + k).coerceIn(0, width - 1)]
                line[x] = sum
            }
            System.arraycopy(line, 0, values, y * width, width)
        }
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sum = 0f
                for (k in -2..2) sum += kernel[k + 2] * values[(y + k).coerceIn(0, height - 1) * width + x]
                line[y] = sum
            }
            for (y in 0 until height) values[y * width + x] = line[y]
        }
    }

    /** An angle in degrees brought into the range of -45 up to 45, where turning by a quarter makes no difference. */
    private fun lean(degrees: Float): Float {
        var folded = (degrees + 45f) % 90f
        if (folded < 0f) folded += 90f
        return folded - 45f
    }
}
