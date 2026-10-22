package com.byeonggon.latent.raw

import com.byeonggon.latent.enhance.LinearImage
import com.byeonggon.latent.enhance.forRowBands
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns decoded sensor data into a picture in linear light, in the camera's colours. Its tones are
 * left for [com.byeonggon.latent.enhance.ToneRenderer] to render.
 */
object RawDeveloper {
    /**
     * Develops at a fraction of the sensor's resolution by merging blocks of photosites into one pixel:
     * [cells] red-green-green-blue cells along each side, so 1 gives half the sensor's size and 2 a
     * quarter. Fast, free of demosaicing artefacts, and plenty for looking at a photo on a phone.
     */
    fun developPreview(image: ArwImage, cells: Int = 1, lensCorrection: Boolean = false): LinearImage {
        val result = LinearImage(image.cropWidth / (2 * cells), image.cropHeight / (2 * cells))
        val color = ColorStage(image, result, lensCorrection && image.dngWarp != null)
        if (image.channels == 3) {
            // Colours already filled in: each pixel of the result averages a block of the same size a mosaic would give.
            val side = 2 * cells
            val count = side * side
            val sensor = image.sensor
            forRowBands(result.height) { from, until ->
                for (y in from until until) for (x in 0 until result.width) {
                    var red = 0
                    var green = 0
                    var blue = 0
                    for (row in 0 until side) {
                        var at = ((image.cropTop + y * side + row) * image.width + image.cropLeft + x * side) * 3
                        repeat(side) {
                            red += color.red[sensor[at].toInt()]
                            green += color.green[sensor[at + 1].toInt()]
                            blue += color.blue[sensor[at + 2].toInt()]
                            at += 3
                        }
                    }
                    color.store(y * result.width + x, red / count, green / count, blue / count)
                }
            }
            color.finish(image, 2 * cells)
            return result
        }
        val sensor = image.sensor
        val perColor = cells * cells
        forRowBands(result.height) { from, until ->
            for (y in from until until) for (x in 0 until result.width) {
                var red = 0
                var green = 0
                var blue = 0
                for (row in 0 until cells) {
                    var top = (image.cropTop + 2 * (y * cells + row)) * image.width + image.cropLeft + 2 * x * cells
                    var bottom = top + image.width
                    repeat(cells) {
                        red += color.red[sensor[top].toInt()]
                        green += color.green[sensor[top + 1].toInt()] + color.green[sensor[bottom].toInt()]
                        blue += color.blue[sensor[bottom + 1].toInt()]
                        top += 2
                        bottom += 2
                    }
                }
                color.store(y * result.width + x, red / perColor, green / (2 * perColor), blue / perColor)
            }
        }
        color.finish(image, 2 * cells)
        return result
    }

