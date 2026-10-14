package com.byeonggon.cameramanager.enhance

import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min

/**
 * A picture in linear light: for each colour, how bright every pixel is on a scale from 0 (black) to
 * [MAX_LEVEL] (as much as the sensor can take), row by row. Raw files are developed into this and
 * JPEGs are converted to it, so that both kinds of photo are corrected by the same code.
 */
class LinearImage(
    val width: Int,
    val height: Int,
    val red: ShortArray,
    val green: ShortArray,
    val blue: ShortArray,
) {
    constructor(width: Int, height: Int) :
        this(width, height, ShortArray(width * height), ShortArray(width * height), ShortArray(width * height))

    fun copy() = LinearImage(width, height, red.copyOf(), green.copyOf(), blue.copyOf())

    /** A copy no larger than [maxEdge] on its long side, each of its pixels the average of a block of this one's. */
    fun downscaled(maxEdge: Int): LinearImage {
        val block = (max(width, height) + maxEdge - 1) / maxEdge
        if (block <= 1) return LinearImage(width, height, red.copyOf(), green.copyOf(), blue.copyOf())
        val small = LinearImage(max(1, width / block), max(1, height / block))
        val columns = min(block, width)
        val rows = min(block, height)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            var sumRed = 0
            var sumGreen = 0
            var sumBlue = 0
            for (row in 0 until rows) {
                var index = (y * block + row) * width + x * block
                repeat(columns) {
                    sumRed += red[index]
                    sumGreen += green[index]
                    sumBlue += blue[index]
                    index++
                }
            }
            val count = rows * columns
            val at = y * small.width + x
            small.red[at] = (sumRed / count).toShort()
            small.green[at] = (sumGreen / count).toShort()
            small.blue[at] = (sumBlue / count).toShort()
        }
        return small
    }

    /** A copy turned clockwise by [quarterTurns] quarter turns. */
    fun turned(quarterTurns: Int): LinearImage {
        val turns = quarterTurns.mod(4)
        if (turns == 0) return this
        val sideways = turns % 2 == 1
        val result = LinearImage(if (sideways) height else width, if (sideways) width else height)
        for (y in 0 until result.height) for (x in 0 until result.width) {
            val from = when (turns) {
                1 -> (height - 1 - x) * width + y
                2 -> (height - 1 - y) * width + (width - 1 - x)
                else -> x * width + (width - 1 - y)
            }
            val to = y * result.width + x
            result.red[to] = red[from]
            result.green[to] = green[from]
            result.blue[to] = blue[from]
        }
        return result
    }

    companion object {
        const val LEVELS = 1 shl 14
        const val MAX_LEVEL = LEVELS - 1

        /**
         * Takes finished sRGB pixels back to the light they were made from, assuming the camera's
         * rendering. Rendering the result unchanged gives exactly the same pixels again.
         */
        fun fromArgb(pixels: IntArray, width: Int, height: Int): LinearImage {
            val image = LinearImage(width, height)
            val level = CameraCurve.levelOfDisplay
            for (index in 0 until width * height) {
                val pixel = pixels[index]
                image.red[index] = level[(pixel ushr 16) and 0xFF]
                image.green[index] = level[(pixel ushr 8) and 0xFF]
                image.blue[index] = level[pixel and 0xFF]
            }
            return image
        }
    }
}

/**
 * Runs [work] on bands of rows on all processor cores at once, and returns when every row is done.
 * Whole pictures are too much for one core to keep up with a moving slider.
 */
internal fun forRowBands(rows: Int, work: (from: Int, until: Int) -> Unit) {
    // More bands than cores, so that a slow core does not hold up the rest with its share.
    val bands = min(rows / MIN_ROWS_PER_BAND, Runtime.getRuntime().availableProcessors() * BANDS_PER_CORE)
    if (bands <= 1) {
        work(0, rows)
        return
    }
    IntStream.range(0, bands).parallel().forEach { band ->
        work((rows.toLong() * band / bands).toInt(), (rows.toLong() * (band + 1) / bands).toInt())
    }
}

private const val MIN_ROWS_PER_BAND = 16
private const val BANDS_PER_CORE = 4
