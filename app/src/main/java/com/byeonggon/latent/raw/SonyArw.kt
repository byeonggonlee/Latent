package com.byeonggon.latent.raw

import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sony's own corrections for the lens and settings a picture was taken with, as stored in the raw file.
 * Each array holds one value per step of distance, from the image centre (first) to the corners (last).
 *
 * The file also describes how the lens darkens the corners, but that needs no correcting: measured
 * against the camera's JPEGs, the raw data is already as bright in the corners as the JPEG is.
 */
class LensProfile(
    val distortion: IntArray,
    val redAberration: IntArray,
    val blueAberration: IntArray,
)

/** A decoded raw file, a Sony ARW or a DNG: what the sensor recorded plus what is needed to turn it into a picture. */
class ArwImage(
    /** Size of [sensor], which is slightly larger than the picture. */
    val width: Int,
    val height: Int,
    /** The part of the sensor that makes up the picture. */
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val blackLevel: Int,
    val whiteLevel: Int,
    /** Gains for red, green and blue that make neutral objects neutral, as chosen by the camera. */
    val whiteBalance: FloatArray,
    /** Row-major 3x3 matrix from white-balanced sensor colours to linear sRGB. */
    val colorMatrix: FloatArray,
    /** How much more vivid than [colorMatrix] alone the colours are made, to come near the camera's own pictures. */
    val saturation: Float,
    val lens: LensProfile?,
    /** How the camera was held, as an Exif orientation value; the sensor data itself is never rotated. */
    val orientation: Int,
    /**
     * One value per photosite, in rows. From the corner of the crop on, they are laid out as red/green over
     * green/blue; values reach above [whiteLevel] only where a correction has brightened them.
     */
    val sensor: ShortArray,
) {
    /**
     * The same file with only a part of its picture: [width] by [height] pixels, [left] across and
     * [top] down the picture. All four have to be even, as the colours repeat every two photosites.
     */
    fun part(left: Int, top: Int, width: Int, height: Int) = ArwImage(
        this.width, this.height, cropLeft + left, cropTop + top, width, height,
        blackLevel, whiteLevel, whiteBalance, colorMatrix, saturation, lens, orientation, sensor,
    )
}

/** Reads the compressed raw format written by Sony cameras such as the ZV-E10 (ARW 2.3). */
object SonyArw {
    private const val TAG_MAKE = 0x010F
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_SUB_IFDS = 0x014A
    private const val TAG_WIDTH = 0x0100
    private const val TAG_HEIGHT = 0x0101
    private const val TAG_COMPRESSION = 0x0103
    private const val TAG_STRIP_OFFSETS = 0x0111
    private const val TAG_STRIP_BYTE_COUNTS = 0x0117
    private const val TAG_CFA_PATTERN = 0x828E
    private const val TAG_TONE_CURVE = 0x7010
    private const val TAG_ABERRATION = 0x7035
    private const val TAG_DISTORTION = 0x7037
    private const val TAG_BLACK_LEVEL = 0x7310
    private const val TAG_WHITE_BALANCE = 0x7313
    private const val TAG_WHITE_LEVEL = 0xC61D
    private const val TAG_CROP_ORIGIN = 0xC61F
    private const val TAG_CROP_SIZE = 0xC620
    private const val TAG_PRIVATE_DATA = 0xC634
    private const val TAG_PRIVATE_OFFSET = 0x7200
    private const val TAG_PRIVATE_LENGTH = 0x7201
    private const val TAG_PRIVATE_KEY = 0x7221
    private const val TAG_COLOR_MATRIX = 0x7800

    private const val COMPRESSION_SONY = 32767
    private val RGGB = intArrayOf(0, 1, 1, 2)

    /** How vivid the camera's JPEGs are relative to its colour matrix; this leaves the smallest difference from them. */
    const val SATURATION = 1.45f

    // Used when the camera's own matrix cannot be read; measured from a ZV-E10 file.
    private val FALLBACK_COLOR_MATRIX = intArrayOf(1251, -211, 17, -100, 1315, -159, 62, -243, 1238)

    /** Samples are stored in 11 bits and expanded through [toneCurve] to the sensor's 14-bit scale. */
    private const val STORED_MAX = 0x7FF

    @Throws(IOException::class)
    fun read(file: ByteArray): ArwImage = try {
        parse(file)
    } catch (e: IndexOutOfBoundsException) {
        throw IOException("Damaged or unsupported ARW file", e)
    } catch (e: BufferUnderflowException) {
        throw IOException("Damaged or unsupported ARW file", e)
    }

