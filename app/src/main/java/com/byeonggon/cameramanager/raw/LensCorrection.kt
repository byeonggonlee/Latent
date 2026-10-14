package com.byeonggon.cameramanager.raw

import com.byeonggon.cameramanager.enhance.LinearImage
import com.byeonggon.cameramanager.enhance.forRowBands
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Applies a [LensProfile] to a picture of the given size: straightens distortion and lines the colour
 * channels up against each other.
 *
 * The profile's meaning is not documented by Sony. This follows what the camera does to its own
 * JPEGs, measured by matching them against the raw data of the same shots.
 */
class LensCorrection(private val profile: LensProfile, private val width: Int, private val height: Int) {
    private val centerX = (width - 1) / 2f
    private val centerY = (height - 1) / 2f

    /** Distances are measured in units of the distance from the centre to a corner. */
    private val cornerDistance = hypot(centerX, centerY)

    // For a corrected position, how far out the lens actually put that light, per colour.
    private val zoom = 1f / largestStretchAlongTheBorder()
    private val greenStretch = table { distortionAt(it) * zoom }
    private val redStretch = table { distortionAt(it) * aberrationAt(profile.redAberration, it) * zoom }
    private val blueStretch = table { distortionAt(it) * aberrationAt(profile.blueAberration, it) * zoom }

    /**
     * Redraws [image] the way a distortion-free lens would have: every pixel is fetched from where the
     * lens put it. Done in place, one colour at a time, as a second copy of a full-size picture would
     * take more memory than the app can count on.
     */
    fun straighten(image: LinearImage) {
        require(image.width == width && image.height == height) { "The correction was made for another size" }
        straighten(image.red, redStretch)
        straighten(image.green, greenStretch)
        straighten(image.blue, blueStretch)
    }

    private fun straighten(plane: ShortArray, stretch: FloatArray) {
        val source = plane.copyOf()
        forRowBands(height) { from, until ->
            for (y in from until until) {
                val offsetY = y - centerY
                val dy = offsetY / cornerDistance
                for (x in 0 until width) {
                    val offsetX = x - centerX
                    val dx = offsetX / cornerDistance
                    val by = stretch[index(dx * dx + dy * dy)]
                    plane[y * width + x] = sample(source, centerX + offsetX * by, centerY + offsetY * by)
                }
            }
        }
    }

    /** One colour at a position between pixels, blended from the four around it. */
    private fun sample(plane: ShortArray, x: Float, y: Float): Short {
        val fx = x.coerceIn(0f, width - 1f)
        val fy = y.coerceIn(0f, height - 1f)
        val left = fx.toInt().coerceAtMost(width - 2)
        val top = fy.toInt().coerceAtMost(height - 2)
        val wx = fx - left
        val wy = fy - top
        val row = top * width + left
        val upper = plane[row] + (plane[row + 1] - plane[row]) * wx
        val lower = plane[row + width] + (plane[row + width + 1] - plane[row + width]) * wx
        return (upper + (lower - upper) * wy + 0.5f).toInt().toShort()
    }

    private fun distortionAt(radius: Float) = 1f + interpolate(profile.distortion, radius) / 16384f

    private fun aberrationAt(values: IntArray, radius: Float) = 1f + interpolate(values, radius) / 2097152f

    /**
     * Straightening pulls some of the picture's edge inwards or pushes it out. The picture is zoomed
     * so that the edge still lands on real pixels: the camera does the same, mapping corner to corner.
     */
    private fun largestStretchAlongTheBorder(): Float {
        val nearestEdge = minOf(centerX, centerY) / cornerDistance
        var largest = 0f
        for (step in 0..BORDER_STEPS) {
            val radius = nearestEdge + (1f - nearestEdge) * step / BORDER_STEPS
            val distortion = distortionAt(radius)
            largest = maxOf(
                largest,
                distortion,
                distortion * aberrationAt(profile.redAberration, radius),
                distortion * aberrationAt(profile.blueAberration, radius),
            )
        }
        return largest
    }

    /** The profile's values are evenly spaced from the centre (first) to the corner (last). */
    private fun interpolate(values: IntArray, radius: Float): Float {
        val position = (radius * (values.size - 1)).coerceIn(0f, values.size - 1f)
        val below = position.toInt().coerceAtMost(values.size - 2)
        return values[below] + (values[below + 1] - values[below]) * (position - below)
    }

    /** Tabulates a function of distance over the squared distance, which saves a square root per pixel. */
    private fun table(function: (Float) -> Float) =
        FloatArray(TABLE_SIZE) { function(sqrt(it * MAX_SQUARED_RADIUS / (TABLE_SIZE - 1))) }

    private fun index(squaredRadius: Float) =
        (squaredRadius * ((TABLE_SIZE - 1) / MAX_SQUARED_RADIUS)).toInt().coerceIn(0, TABLE_SIZE - 1)

    private companion object {
        const val TABLE_SIZE = 8192
        const val MAX_SQUARED_RADIUS = 1.05f // a little beyond the corners
        const val BORDER_STEPS = 256
    }
}
