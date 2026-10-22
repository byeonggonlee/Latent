package com.byeonggon.latent.raw

import com.byeonggon.latent.enhance.LinearImage
import com.byeonggon.latent.enhance.forRowBands
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pending OpcodeList3 WarpRectilinear geometry, independent of Sony's lens tables.
 * DNG coordinates describe pixel centres in the active rectangle, before default crop/orientation.
 * Equations follow Adobe's DNG SDK dng_lens_correction.cpp; this implementation uses square pixels.
 * The crop-centred zoom is shared by all planes and by preview/export, as for Sony correction.
 */
class DngWarp private constructor(
    private val warps: List<Warp>,
    private val left: Int,
    private val top: Int,
    private val width: Int,
    private val height: Int,
) {
    private val inverseWarps = warps.asReversed()
    private val centerX = left + (width - 1) / 2.0
    private val centerY = top + (height - 1) / 2.0
    private val zoom = findZoom()

    /** Inverse composition: the last applied operation is sampled first, in SDK integer-centred coordinates. */
    internal fun sourcePosition(point: DoubleArray, plane: Int) {
        for (warp in inverseWarps) warp.map(point, plane)
    }

    internal fun straighten(image: LinearImage, raw: ArwImage, step: Int) {
        require(step > 0 && image.width == raw.cropWidth / step && image.height == raw.cropHeight / step)
        if (image.width < 2 || image.height < 2) return
        val blockCentre = (step - 1) / 2.0
        for ((plane, pixels) in arrayOf(image.red, image.green, image.blue).withIndex()) {
            val source = pixels.copyOf()
            forRowBands(image.height) { from, until ->
                val point = DoubleArray(2)
                for (y in from until until) for (x in 0 until image.width) {
                    point[0] = centerX + (raw.cropLeft + x * step + blockCentre - centerX) * zoom
                    point[1] = centerY + (raw.cropTop + y * step + blockCentre - centerY) * zoom
                    sourcePosition(point, plane)
                    // Proxy block centres share full geometry. At the outer half-block/remainder,
                    // nearest-edge replication is the usual low-resolution sampling approximation.
                    val sx = ((point[0] - raw.cropLeft - blockCentre) / step).coerceIn(0.0, image.width - 1.0)
                    val sy = ((point[1] - raw.cropTop - blockCentre) / step).coerceIn(0.0, image.height - 1.0)
                    val ix = sx.toInt().coerceAtMost(image.width - 2)
                    val iy = sy.toInt().coerceAtMost(image.height - 2)
                    val wx = sx - ix
                    val wy = sy - iy
                    val at = iy * image.width + ix
                    val upper = source[at] + (source[at + 1] - source[at]) * wx
                    val lower = source[at + image.width] + (source[at + image.width + 1] - source[at + image.width]) * wx
                    pixels[y * image.width + x] = (upper + (lower - upper) * wy + 0.5).toInt().toShort()
                }
            }
        }
    }

    /**
     * Interval bounds cover the interiors too: checking corners alone misses tangential extrema.
     * Subdivision reduces conservative interval overestimation. No full-size coordinate buffers.
     */
    private fun fits(scale: Double): Boolean {
        val minX = left.toDouble()
        val maxX = left + width - 1.0
        val minY = top.toDouble()
        val maxY = top + height - 1.0
        for (plane in 0..2) for (y in 0 until BOUND_CELLS) for (x in 0 until BOUND_CELLS) {
            var bx = Range(centerX + (minX + (maxX - minX) * x / BOUND_CELLS - centerX) * scale,
                centerX + (minX + (maxX - minX) * (x + 1) / BOUND_CELLS - centerX) * scale)
            var by = Range(centerY + (minY + (maxY - minY) * y / BOUND_CELLS - centerY) * scale,
                centerY + (minY + (maxY - minY) * (y + 1) / BOUND_CELLS - centerY) * scale)
            for (warp in inverseWarps) {
                val bounds = warp.bounds(bx, by, plane)
                bx = bounds.first
                by = bounds.second
                // Each intermediate image also needs real source pixels, not just the final composition.
                if (bx.lo < minX || bx.hi > maxX || by.lo < minY || by.hi > maxY) return false
            }
            if (!bx.lo.isFinite() || !bx.hi.isFinite() || !by.lo.isFinite() || !by.hi.isFinite() ||
                bx.lo < minX || bx.hi > maxX || by.lo < minY || by.hi > maxY) return false
        }
        return true
    }

    private fun findZoom(): Double {
        if (fits(1.0)) return 1.0
        // A profile which cannot map even the centre safely is not exposed to the editor.
        if (!fits(0.0)) return Double.NaN
        var low = 0.0
        var high = 1.0
        repeat(24) {
            val mid = (low + high) / 2
            if (fits(mid)) low = mid else high = mid
        }
        return low.takeIf { it > 0.01 } ?: Double.NaN
    }

    private class Warp(val coefficients: Array<DoubleArray>, val cx: Double, val cy: Double, val radius: Double) {
        val identity = coefficients.all { it.contentEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)) }

        fun map(point: DoubleArray, plane: Int) {
            val k = coefficients[if (coefficients.size == 1) 0 else plane]
            val dx = (point[0] - cx) / radius
            val dy = (point[1] - cy) / radius
            val xx = dx * dx
            val yy = dy * dy
            val r2 = min(1.0, xx + yy)
            val radial = k[0] + r2 * (k[1] + r2 * (k[2] + r2 * k[3]))
            point[0] = cx + radius * (dx * radial + 2 * k[4] * dx * dy + k[5] * (r2 + 2 * xx))
            point[1] = cy + radius * (dy * radial + 2 * k[5] * dx * dy + k[4] * (r2 + 2 * yy))
        }

        fun bounds(x: Range, y: Range, plane: Int): Pair<Range, Range> {
            val k = coefficients[if (coefficients.size == 1) 0 else plane]
            val dx = (x + -cx) * (1 / radius)
            val dy = (y + -cy) * (1 / radius)
            val xx = dx.squared()
            val yy = dy.squared()
            val r2 = (xx + yy).let { Range(min(1.0, it.lo), min(1.0, it.hi)) }
            val radial = ((r2 * k[3] + k[2]) * r2 + k[1]) * r2 + k[0]
            return (dx * radial + dx * dy * (2 * k[4]) + (r2 + xx * 2.0) * k[5]) * radius + cx to
                (dy * radial + dx * dy * (2 * k[5]) + (r2 + yy * 2.0) * k[4]) * radius + cy
        }
    }

    private data class Range(val lo: Double, val hi: Double) {
        operator fun plus(other: Range) = Range(lo + other.lo, hi + other.hi)
        operator fun plus(value: Double) = Range(lo + value, hi + value)
        operator fun times(value: Double) = if (value >= 0) Range(lo * value, hi * value) else Range(hi * value, lo * value)
        operator fun times(other: Range): Range {
            val a = lo * other.lo
            val b = lo * other.hi
            val c = hi * other.lo
            val d = hi * other.hi
            return Range(minOf(a, b, c, d), maxOf(a, b, c, d))
        }
        fun squared() = Range(if (lo <= 0 && hi >= 0) 0.0 else min(lo * lo, hi * hi), max(lo * lo, hi * hi))
    }

    internal companion object {
        private const val BOUND_CELLS = 8

        /** Broken/unsupported lists are all-or-nothing; never apply just one part of required geometry. */
        fun read(bytes: ByteArray?, active: IntArray, crop: IntArray): DngWarp? {
            if (bytes == null || bytes.size < 4) return null
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val count = data.int
            if (count !in 0..64) return null
            val warps = mutableListOf<Warp>()
            repeat(count) {
                if (data.remaining() < 16) return null
                val id = data.int
                val version = data.int
                val flags = data.int
                val length = data.int
                if (length < 0 || length > data.remaining() || flags and 3.inv() != 0) return null
                val end = data.position() + length
                if (id == 1) {
                    if (version !in 0x01000000..0x01030000 || length < 20) return null
                    val planes = data.int
                    if (planes != 1 && planes != 3 || length != 20 + 48 * planes) return null
                    val coefficients = Array(planes) { DoubleArray(6) { data.double } }
                    val x = data.double
                    val y = data.double
                    if (!coefficients.all { p -> p.all(Double::isFinite) } || x !in 0.0..1.0 || y !in 0.0..1.0) return null
                    val w = active[3] - active[1]
                    val h = active[2] - active[0]
                    val warp = Warp(coefficients, active[1] + w * x, active[0] + h * y,
                        hypot(w * max(x, 1 - x), h * max(y, 1 - y)))
                    if (!warp.identity) warps.add(warp)
                    if (warps.size > 8) return null
                } else if (flags and 1 == 0) return null
                data.position(end)
            }
            if (data.hasRemaining() || warps.isEmpty()) return null
            return DngWarp(warps.toList(), crop[0], crop[1], crop[2], crop[3]).takeIf { it.zoom.isFinite() }
        }
    }
}
