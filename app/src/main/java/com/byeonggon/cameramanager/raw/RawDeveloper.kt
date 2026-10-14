package com.byeonggon.cameramanager.raw

import com.byeonggon.cameramanager.enhance.LinearImage
import com.byeonggon.cameramanager.enhance.forRowBands
import kotlin.math.roundToInt

/**
 * Turns decoded sensor data into a picture in linear light, in the camera's colours. Its tones are
 * left for [com.byeonggon.cameramanager.enhance.ToneRenderer] to render.
 */
object RawDeveloper {
    /**
     * Develops at a fraction of the sensor's resolution by merging blocks of photosites into one pixel:
     * [cells] red-green-green-blue cells along each side, so 1 gives half the sensor's size and 2 a
     * quarter. Fast, free of demosaicing artefacts, and plenty for looking at a photo on a phone.
     */
    fun developPreview(image: ArwImage, cells: Int = 1): LinearImage {
        val result = LinearImage(image.cropWidth / (2 * cells), image.cropHeight / (2 * cells))
        val color = ColorStage(image, result)
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
        return result
    }

    /**
     * Develops at full resolution, reconstructing the two colours each photosite did not record with
     * the Malvar-He-Cutler filters: a neighbourhood average of the wanted colour, sharpened by how
     * much the photosite's own colour deviates from its surroundings.
     */
    fun develop(image: ArwImage): LinearImage {
        val width = image.cropWidth
        val result = LinearImage(width, image.cropHeight)
        val color = ColorStage(image, result)
        val s = image.sensor
        val w = image.width
        val red = color.red
        val green = color.green
        val blue = color.blue
        forRowBands(result.height) { from, until ->
            for (y in from until until) {
                val blueRow = y and 1 == 1
                var i = (image.cropTop + y) * w + image.cropLeft
                for (x in 0 until width) {
                    val oddColumn = x and 1 == 1
                    val r: Int
                    val g: Int
                    val b: Int
                    if (blueRow == oddColumn) {
                        // A red or blue photosite: `own` is its colour, `other` the one on its diagonals.
                        val own = if (blueRow) blue else red
                        val other = if (blueRow) red else blue
                        val center = own[s[i].toInt()]
                        val farOwn = own[s[i - 2].toInt()] + own[s[i + 2].toInt()] + own[s[i - 2 * w].toInt()] + own[s[i + 2 * w].toInt()]
                        val nearGreen = green[s[i - 1].toInt()] + green[s[i + 1].toInt()] + green[s[i - w].toInt()] + green[s[i + w].toInt()]
                        val diagonal = other[s[i - w - 1].toInt()] + other[s[i - w + 1].toInt()] + other[s[i + w - 1].toInt()] + other[s[i + w + 1].toInt()]
                        g = (4 * center + 2 * nearGreen - farOwn) / 8
                        val opposite = (12 * center + 4 * diagonal - 3 * farOwn) / 16
                        r = if (blueRow) opposite else center
                        b = if (blueRow) center else opposite
                    } else {
                        // A green photosite: one colour sits left and right of it, the other above and below.
                        val center = green[s[i].toInt()]
                        val diagonalGreen = green[s[i - w - 1].toInt()] + green[s[i - w + 1].toInt()] + green[s[i + w - 1].toInt()] + green[s[i + w + 1].toInt()]
                        val farHorizontal = green[s[i - 2].toInt()] + green[s[i + 2].toInt()]
                        val farVertical = green[s[i - 2 * w].toInt()] + green[s[i + 2 * w].toInt()]
                        val beside = if (blueRow) blue else red
                        val above = if (blueRow) red else blue
                        val fromSides = (10 * center + 8 * (beside[s[i - 1].toInt()] + beside[s[i + 1].toInt()]) -
                            2 * diagonalGreen - 2 * farHorizontal + farVertical) / 16
                        val fromAbove = (10 * center + 8 * (above[s[i - w].toInt()] + above[s[i + w].toInt()]) -
                            2 * diagonalGreen - 2 * farVertical + farHorizontal) / 16
                        g = center
                        r = if (blueRow) fromAbove else fromSides
                        b = if (blueRow) fromSides else fromAbove
                    }
                    color.store(y * width + x, r, g, b)
                    i++
                }
            }
        }
        return result
    }

    /** White balance, highlight clipping and the camera's colour matrix, in that order. */
    private class ColorStage(image: ArwImage, private val into: LinearImage) {
        private val range = image.whiteLevel - image.blackLevel

        // Per sensor value: black removed, white balance applied, and cut off where the sensor clips
        // so that blown highlights stay neutral.
        val red = balanced(image, image.whiteBalance[0])
        val green = balanced(image, image.whiteBalance[1])
        val blue = balanced(image, image.whiteBalance[2])

        private val matrix = withSaturation(normalizedRows(image.colorMatrix), CAMERA_SATURATION)
        private val toLevel = LinearImage.MAX_LEVEL.toFloat() / range

        fun store(index: Int, r: Int, g: Int, b: Int) {
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

    /** How vivid the camera's JPEGs are relative to its colour matrix; this leaves the smallest difference from them. */
    private const val CAMERA_SATURATION = 1.45f
    private const val SENSOR_VALUES = 1 shl 15
    private const val LUMA_RED = 0.2126f
    private const val LUMA_GREEN = 0.7152f
    private const val LUMA_BLUE = 0.0722f
}