    /**
     * Develops at full resolution, reconstructing the two colours each photosite did not record with
     * patterned pixel grouping (PPG): green first, along whichever of the row or the column changes
     * least, then red and blue from their differences to green, which vary far less than the colours
     * themselves. The few pixels along the edges, which lack the neighbours for it, average theirs.
     */
    fun develop(image: ArwImage, lensCorrection: Boolean = false): LinearImage {
        val width = image.cropWidth
        val height = image.cropHeight
        val result = LinearImage(width, height)
        val color = ColorStage(image, result, lensCorrection && image.dngWarp != null)
        if (image.channels == 3) {
            val sensor = image.sensor
            forRowBands(height) { from, until ->
                for (y in from until until) {
                    var at = ((image.cropTop + y) * image.width + image.cropLeft) * 3
                    for (x in 0 until width) {
                        color.store(y * width + x, color.red[sensor[at].toInt()], color.green[sensor[at + 1].toInt()], color.blue[sensor[at + 2].toInt()])
                        at += 3
                    }
                }
            }
            color.finish(image, 1)
            return result
        }
        // Until the colour stage at the end, the planes hold what the photosites recorded, white balanced.
        val red = result.red
        val green = result.green
        val blue = result.blue
        val sensor = image.sensor
        forRowBands(height) { from, until ->
            for (y in from until until) {
                var at = (image.cropTop + y) * image.width + image.cropLeft
                for (x in 0 until width) {
                    val value = sensor[at++].toInt()
                    val index = y * width + x
                    when (siteOf(x, y)) {
                        RED -> red[index] = color.red[value].toShort()
                        BLUE -> blue[index] = color.blue[value].toShort()
                        else -> green[index] = color.green[value].toShort()
                    }
                }
            }
        }
        val most = color.range

        // Green where red or blue was recorded: the estimate along the smoother direction, kept between its two green neighbours.
        forInterior(width, height) { x, y, i ->
            val site = siteOf(x, y)
            if (site == GREEN) return@forInterior
            val own = if (site == RED) red else blue
            val center = own[i].toInt()
            val down = width
            val acrossGuess = (green[i - 1] + center + green[i + 1]) * 2 - own[i - 2] - own[i + 2]
            val acrossDiff = (abs(own[i - 2] - center) + abs(own[i + 2] - center) + abs(green[i - 1] - green[i + 1])) * 3 +
                (abs(green[i + 3] - green[i + 1]) + abs(green[i - 3] - green[i - 1])) * 2
            val downGuess = (green[i - down] + center + green[i + down]) * 2 - own[i - 2 * down] - own[i + 2 * down]
            val downDiff = (abs(own[i - 2 * down] - center) + abs(own[i + 2 * down] - center) + abs(green[i - down] - green[i + down])) * 3 +
                (abs(green[i + 3 * down] - green[i + down]) + abs(green[i - 3 * down] - green[i - down])) * 2
            green[i] = if (downDiff < acrossDiff) {
                between(downGuess shr 2, green[i - down].toInt(), green[i + down].toInt())
            } else {
                between(acrossGuess shr 2, green[i - 1].toInt(), green[i + 1].toInt())
            }.toShort()
        }
        fillEdges(width, height, setOf(GREEN), red, green, blue)

        // Red and blue where green was recorded: one colour sits left and right of it, the other above and below.
        forInterior(width, height) { x, y, i ->
            if (siteOf(x, y) != GREEN) return@forInterior
            val beside = if (y and 1 == 0) red else blue
            val above = if (y and 1 == 0) blue else red
            val center = 2 * green[i]
            beside[i] = ((beside[i - 1] + beside[i + 1] + center - green[i - 1] - green[i + 1]) shr 1).coerceIn(0, most).toShort()
            above[i] = ((above[i - width] + above[i + width] + center - green[i - width] - green[i + width]) shr 1).coerceIn(0, most).toShort()
        }

        // Blue where red was recorded and red where blue was, along the diagonal that changes least.
        forInterior(width, height) { x, y, i ->
            val site = siteOf(x, y)
            if (site == GREEN) return@forInterior
            val other = if (site == RED) blue else red
            val center = 2 * green[i]
            val falling = width + 1
            val rising = width - 1
            val diffFalling = abs(other[i - falling] - other[i + falling]) + abs(green[i - falling] - green[i]) + abs(green[i + falling] - green[i])
            val diffRising = abs(other[i - rising] - other[i + rising]) + abs(green[i - rising] - green[i]) + abs(green[i + rising] - green[i])
            val guessFalling = other[i - falling] + other[i + falling] + center - green[i - falling] - green[i + falling]
            val guessRising = other[i - rising] + other[i + rising] + center - green[i - rising] - green[i + rising]
            val value = when {
                diffFalling < diffRising -> guessFalling shr 1
                diffRising < diffFalling -> guessRising shr 1
                else -> (guessFalling + guessRising) shr 2
            }
            other[i] = value.coerceIn(0, most).toShort()
        }
        fillEdges(width, height, setOf(RED, BLUE), red, green, blue)

        forRowBands(height) { from, until ->
            for (index in from * width until until * width) {
                color.store(index, red[index].toInt(), green[index].toInt(), blue[index].toInt())
            }
        }
        color.finish(image, 1)
        return result
    }

    /** Which colour the photosite at [x], [y] of the cropped picture recorded: red, green and blue in rows of red-green and green-blue. */
    private fun siteOf(x: Int, y: Int): Int = when {
        y and 1 == 0 -> if (x and 1 == 0) RED else GREEN
        else -> if (x and 1 == 0) GREEN else BLUE
    }

    /** Visits every pixel at least [EDGE] away from the picture's edges, with its index. */
    private inline fun forInterior(width: Int, height: Int, crossinline visit: (x: Int, y: Int, index: Int) -> Unit) {
        forRowBands(height - 2 * EDGE) { from, until ->
            for (y in from + EDGE until until + EDGE) {
                for (x in EDGE until width - EDGE) visit(x, y, y * width + x)
            }
        }
    }