    private fun parse(file: ByteArray): ArwImage {
        val (data, firstOffset) = TiffDirectory.open(file)
        val main = TiffDirectory.read(data, firstOffset)
        if (main.text(TAG_MAKE)?.startsWith("SONY") != true) throw IOException("Not a Sony raw file")
        val rawOffset = main.int(TAG_SUB_IFDS) ?: throw IOException("The file holds no raw image")
        val raw = TiffDirectory.read(data, rawOffset)

        val width = raw.int(TAG_WIDTH) ?: throw IOException("Missing raw image size")
        val height = raw.int(TAG_HEIGHT) ?: throw IOException("Missing raw image size")
        val stripOffset = raw.int(TAG_STRIP_OFFSETS) ?: throw IOException("Missing raw image data")
        val stripLength = raw.int(TAG_STRIP_BYTE_COUNTS) ?: throw IOException("Missing raw image data")
        // The compressed format spends exactly one byte per photosite.
        if (raw.int(TAG_COMPRESSION) != COMPRESSION_SONY || stripLength.toLong() != width.toLong() * height) {
            throw IOException("Only Sony's compressed raw format is supported")
        }
        if (raw.ints(TAG_CFA_PATTERN)?.contentEquals(RGGB) != true) {
            throw IOException("Unsupported sensor colour layout")
        }

        val cropOrigin = raw.ints(TAG_CROP_ORIGIN)?.takeIf { it.size == 2 } ?: intArrayOf(0, 0)
        val cropSize = raw.ints(TAG_CROP_SIZE)?.takeIf { it.size == 2 } ?: intArrayOf(width, height)
        val whiteBalance = raw.ints(TAG_WHITE_BALANCE)?.takeIf { it.size == 4 && it[1] > 0 }
            ?: throw IOException("Missing white balance")
        val colorMatrix = readPrivateDirectory(data, main)?.ints(TAG_COLOR_MATRIX)?.takeIf { it.size == 9 }
            ?: FALLBACK_COLOR_MATRIX

        return ArwImage(
            width = width,
            height = height,
            // The colour layout repeats every two photosites, so the crop has to start on an even one.
            cropLeft = cropOrigin[0] and 1.inv(),
            cropTop = cropOrigin[1] and 1.inv(),
            cropWidth = cropSize[0] and 1.inv(),
            cropHeight = cropSize[1] and 1.inv(),
            blackLevel = raw.int(TAG_BLACK_LEVEL) ?: 512,
            whiteLevel = raw.int(TAG_WHITE_LEVEL) ?: 16383,
            whiteBalance = floatArrayOf(
                whiteBalance[0].toFloat() / whiteBalance[1],
                1f,
                whiteBalance[3].toFloat() / whiteBalance[1],
            ),
            colorMatrix = FloatArray(9) { colorMatrix[it] / 1024f },
            saturation = SATURATION,
            lens = readLensProfile(raw),
            orientation = main.int(TAG_ORIENTATION) ?: 1,
            sensor = decode(file, stripOffset, width, height, toneCurve(raw.ints(TAG_TONE_CURVE))),
        )
    }

    private fun readLensProfile(raw: TiffDirectory): LensProfile? {
        // Each tag starts with the number of values that follow.
        val distortion = raw.ints(TAG_DISTORTION) ?: return null
        val steps = distortion[0]
        if (steps < 2 || distortion.size <= steps) return null
        val aberration = raw.ints(TAG_ABERRATION)?.takeIf { it[0] == 2 * steps && it.size > 2 * steps }
        return LensProfile(
            distortion = distortion.copyOfRange(1, steps + 1),
            redAberration = aberration?.copyOfRange(1, steps + 1) ?: IntArray(steps),
            blueAberration = aberration?.copyOfRange(steps + 1, 2 * steps + 1) ?: IntArray(steps),
        )
    }

    /** Sony keeps some settings, the colour matrix among them, in a scrambled directory of its own. */
    private fun readPrivateDirectory(data: ByteBuffer, main: TiffDirectory): TiffDirectory? {
        val pointer = main.ints(TAG_PRIVATE_DATA) ?: return null
        val locator = TiffDirectory.read(data, if (pointer.size == 4) littleEndian(pointer) else pointer[0])
        val offset = locator.int(TAG_PRIVATE_OFFSET) ?: return null
        val length = locator.int(TAG_PRIVATE_LENGTH) ?: return null
        val key = locator.ints(TAG_PRIVATE_KEY)?.takeIf { it.size == 4 } ?: return null
        if (offset < 0 || length <= 0 || offset.toLong() + length > data.capacity()) return null

        val words = IntArray(length / 4) { data.getInt(offset + 4 * it) }
        unscramble(words, littleEndian(key))
        val plain = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        words.forEach(plain::putInt)
        return TiffDirectory.read(plain, 0, base = offset)
    }

