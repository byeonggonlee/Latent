package com.byeonggon.cameramanager.enhance

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A painted mask laid out on a grid over the whole picture, so that looking up how much of it covers
 * a pixel takes no longer than for any other mask, however much was painted. [aspect] is the
 * picture's width over its height; brush strokes are round on the picture, not on the grid.
 */
class BrushRaster private constructor(mask: Mask, aspect: Float) {
    val width: Int
    val height: Int

    /** How much of the mask covers each cell, from 0 to 1, row by row; not yet inverted. */
    val coverage: FloatArray

    init {
        width = if (aspect >= 1f) SIDE else max(1, (SIDE * aspect).roundToInt())
        height = if (aspect >= 1f) max(1, (SIDE / aspect).roundToInt()) else SIDE
        coverage = FloatArray(width * height)
        // Distances are measured in cells, which are square: a cell is as wide as it is high.
        val longSide = max(width, height).toFloat()
        for (stroke in mask.strokes) {
            val radius = stroke.radius * longSide
            if (radius <= 0f) continue
            val inner = radius * (1f - mask.feather)
            val points = stroke.points
            val count = points.size / 2
            for (segment in 0 until max(1, count - 1)) {
                val fromX = points[2 * segment] * width
                val fromY = points[2 * segment + 1] * height
                val toX = if (count > 1) points[2 * segment + 2] * width else fromX
                val toY = if (count > 1) points[2 * segment + 3] * height else fromY
                stamp(fromX, fromY, toX, toY, radius, inner, stroke.erasing)
            }
        }
    }

    /** How much of the mask covers ([x], [y]), given as fractions of the picture, before inverting it. */
    fun weightAt(x: Float, y: Float): Float {
        val column = (x * width - 0.5f).coerceIn(0f, width - 1f)
        val row = (y * height - 0.5f).coerceIn(0f, height - 1f)
        val left = column.toInt()
        val top = row.toInt()
        val right = min(left + 1, width - 1)
        val bottom = min(top + 1, height - 1)
        val across = column - left
        val down = row - top
        val upper = coverage[top * width + left] + (coverage[top * width + right] - coverage[top * width + left]) * across
        val lower = coverage[bottom * width + left] + (coverage[bottom * width + right] - coverage[bottom * width + left]) * across
        return upper + (lower - upper) * down
    }

    /** Paints, or wipes out, a line from one point to another, fully out to [inner] and fading out to [radius]. */
    private fun stamp(fromX: Float, fromY: Float, toX: Float, toY: Float, radius: Float, inner: Float, erasing: Boolean) {
        val left = max(0, (min(fromX, toX) - radius).toInt())
        val right = min(width - 1, (max(fromX, toX) + radius).toInt() + 1)
        val top = max(0, (min(fromY, toY) - radius).toInt())
        val bottom = min(height - 1, (max(fromY, toY) + radius).toInt() + 1)
        val alongX = toX - fromX
        val alongY = toY - fromY
        val length = alongX * alongX + alongY * alongY
        for (row in top..bottom) for (column in left..right) {
            val x = column + 0.5f
            val y = row + 0.5f
            // The nearest point of the line.
            val share = if (length == 0f) 0f else (((x - fromX) * alongX + (y - fromY) * alongY) / length).coerceIn(0f, 1f)
            val apartX = x - (fromX + share * alongX)
            val apartY = y - (fromY + share * alongY)
            val distance = sqrt(apartX * apartX + apartY * apartY)
            if (distance >= radius) continue
            val reached = if (distance <= inner) 1f else ((radius - distance) / (radius - inner)).let { it * it * (3f - 2f * it) }
            val at = row * width + column
            coverage[at] = if (erasing) min(coverage[at], 1f - reached) else max(coverage[at], reached)
        }
    }

    companion object {
        /** Cells along the picture's long side. */
        private const val SIDE = 384

        private var last: BrushRaster? = null
        private var lastMask: Mask? = null
        private var lastAspect = 0f

        /**
         * The grid for [mask] on a picture [aspect] times as wide as high. The last one is kept, as the
         * same mask is looked up for every pixel of a picture.
         */
        @Synchronized
        fun of(mask: Mask, aspect: Float): BrushRaster {
            val kept = last
            if (kept != null && lastMask == mask && lastAspect == aspect) return kept
            return BrushRaster(mask, aspect).also {
                last = it
                lastMask = mask
                lastAspect = aspect
            }
        }
    }
}