    /**
     * Fills in [colors] for the pixels within [EDGE] of the picture's edges, which did not record
     * them, with the average of the photosites around that did; the picture is mirrored beyond its edges.
     */
    private fun fillEdges(width: Int, height: Int, colors: Set<Int>, red: ShortArray, green: ShortArray, blue: ShortArray) {
        fun mirrored(value: Int, size: Int) = when {
            value < 0 -> -value
            value >= size -> 2 * size - 2 - value
            else -> value
        }
        for (y in 0 until height) {
            val edgeRow = y < EDGE || y >= height - EDGE
            for (x in 0 until width) {
                if (!edgeRow && x >= EDGE && x < width - EDGE) continue
                val own = siteOf(x, y)
                for (wanted in colors) {
                    if (wanted == own) continue
                    val plane = when (wanted) {
                        RED -> red
                        BLUE -> blue
                        else -> green
                    }
                    var sum = 0
                    var count = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = mirrored(x + dx, width)
                        val ny = mirrored(y + dy, height)
                        if (siteOf(nx, ny) == wanted) {
                            sum += plane[ny * width + nx]
                            count++
                        }
                    }
                    if (count > 0) plane[y * width + x] = (sum / count).toShort()
                }
            }
        }
    }

    /** [value] kept between [one] and [other], whichever way round they are. */
    private fun between(value: Int, one: Int, other: Int): Int = value.coerceIn(min(one, other), max(one, other))

    /** White balance, highlight clipping and the camera's colour matrix, in that order. */
    private class ColorStage(image: ArwImage, private val into: LinearImage, private val deferMatrix: Boolean) {
        /** The most a white-balanced sensor value can be. */
        val range = image.whiteLevel - image.blackLevel

        // Per sensor value: black removed, white balance applied, and cut off where the sensor clips
        // so that blown highlights stay neutral.
        val red = balanced(image, image.whiteBalance[0])
        val green = balanced(image, image.whiteBalance[1])
        val blue = balanced(image, image.whiteBalance[2])

        private val matrix = withSaturation(normalizedRows(image.colorMatrix), image.saturation)
        private val toLevel = LinearImage.MAX_LEVEL.toFloat() / range

        fun store(index: Int, r: Int, g: Int, b: Int) {
            if (deferMatrix) {
                into.red[index] = r.toShort()
                into.green[index] = g.toShort()
                into.blue[index] = b.toShort()
            } else convert(index, r, g, b)
        }

        fun finish(image: ArwImage, step: Int) {
            if (!deferMatrix) return
            // OpcodeList3 planes are camera RGB, before the colour matrix mixes them.
            image.dngWarp!!.straighten(into, image, step)
            forRowBands(into.height) { from, until ->
                for (index in from * into.width until until * into.width) {
                    convert(index, into.red[index].toInt(), into.green[index].toInt(), into.blue[index].toInt())
                }
            }
        }

        private fun convert(index: Int, r: Int, g: Int, b: Int) {
            into.red[index] = level(matrix[0] * r + matrix[1] * g + matrix[2] * b)
            into.green[index] = level(matrix[3] * r + matrix[4] * g + matrix[5] * b)
            into.blue[index] = level(matrix[6] * r + matrix[7] * g + matrix[8] * b)
        }

        private fun level(value: Float) = (value * toLevel + 0.5f).toInt().coerceIn(0, LinearImage.MAX_LEVEL).toShort()

        private fun balanced(image: ArwImage, gain: Float) = IntArray(SENSOR_VALUES) { value ->
            ((value - image.blackLevel) * gain).roundToInt().coerceIn(0, range)
        }

        /** Rows are scaled to sum to one, so that neutral sensor colours come out exactly neutral. */
        private fun normalizedRows(matrix: FloatArray) = FloatArray(9) { index ->
            val row = index / 3 * 3
            matrix[index] / (matrix[row] + matrix[row + 1] + matrix[row + 2])
        }

        /** Folds a saturation change into the matrix: every colour moves away from its own brightness. */
        private fun withSaturation(matrix: FloatArray, saturation: Float): FloatArray {
            val luma = FloatArray(3) { column ->
                LUMA_RED * matrix[column] + LUMA_GREEN * matrix[3 + column] + LUMA_BLUE * matrix[6 + column]
            }
            return FloatArray(9) { index -> luma[index % 3] + (matrix[index] - luma[index % 3]) * saturation }
        }
    }

    private const val SENSOR_VALUES = 1 shl 15
    private const val RED = 0
    private const val GREEN = 1
    private const val BLUE = 2

    /** How far the reconstruction reaches around a pixel; nearer the edges, pixels average their neighbours. */
    private const val EDGE = 3
    private const val LUMA_RED = 0.2126f
    private const val LUMA_GREEN = 0.7152f
    private const val LUMA_BLUE = 0.0722f
}
