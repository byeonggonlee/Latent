package com.byeonggon.latent.enhance

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A blemish to take out: the circle around ([x], [y]) is covered with the circle around ([fromX],
 * [fromY]), blended in at its edge and evened out to the brightness and colour around the blemish.
 * Positions are fractions of the whole upright picture's width and height; [radius] is a fraction of
 * its long side.
 */
data class Spot(val x: Float, val y: Float, val radius: Float, val fromX: Float, val fromY: Float)

/** Taking blemishes out of linear pictures. */
object Healing {
    /** The share of a spot's radius over which the patch fades into what surrounds it. */
    private const val FEATHER = 0.35f

    /** How far beyond a spot's edge the surroundings are taken that the patch is evened out to. */
    private const val RING = 1.35f

    /**
     * Takes [spots] out of this picture, in place and in their order. The spots are placed on the upright
     * picture; [orientation] is how this one is stored, for one on its side or mirrored.
     */
    fun heal(image: LinearImage, spots: List<Spot>, orientation: Orientation = Orientation.UPRIGHT) {
        for (spot in spots) heal(image, spot, orientation)
    }

    /**
     * Where to take a patch from for a blemish at ([x], [y]) of [radius]: nearby, where what lies
     * around the patch matches what lies around the blemish best. Returns the patch's centre.
     */
    fun sourceFor(image: LinearImage, x: Float, y: Float, radius: Float): Pair<Float, Float> {
        val longSide = max(image.width, image.height)
        val reach = radius * longSide
        val centreX = x * image.width
        val centreY = y * image.height
        val around = ring(image, centreX, centreY, reach)
        var best = Float.MAX_VALUE
        var chosen = x to y
        for (distance in floatArrayOf(2.4f, 3.2f, 4.2f)) for (step in 0 until DIRECTIONS) {
            val angle = 2 * PI * step / DIRECTIONS
            val fromX = centreX + (cos(angle) * distance * reach).toFloat()
            val fromY = centreY + (sin(angle) * distance * reach).toFloat()
            // The patch and what is around it have to lie within the picture.
            if (fromX - reach * RING < 0 || fromY - reach * RING < 0 || fromX + reach * RING >= image.width || fromY + reach * RING >= image.height) continue
            val candidate = ring(image, fromX, fromY, reach)
            // What counts is the pattern around the patch, not its brightness: that is evened out anyway.
            val meanAround = around.average().toFloat()
            val meanCandidate = candidate.average().toFloat()
            var difference = 0f
            for (index in around.indices) difference += abs((around[index] - meanAround) - (candidate[index] - meanCandidate))
            // Nearer patches are likelier to be of the same surface.
            difference *= 1f + 0.1f * distance
            if (difference < best) {
                best = difference
                chosen = fromX / image.width to fromY / image.height
            }
        }
        return chosen
    }

    private fun heal(image: LinearImage, spot: Spot, orientation: Orientation) {
        val (x, y) = stored(spot.x, spot.y, orientation)
        val (fromX, fromY) = stored(spot.fromX, spot.fromY, orientation)
        val radius = spot.radius * max(image.width, image.height)
        if (radius < 1f) return
        val centreX = x * image.width
        val centreY = y * image.height
        val shiftX = ((fromX * image.width) - centreX).roundToInt()
        val shiftY = ((fromY * image.height) - centreY).roundToInt()
        for (plane in listOf(image.red, image.green, image.blue)) {
            // The patch is evened out to its new surroundings: what tells the two apart is the difference of their averages.
            val correction = meanOfRing(plane, image.width, image.height, centreX, centreY, radius) -
                meanOfRing(plane, image.width, image.height, centreX + shiftX, centreY + shiftY, radius)
            val left = max(0, (centreX - radius).toInt())
            val right = min(image.width - 1, (centreX + radius).toInt() + 1)
            val top = max(0, (centreY - radius).toInt())
            val bottom = min(image.height - 1, (centreY + radius).toInt() + 1)
            for (row in top..bottom) for (column in left..right) {
                val distance = sqrt((column + 0.5f - centreX) * (column + 0.5f - centreX) + (row + 0.5f - centreY) * (row + 0.5f - centreY)) / radius
                if (distance >= 1f) continue
                val sourceColumn = (column + shiftX).coerceIn(0, image.width - 1)
                val sourceRow = (row + shiftY).coerceIn(0, image.height - 1)
                val patch = plane[sourceRow * image.width + sourceColumn] + correction
                val fade = ((1f - distance) / FEATHER).coerceAtMost(1f)
                val weight = fade * fade * (3f - 2f * fade)
                val at = row * image.width + column
                plane[at] = (plane[at] + (patch - plane[at]) * weight).roundToInt().coerceIn(0, LinearImage.MAX_LEVEL).toShort()
            }
        }
    }

    /** The average level of [plane] on a ring just outside a circle of [radius] around ([x], [y]). */
    private fun meanOfRing(plane: ShortArray, width: Int, height: Int, x: Float, y: Float, radius: Float): Float {
        var sum = 0f
        var count = 0
        for (step in 0 until RING_SAMPLES) {
            val angle = 2 * PI * step / RING_SAMPLES
            for (out in floatArrayOf(1.05f, RING)) {
                val column = (x + cos(angle) * radius * out).toInt()
                val row = (y + sin(angle) * radius * out).toInt()
                if (column !in 0 until width || row !in 0 until height) continue
                sum += plane[row * width + column]
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    /** The brightness on a ring around ([x], [y]), as levels, at evenly spread points. */
    private fun ring(image: LinearImage, x: Float, y: Float, radius: Float): FloatArray {
        val values = FloatArray(RING_SAMPLES * 2)
        for (step in 0 until RING_SAMPLES) {
            val angle = 2 * PI * step / RING_SAMPLES
            for ((index, out) in floatArrayOf(1.1f, RING).withIndex()) {
                val column = (x + cos(angle) * radius * out).toInt().coerceIn(0, image.width - 1)
                val row = (y + sin(angle) * radius * out).toInt().coerceIn(0, image.height - 1)
                val at = row * image.width + column
                values[step * 2 + index] = LUMA_RED * image.red[at] + LUMA_GREEN * image.green[at] + LUMA_BLUE * image.blue[at]
            }
        }
        return values
    }

    /** Where a point of the upright picture is in the picture as stored, both as fractions. */
    private fun stored(x: Float, y: Float, orientation: Orientation): Pair<Float, Float> {
        val (unturnedX, unturnedY) = when (orientation.quarterTurns.mod(4)) {
            1 -> y to 1f - x
            2 -> 1f - x to 1f - y
            3 -> 1f - y to x
            else -> x to y
        }
        // Turned back, the point lies in the stored picture mirrored, if its orientation mirrors it.
        return (if (orientation.mirrored) 1f - unturnedX else unturnedX) to unturnedY
    }

    private const val DIRECTIONS = 16
    private const val RING_SAMPLES = 32
}
