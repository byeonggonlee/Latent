package com.byeonggon.cameramanager.raw

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** A finished picture: ARGB pixels in sRGB, row by row. */
class DevelopedImage(val width: Int, val height: Int, val pixels: IntArray)

/** Maps linear light, from black to the sensor's clipping point, onto 8-bit sRGB values. */
internal class ToneCurve(function: (Float) -> Float) {
    val lut = IntArray(SIZE) { (function(it / (SIZE - 1f)).coerceIn(0f, 1f) * 255f).roundToInt() }

    companion object {
        const val SIZE = 1 shl 14

        fun srgb(linear: Float): Float =
            if (linear <= 0.0031308f) linear * 12.92f else 1.055f * linear.pow(1f / 2.4f) - 0.055f

        /** Plain sRGB encoding: what the sensor saw, with no rendering intent. */
        val LINEAR = ToneCurve(::srgb)

        /**
         * The brightness and contrast of the camera's own JPEGs. Fitted to what a ZV-E10 did to each
         * brightness level, measured pixel by pixel on raw files and the JPEGs of the same shots:
         * the deepest shadows pass through unchanged, midtones are lifted about a stop and a half,
         * and highlights roll off gently instead of clipping. The camera adapts its curve to each
         * scene; this is the middle of what it did.
         */
        val CAMERA = ToneCurve { linear -> srgb(lifted(linear) / lifted(1f)) }

        private fun lifted(linear: Float): Float {
            val squared = linear * linear
            val contrast = SHADOW_GAIN + (1f - SHADOW_GAIN) * squared / (squared + MIDTONE_ONSET * MIDTONE_ONSET)
            return 1f - exp(-MIDTONE_LIFT * linear * contrast)
        }

        private const val MIDTONE_LIFT = 3.46f
        private const val SHADOW_GAIN = 0.29f // times MIDTONE_LIFT is one: shadows keep their brightness
        private const val MIDTONE_ONSET = 0.04f
    }
}

/** How sensor colours are rendered: a tone curve and how vivid colours are relative to the camera's matrix. */
internal class Look(val tone: ToneCurve, val saturation: Float) {
    companion object {
        /** Close to the camera's JPEGs; this saturation leaves the smallest difference from them. */
        val CAMERA = Look(ToneCurve.CAMERA, saturation = 1.45f)
    }
}

/** Turns decoded sensor data into a picture. */
object RawDeveloper {
    /**
     * Develops at half the sensor's resolution by merging each 2x2 cell of photosites into one pixel.
     * Fast, free of demosaicing artefacts, and plenty for looking at a photo on a phone.
     */
    fun developPreview(image: ArwImage): DevelopedImage = developPreview(image, Look.CAMERA)

    /** Develops at full resolution, reconstructing the two colours each photosite did not record. */
    fun develop(image: ArwImage): DevelopedImage = develop(image, Look.CAMERA)

    internal fun developPreview(image: ArwImage, look: Look): DevelopedImage {
        val width = image.cropWidth / 2
        val height = image.cropHeight / 2
        val pixels = IntArray(width * height)
        val color = ColorStage(image, look)
        val sensor = image.sensor
        for (y in 0 until height) {
            var top = (image.cropTop + 2 * y) * image.width + image.cropLeft
            var bottom = top + image.width
            for (x in 0 until width) {
                val red = color.red[sensor[top].toInt()]
                val green = (color.green[sensor[top + 1].toInt()] + color.green[sensor[bottom].toInt()]) / 2
                val blue = color.blue[sensor[bottom + 1].toInt()]
                pixels[y * width + x] = color.render(red, green, blue)
                top += 2
                bottom += 2
            }
        }
        return DevelopedImage(width, height, pixels)
    }

    /**
     * Fills in the missing colours with the Malvar-He-Cutler filters: a neighbourhood average of the
     * wanted colour, sharpened by how much the photosite's own colour deviates from its surroundings.
     */
    internal fun develop(image: ArwImage, look: Look): DevelopedImage {
        val width = image.cropWidth
        val height = image.cropHeight
        val pixels = IntArray(width * height)
        val color = ColorStage(image, look)
        val s = image.sensor
        val w = image.width
        val red = color.red
        val green = color.green
        val blue = color.blue
        for (y in 0 until height) {
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
                pixels[y * width + x] = color.render(r, g, b)
                i++
            }
        }
        return DevelopedImage(width, height, pixels)
    }

    /** White balance, highlight clipping, the camera's colour matrix and the tone curve, in that order. */
    private class ColorStage(image: ArwImage, look: Look) {
        private val range = image.whiteLevel - image.blackLevel

        // Per sensor value: black removed, white balance applied, and cut off where the sensor clips
        // so that blown highlights stay neutral.
        val red = balanced(image, image.whiteBalance[0])
        val green = balanced(image, image.whiteBalance[1])
        val blue = balanced(image, image.whiteBalance[2])

        private val matrix = withSaturation(normalizedRows(image.colorMatrix), look.saturation)
        private val lut = look.tone.lut
        private val toIndex = (ToneCurve.SIZE - 1f) / range

        fun render(r: Int, g: Int, b: Int): Int {
            val red = lut[index(matrix[0] * r + matrix[1] * g + matrix[2] * b)]
            val green = lut[index(matrix[3] * r + matrix[4] * g + matrix[5] * b)]
            val blue = lut[index(matrix[6] * r + matrix[7] * g + matrix[8] * b)]
            return OPAQUE or (red shl 16) or (green shl 8) or blue
        }

        private fun index(value: Float) = (value * toIndex).toInt().coerceIn(0, ToneCurve.SIZE - 1)

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

    private const val OPAQUE = 0xFF shl 24
    private const val SENSOR_VALUES = 1 shl 15
    private const val LUMA_RED = 0.2126f
    private const val LUMA_GREEN = 0.7152f
    private const val LUMA_BLUE = 0.0722f
}