    private fun littleEndian(bytes: IntArray) = bytes[0] or (bytes[1] shl 8) or (bytes[2] shl 16) or (bytes[3] shl 24)

    /** Undoes Sony's scrambling: every word is XORed with a pseudo-random sequence grown from [key]. */
    internal fun unscramble(words: IntArray, key: Int) {
        val pad = IntArray(128)
        var seed = key
        for (p in 0 until 4) {
            seed = seed * 48828125 + 1
            pad[p] = seed
        }
        pad[3] = (pad[3] shl 1) or ((pad[0] xor pad[2]) ushr 31)
        for (p in 4 until 127) {
            pad[p] = ((pad[p - 4] xor pad[p - 2]) shl 1) or ((pad[p - 3] xor pad[p - 1]) ushr 31)
        }
        // The sequence is defined on big-endian words while the file is read little-endian.
        for (p in 0 until 127) pad[p] = Integer.reverseBytes(pad[p])
        var p = 127
        for (index in words.indices) {
            pad[p and 127] = pad[(p + 1) and 127] xor pad[(p + 65) and 127]
            words[index] = words[index] xor pad[p and 127]
            p++
        }
    }

    /**
     * The file stores 11-bit samples on a curve that keeps fine steps in the shadows and takes ever
     * larger ones towards the highlights. [points] are where the step size doubles.
     */
    internal fun toneCurve(points: IntArray?): IntArray {
        val bounds = IntArray(6)
        bounds[5] = 4095
        if (points != null && points.size == 4) {
            for (index in 0 until 4) bounds[index + 1] = (points[index] shr 2) and 0xFFF
        }
        val curve = IntArray(4096) { it }
        for (segment in 0 until 5) {
            for (value in bounds[segment] + 1..bounds[segment + 1]) {
                curve[value] = curve[value - 1] + (1 shl segment)
            }
        }
        return curve
    }

    /**
     * Unpacks the sensor data. Each row is a series of 16-byte blocks; a block holds 16 photosites of
     * one colour as their maximum, their minimum, where those two sit, and 7-bit offsets for the rest.
     */
    internal fun decode(file: ByteArray, stripOffset: Int, width: Int, height: Int, curve: IntArray): ShortArray {
        val sensor = ShortArray(width * height)
        val block = IntArray(16)
        for (row in 0 until height) {
            var position = stripOffset + row * width
            val rowStart = row * width
            var column = 0
            while (column < width - 30) {
                val header = int32(file, position)
                val max = header and STORED_MAX
                val min = (header ushr 11) and STORED_MAX
                val maxIndex = (header ushr 22) and 0x0F
                val minIndex = (header ushr 26) and 0x0F
                var shift = 0
                while (shift < 4 && (0x80 shl shift) <= max - min) shift++
                var bit = 30
                for (index in 0 until 16) {
                    block[index] = when (index) {
                        maxIndex -> max
                        minIndex -> min
                        else -> {
                            val offset = (int16(file, position + (bit shr 3)) ushr (bit and 7)) and 0x7F
                            bit += 7
                            minOf((offset shl shift) + min, STORED_MAX)
                        }
                    }
                }
                // A block covers every other column; two blocks interleave to fill 32 columns.
                for (index in 0 until 16) {
                    sensor[rowStart + column] = curve[block[index] shl 1].toShort()
                    column += 2
                }
                column -= if (column and 1 != 0) 1 else 31
                position += 16
            }
        }
        return sensor
    }

    // The last block of a file can reach a byte or two past its end; those bits count as zero.
    private fun int16(file: ByteArray, at: Int): Int {
        val low = if (at < file.size) file[at].toInt() and 0xFF else 0
        val high = if (at + 1 < file.size) file[at + 1].toInt() and 0xFF else 0
        return low or (high shl 8)
    }

    private fun int32(file: ByteArray, at: Int): Int =
        (file[at].toInt() and 0xFF) or
            ((file[at + 1].toInt() and 0xFF) shl 8) or
            ((file[at + 2].toInt() and 0xFF) shl 16) or
            ((file[at + 3].toInt() and 0xFF) shl 24)
}
