package com.byeonggon.latent.enhance

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A painted or detected mask laid out on a grid over the whole picture, so that looking up how much of
 * it covers a pixel takes no longer than for any other mask, however much was painted. [aspect] is the
 * picture's width over its height; brush strokes are round on the picture, not on the grid.
 *
 * A painted mask starts out empty. A detected one starts from the outline kept in [MaskStore], at that
 * outline's size, with its edge softened by the mask's feather; inverted, it covers the rest of the
 * picture instead. Brush strokes then add to it or take from it, so that painting on a background mask
 * adds to the background. Without its outline a detected mask covers nothing at all, inverted or not.
 */
class BrushRaster private constructor(mask: Mask, aspect: Float) {
    val width: Int
    val height: Int

    /** How much of the mask covers each cell, from 0 to 1, row by row, inverted where the mask is. */
    val coverage: FloatArray

    init {
        val outline = if (mask.shape == MaskShape.DETECTED) mask.detection?.let { MaskStore.load(it.raster) } else null
        when {
            outline != null -> {
                width = outline.width
                height = outline.height
                coverage = softened(outline.coverage(), mask.feather)
                if (mask.inverted) invert()
                paint(mask)
            }
            mask.shape == MaskShape.DETECTED -> {
                // Lost, or never found: no part of the picture is told apart from the rest.
                width = 1
                height = 1
                coverage = FloatArray(1)
            }
            else -> {
                width = if (aspect >= 1f) SIDE else max(1, (SIDE * aspect).roundToInt())
                height = if (aspect >= 1f) max(1, (SIDE / aspect).roundToInt()) else SIDE
                coverage = FloatArray(width * height)
                paint(mask)
                if (mask.inverted) invert()
            }
        }
    }

    /** How much of the mask covers ([x], [y]), given as fractions of the picture. */
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

    private fun invert() {
        for (index in coverage.indices) coverage[index] = 1f - coverage[index]
    }

    /** [outline] with its edge spread over up to [FEATHER_REACH] of the long side, the more the higher [feather] is. */
    private fun softened(outline: FloatArray, feather: Float): FloatArray {
        val radius = (feather * FEATHER_REACH * max(width, height)).roundToInt()
        if (radius < 1) return outline
        // Two box blurs in a row fade out evenly, without the steps one leaves at its edges.
        return GuidedMap.blurred(GuidedMap.blurred(outline, width, height, radius), width, height, radius)
    }

    private fun paint(mask: Mask) {
        // Distances are measured in cells, which are square: a cell is as wide as it is high.
        val longSide = max(width, height).toFloat()
        for (stroke in mask.strokes) {
            val radius = stroke.radius * longSide
            val points = stroke.points
            val count = points.size / 2
            // A stroke without a whole point, as only a damaged or foreign edit holds, paints nothing.
            if (radius <= 0f || count == 0) continue
            val inner = radius * (1f - mask.feather)
            for (segment in 0 until max(1, count - 1)) {
                val fromX = points[2 * segment] * width
                val fromY = points[2 * segment + 1] * height
                val toX = if (count > 1) points[2 * segment + 2] * width else fromX
                val toY = if (count > 1) points[2 * segment + 3] * height else fromY
                stamp(fromX, fromY, toX, toY, radius, inner, stroke.erasing)
            }
        }
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
        /** Cells along the picture's long side, for a painted mask. */
        private const val SIDE = 384

        /** How far a detected mask's edge spreads at full feather, as a fraction of the picture's long side. */
        private const val FEATHER_REACH = 0.03f

        /** Grids kept: as many as a photo can have masks, all looked up in turn for every rendering. */
        private const val KEPT = 6

        // In the order they were last asked for.
        private val kept = LinkedHashMap<Pair<Mask, Float>, BrushRaster>(8, 0.75f, true)

        /**
         * The grid for [mask] on a picture [aspect] times as wide as high. The last few are kept, as the
         * same masks are looked up for every pixel of a picture.
         */
        @Synchronized
        fun of(mask: Mask, aspect: Float): BrushRaster = kept.getOrPut(mask to aspect) { BrushRaster(mask, aspect) }.also {
            val eldest = kept.entries.iterator()
            while (kept.size > KEPT) {
                eldest.next()
                eldest.remove()
            }
        }

        /** Lets go of the grids kept, as when the outlines they were made from may have changed. */
        @Synchronized
        internal fun forget() = kept.clear()
    }
}
