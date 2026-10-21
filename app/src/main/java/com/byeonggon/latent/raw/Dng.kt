package com.byeonggon.latent.raw

import com.byeonggon.latent.raw.api.DecodeFailure
import com.byeonggon.latent.raw.api.DecodeFailureReason
import com.byeonggon.latent.raw.api.DecodeRequest
import com.byeonggon.latent.raw.api.DecodedFrame
import com.byeonggon.latent.raw.api.RawCorrection
import com.byeonggon.latent.raw.api.RawFormat
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.byeonggon.latent.enhance.forRowBands
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Reads Adobe's Digital Negative (DNG), the raw format of phones such as a Galaxy's in Pro mode: the
 * sensor's mosaic of red, green and blue, uncompressed or in lossless JPEG, and what the DNG specification
 * says is needed to develop it (black and white levels, the colour matrices, white balance, the default crop
 * and the gain maps that undo the lens's shading). Also linear DNGs, whose colours the phone has already filled in,
 * as a Galaxy writes them in Pro mode (lossless JPEG) and in Expert RAW (JPEG XL).
 */
object Dng {
    private const val TAG_NEW_SUBFILE_TYPE = 0x00FE
    private const val TAG_WIDTH = 0x0100
    private const val TAG_HEIGHT = 0x0101
    private const val TAG_BITS_PER_SAMPLE = 0x0102
    private const val TAG_COMPRESSION = 0x0103
    private const val TAG_PHOTOMETRIC = 0x0106
    private const val TAG_STRIP_OFFSETS = 0x0111
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_SAMPLES_PER_PIXEL = 0x0115
    private const val TAG_PLANAR_CONFIGURATION = 0x011C
    private const val TAG_ROWS_PER_STRIP = 0x0116
    private const val TAG_STRIP_BYTE_COUNTS = 0x0117
    private const val TAG_TILE_WIDTH = 0x0142
    private const val TAG_TILE_LENGTH = 0x0143
    private const val TAG_TILE_OFFSETS = 0x0144
    private const val TAG_TILE_BYTE_COUNTS = 0x0145
    private const val TAG_SUB_IFDS = 0x014A
    private const val TAG_CFA_REPEAT = 0x828D
    private const val TAG_CFA_PATTERN = 0x828E
    private const val TAG_DNG_VERSION = 0xC612
    private const val TAG_ICC_PROFILE = 0x8773
    private const val TAG_CFA_PLANE_COLOR = 0xC616
    private const val TAG_LINEARIZATION = 0xC618
    private const val TAG_BLACK_REPEAT = 0xC619
    private const val TAG_BLACK_LEVEL = 0xC61A
    private const val TAG_BLACK_DELTA_H = 0xC61B
    private const val TAG_BLACK_DELTA_V = 0xC61C
    private const val TAG_WHITE_LEVEL = 0xC61D
    private const val TAG_CROP_ORIGIN = 0xC61F
    private const val TAG_CROP_SIZE = 0xC620
    private const val TAG_COLOR_MATRIX_1 = 0xC621
    private const val TAG_COLOR_MATRIX_2 = 0xC622
    private const val TAG_CALIBRATION_1 = 0xC623
    private const val TAG_CALIBRATION_2 = 0xC624
    private const val TAG_ANALOG_BALANCE = 0xC627
    private const val TAG_AS_SHOT_NEUTRAL = 0xC628
    private const val TAG_AS_SHOT_WHITE_XY = 0xC629
    private const val TAG_ILLUMINANT_1 = 0xC65A
    private const val TAG_ILLUMINANT_2 = 0xC65B
    private const val TAG_BASELINE_EXPOSURE = 0xC62A
    private const val TAG_ACTIVE_AREA = 0xC68D
    private const val TAG_OPCODES_2 = 0xC741

    private const val PHOTOMETRIC_CFA = 32803
    private const val PHOTOMETRIC_LINEAR_RAW = 34892
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_LOSSLESS_JPEG = 7
    private const val COMPRESSION_JPEG_XL = 52546
    private const val OPCODE_GAIN_MAP = 9

    /** Sensor values are brought to this white level, as in a Sony file, whatever the bit depth of the file. */
    private const val WHITE = 16383

    /** Where a gain map brightens a corner beyond the white level, it still has to fit a sensor value. */
    private const val MAX_SENSOR = 32767

    /** Phones' sensors stop at about 200 megapixels; more is a damaged file, and more than the memory there is. */
    private const val MAX_PHOTOSITES = 210_000_000L

    /**
     * How much more vivid than the colour matrix the picture is made. The matrices of a DNG are measured ones, so
     * none: colours as they were. Unlike Sony's own matrix, which is milder than the camera's JPEGs.
     */
    const val SATURATION = 1f

    /** Linear sRGB to CIE XYZ, for the D65 white point. */
    private val XYZ_OF_SRGB = floatArrayOf(
        0.4124564f, 0.3575761f, 0.1804375f,
        0.2126729f, 0.7151522f, 0.0721750f,
        0.0193339f, 0.1191920f, 0.9503041f,
    )

    /** EXIF light sources, from the one nearest to daylight on: the colour matrix measured under it is the one used. */
    private val DAYLIGHT_FIRST = intArrayOf(21, 20, 23, 22, 1, 9, 10, 11, 4, 19, 18, 17)

    /** Whether [file] is a DNG; it says so in its first directory. */
    fun isDng(file: ByteArray): Boolean = try {
        val (data, offset) = TiffDirectory.open(file)
        TAG_DNG_VERSION in TiffDirectory.read(data, offset)
    } catch (e: IOException) {
        false
    } catch (e: IndexOutOfBoundsException) {
        false
    }

    @Throws(IOException::class)
    fun read(file: ByteArray): ArwImage = decode(file).legacyImage

    internal fun decode(file: ByteArray, request: DecodeRequest? = null): DecodedFrame = try {
        parse(file, request)
    } catch (e: IndexOutOfBoundsException) {
        throw IOException("Damaged or unsupported DNG file", e)
    } catch (e: BufferUnderflowException) {
        throw IOException("Damaged or unsupported DNG file", e)
    } catch (e: NegativeArraySizeException) {
        throw IOException("Damaged or unsupported DNG file", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("Damaged or unsupported DNG file", e)
    }

    private fun parse(file: ByteArray, request: DecodeRequest?): DecodedFrame {
        request?.checkCancelled?.invoke()
        val (data, firstOffset) = TiffDirectory.open(file)
        val main = TiffDirectory.read(data, firstOffset)
        if (TAG_DNG_VERSION !in main) throw IOException("Not a DNG file")
        if (main.ints(TAG_DNG_VERSION)?.firstOrNull() != 1) throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Unsupported DNG version")
        // The full-size raw image is the first directory itself (as Android writes it) or one under it (as Adobe does).
        val directories = listOf(main) + (main.ints(TAG_SUB_IFDS)?.map { TiffDirectory.read(data, it) } ?: emptyList())
        val fullSize = directories.filter { (it.int(TAG_NEW_SUBFILE_TYPE) ?: 0) == 0 }
        val raw = fullSize.firstOrNull { it.int(TAG_PHOTOMETRIC) == PHOTOMETRIC_CFA }
            ?: fullSize.firstOrNull { it.int(TAG_PHOTOMETRIC) == PHOTOMETRIC_LINEAR_RAW }
            ?: throw IOException("The file holds no raw image")
        val linear = raw.int(TAG_PHOTOMETRIC) == PHOTOMETRIC_LINEAR_RAW
        // Colour settings belong in the first directory, but some writers put them with the raw image.
        fun floatsOf(tag: Int) = main.floats(tag) ?: raw.floats(tag)
        fun intOf(tag: Int) = main.int(tag) ?: raw.int(tag)

        val width = raw.int(TAG_WIDTH) ?: throw IOException("Missing raw image size")
        val height = raw.int(TAG_HEIGHT) ?: throw IOException("Missing raw image size")
        if (width < 8 || height < 8) throw IOException("Invalid raw image size")
        if (width.toLong() * height > MAX_PHOTOSITES) throw DecodeFailure(DecodeFailureReason.RESOURCE_LIMIT, "Unsupported raw image size")
        val bits = raw.int(TAG_BITS_PER_SAMPLE) ?: 16
        if (bits !in 8..16) throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Unsupported raw sample size: $bits bits")
        val channels = raw.int(TAG_SAMPLES_PER_PIXEL) ?: 1
        if (channels != (if (linear) 3 else 1)) throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Unsupported raw image layout")
        if (linear && (raw.int(TAG_PLANAR_CONFIGURATION) ?: 1) != 1) throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Unsupported raw image layout")

        request?.checkSamples(width.toLong() * height * channels)
        val checkCancelled = request?.checkCancelled ?: {}
        val active = raw.ints(TAG_ACTIVE_AREA)?.takeIf { it.size == 4 } ?: intArrayOf(0, 0, height, width)
        val (activeTop, activeLeft, activeBottom, activeRight) = active
        if (activeTop < 0 || activeLeft < 0 || activeBottom > height || activeRight > width ||
            activeBottom - activeTop < 8 || activeRight - activeLeft < 8
        ) {
            throw IOException("Damaged active area")
        }
        val activeWidth = activeRight - activeLeft
        val activeHeight = activeBottom - activeTop
        // A linear picture has no mosaic; its crop may start anywhere.
        val redAt = if (linear) 0 to 0 else redSite(raw)

        val sensor = readSamples(file, data.order(), raw, width, height, bits, channels, checkCancelled)
        val levels = Levels(raw, bits, activeWidth, activeHeight, channels)
        levels.apply(sensor, width, activeLeft, activeTop, checkCancelled)
        val brightened = floatsOf(TAG_BASELINE_EXPOSURE)?.firstOrNull()?.let { brighten(sensor, it, checkCancelled) } ?: false
        val maps = gainMaps(raw.bytes(TAG_OPCODES_2), channels)
        maps.forEach { it.apply(sensor, width, activeLeft, activeTop, activeWidth, activeHeight, channels, checkCancelled) }

        // The default crop, within the active area, moved by a photosite where needed so that it starts on a red one.
        val origin = raw.floats(TAG_CROP_ORIGIN)?.takeIf { it.size == 2 && it.all(Float::isFinite) } ?: floatArrayOf(0f, 0f)
        val size = raw.floats(TAG_CROP_SIZE)?.takeIf { it.size == 2 && it.all(Float::isFinite) }
            ?: floatArrayOf(activeWidth.toFloat(), activeHeight.toFloat())
        var cropLeft = origin[0].toInt().coerceIn(0, activeWidth - 2)
        var cropTop = origin[1].toInt().coerceIn(0, activeHeight - 2)
        cropLeft += (redAt.first - cropLeft) and 1
        cropTop += (redAt.second - cropTop) and 1
        val cropWidth = minOf(size[0].toInt(), activeWidth - cropLeft) and 1.inv()
        val cropHeight = minOf(size[1].toInt(), activeHeight - cropTop) and 1.inv()
        if (cropWidth < 4 || cropHeight < 4) throw IOException("Damaged default crop")

        val color = Colors(::floatsOf, ::intOf)
        val image = ArwImage(
            width = width,
            height = height,
            cropLeft = activeLeft + cropLeft,
            cropTop = activeTop + cropTop,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            blackLevel = 0,
            whiteLevel = WHITE,
            whiteBalance = color.whiteBalance,
            colorMatrix = color.matrix,
            saturation = SATURATION,
            lens = null,
            orientation = main.int(TAG_ORIENTATION)?.takeIf { it in 1..8 } ?: 1,
            sensor = sensor,
            channels = channels,
        )
        val corrections = buildSet {
            add(RawCorrection.DNG_LEVELS)
            if (levels.linearized) add(RawCorrection.LINEARIZATION)
            if (brightened) add(RawCorrection.POSITIVE_BASELINE_EXPOSURE)
            if (maps.isNotEmpty()) add(RawCorrection.GAIN_MAP)
        }
        // An unreadable preservation-only profile must not hide otherwise decodable pixels.
        fun profileOf(directory: TiffDirectory): ByteArray? = try {
            directory.bytes(TAG_ICC_PROFILE)
        } catch (_: IOException) {
            null
        }
        return DecodedFrame(image, RawFormat.DNG, bits, corrections, profileOf(main) ?: profileOf(raw))
    }

    /**
     * Where a red photosite sits in the repeating two-by-two pattern, counted from the active area's corner; only
     * the usual layouts with two greens on one diagonal are read.
     */
    private fun redSite(raw: TiffDirectory): Pair<Int, Int> {
        val repeat = raw.ints(TAG_CFA_REPEAT) ?: intArrayOf(2, 2)
        val planes = raw.ints(TAG_CFA_PLANE_COLOR) ?: intArrayOf(0, 1, 2)
        val pattern = raw.ints(TAG_CFA_PATTERN)?.map { planes.getOrNull(it) ?: -1 }
        if (!repeat.contentEquals(intArrayOf(2, 2)) || pattern == null || pattern.sorted() != listOf(0, 1, 1, 2)) {
            throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Unsupported sensor colour layout")
        }
        val red = pattern.indexOf(0)
        if (pattern[3 - red] != 2) throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Unsupported sensor colour layout")
        return (red % 2) to (red / 2)
    }

    /** Reads the strips or tiles of samples into [into], as they are stored. */
    private fun readSamples(file: ByteArray, order: ByteOrder, raw: TiffDirectory, width: Int, height: Int, bits: Int, channels: Int, checkCancelled: () -> Unit): ShortArray {
        val tiled = TAG_TILE_OFFSETS in raw
        val offsets = raw.ints(if (tiled) TAG_TILE_OFFSETS else TAG_STRIP_OFFSETS) ?: throw IOException("Missing raw image data")
        val lengths = raw.ints(if (tiled) TAG_TILE_BYTE_COUNTS else TAG_STRIP_BYTE_COUNTS)?.takeIf { it.size == offsets.size }
            ?: throw IOException("Missing raw image data")
        val tileWidth = if (tiled) raw.int(TAG_TILE_WIDTH) ?: 0 else width
        // Rows per strip is unsigned, and its largest value, the default, means one strip for the whole image.
        val rowsPerStrip = raw.int(TAG_ROWS_PER_STRIP)?.let { it.toLong() and 0xFFFFFFFFL } ?: height.toLong()
        val tileLength = if (tiled) raw.int(TAG_TILE_LENGTH) ?: 0 else rowsPerStrip.coerceAtMost(height.toLong()).toInt()
        if (tileWidth <= 0 || tileLength <= 0) throw IOException("Damaged raw image layout")
        val across = ((width.toLong() + tileWidth - 1) / tileWidth).toInt()
        val down = ((height.toLong() + tileLength - 1) / tileLength).toInt()
        if (across.toLong() * tileWidth * down * tileLength > 2 * MAX_PHOTOSITES) throw IOException("Damaged raw image layout")
        if (offsets.size < across * down) throw IOException("Missing raw image data")
        val compression = raw.int(TAG_COMPRESSION) ?: COMPRESSION_NONE
        if (compression !in intArrayOf(COMPRESSION_NONE, COMPRESSION_LOSSLESS_JPEG, COMPRESSION_JPEG_XL)) {
            throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Unsupported DNG compression: $compression")
        }
        // Uncompressed rows use their actual byte size; only JPEG XL requires the whole declared range.
        for (index in 0 until across * down) {
            checkCancelled()
            val offset = offsets[index]
            val length = lengths[index]
            if (offset < 0 || offset >= file.size ||
                (compression != COMPRESSION_NONE && length <= 0) ||
                (compression == COMPRESSION_JPEG_XL && offset.toLong() + length > file.size)
            ) throw IOException("Raw image data is cut short")
            val left = index % across * tileWidth
            val top = index / across * tileLength
            if (compression == COMPRESSION_JPEG_XL) {
                JpegXl.checkSize(file, offset, length, minOf(tileWidth, width - left)..tileWidth, minOf(tileLength, height - top)..tileLength, channels)
            }
            if (compression == COMPRESSION_NONE) {
                val needed = (tileWidth.toLong() * channels * bits + 7) / 8 * minOf(tileLength, height - top)
                if (offset.toLong() + needed > file.size) throw IOException("Raw image data is cut short")
            }
        }
        val into = ShortArray((width.toLong() * height * channels).toInt())
        for (index in 0 until across * down) {
            checkCancelled()
            val left = index % across * tileWidth
            val top = index / across * tileLength
            // The samples of a pixel lie side by side, so a row holds [channels] times as many as it has pixels.
            val rowSamples = width * channels
            when (compression) {
                COMPRESSION_NONE -> unpack(file, order, offsets[index], lengths[index], into, rowSamples, height, left * channels, top, tileWidth * channels, tileLength, bits, checkCancelled)
                COMPRESSION_LOSSLESS_JPEG -> LosslessJpeg.decode(file, offsets[index], lengths[index], into, rowSamples, height, left * channels, top, tileWidth * channels, tileLength, checkCancelled)
                COMPRESSION_JPEG_XL -> JpegXl.decode(file, offsets[index], lengths[index], into, width, height, left, top, tileWidth, tileLength, channels, bits)
                else -> throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Unsupported DNG compression: $compression")
            }
            checkCancelled()
        }
        return into
    }

    /** Uncompressed samples: 8 or 16 bits as bytes or words in the file's byte order, other sizes packed most significant bit first. */
    private fun unpack(
        file: ByteArray, order: ByteOrder, offset: Int, length: Int, into: ShortArray,
        width: Int, height: Int, left: Int, top: Int, tileWidth: Int, tileLength: Int, bits: Int, checkCancelled: () -> Unit,
    ) {
        val rowBytes = (tileWidth.toLong() * bits + 7) / 8
        if (offset < 0 || offset + rowBytes * tileLength.coerceAtMost(height - top) > file.size) throw IOException("Raw image data is cut short")
        val littleEndian = order == ByteOrder.LITTLE_ENDIAN
        for (row in 0 until minOf(tileLength, height - top)) {
            checkCancelled()
            val rowStart = offset + row * rowBytes
            val target = (top + row) * width + left
            val columns = minOf(tileWidth, width - left)
            when (bits) {
                8 -> for (x in 0 until columns) into[target + x] = (file[(rowStart + x).toInt()].toInt() and 0xFF).toShort()
                16 -> for (x in 0 until columns) {
                    val at = (rowStart + 2 * x).toInt()
                    val first = file[at].toInt() and 0xFF
                    val second = file[at + 1].toInt() and 0xFF
                    into[target + x] = (if (littleEndian) first or (second shl 8) else (first shl 8) or second).toShort()
                }
                else -> {
                    var bitAt = rowStart * 8
                    for (x in 0 until columns) {
                        var value = 0
                        repeat(bits) {
                            val byte = file[(bitAt ushr 3).toInt()].toInt()
                            value = (value shl 1) or ((byte ushr (7 - (bitAt and 7).toInt())) and 1)
                            bitAt++
                        }
                        into[target + x] = value.toShort()
                    }
                }
            }
        }
    }

    /**
     * Brings the picture up by a positive baseline exposure of [stops], as far as its brightest sample stays within white.
     * A Galaxy's Expert RAW keeps its merged exposures three stops under white (BaselineExposure +3), which would
     * otherwise come out that much darker and on an eighth of the levels. A negative one is left to the tone stage
     * and the person, as darkening here would only lose levels.
     */
    private fun brighten(sensor: ShortArray, stops: Float, checkCancelled: () -> Unit): Boolean {
        if (!(stops > 0f) || !stops.isFinite()) return false
        var brightest = 0
        for (at in sensor.indices) {
            if (at % 65536 == 0) checkCancelled()
            brightest = max(brightest, sensor[at].toInt())
        }
        if (brightest == 0) return false
        val gain = min(2f.pow(stops), WHITE.toFloat() / brightest)
        if (gain <= 1.001f) return false
        forRowBands(sensor.size) { from, until ->
            checkCancelled()
            for (at in from until until) sensor[at] = (sensor[at] * gain).roundToInt().coerceAtMost(WHITE).toShort()
        }
        return true
    }

    /**
     * Linearization, black level and white level: brings each photosite of the active area from what the file
     * stores to light above black, scaled so that the sensor's white is [WHITE]. Photosites outside the active
     * area are set to black.
     */
    private class Levels(
        raw: TiffDirectory, bits: Int, private val activeWidth: Int, private val activeHeight: Int, private val channels: Int,
    ) {
        private val table = raw.ints(TAG_LINEARIZATION)?.takeIf { it.isNotEmpty() }
        val linearized: Boolean get() = table != null
        private val repeat = raw.ints(TAG_BLACK_REPEAT)?.takeIf { it.size == 2 && it[0] in 1..16 && it[1] in 1..16 } ?: intArrayOf(1, 1)

        // One black level for each place in the repeat and each sample of a pixel; a file may give one for all samples.
        private val black = raw.floats(TAG_BLACK_LEVEL)?.takeIf { it.all(Float::isFinite) }?.let { given ->
            val wanted = repeat[0] * repeat[1] * channels
            when (given.size) {
                wanted -> given
                repeat[0] * repeat[1] -> FloatArray(wanted) { given[it / channels] }
                1 -> FloatArray(wanted) { given[0] }
                else -> null
            }
        } ?: FloatArray(repeat[0] * repeat[1] * channels)
        private val deltaH = raw.floats(TAG_BLACK_DELTA_H)?.takeIf { it.size == activeWidth && it.all(Float::isFinite) }
        private val deltaV = raw.floats(TAG_BLACK_DELTA_V)?.takeIf { it.size == activeHeight && it.all(Float::isFinite) }

        // A white level for each sample of a pixel, the first standing for all if only one is given.
        private val white: FloatArray = run {
            val fallback = (table?.maxOrNull() ?: ((1 shl bits) - 1)).toFloat()
            val given = raw.floats(TAG_WHITE_LEVEL)?.filter { it.isFinite() && it > 0 }.orEmpty()
            FloatArray(channels) { given.getOrNull(it) ?: given.firstOrNull() ?: fallback }
        }

        fun apply(sensor: ShortArray, width: Int, activeLeft: Int, activeTop: Int, checkCancelled: () -> Unit) {
            val rowDeltaMost = (deltaH?.max() ?: 0f) + (deltaV?.max() ?: 0f)
            val scale = FloatArray(channels) { channel ->
                val highestBlack = (0 until repeat[0] * repeat[1]).maxOf { black[it * channels + channel] } + rowDeltaMost
                val range = white[channel] - highestBlack
                if (!highestBlack.isFinite() || !(range >= 16f) || !range.isFinite()) throw IOException("Damaged black or white level")
                WHITE / range
            }
            val rowSamples = width * channels
            sensor.fill(0, 0, activeTop * rowSamples)
            sensor.fill(0, (activeTop + activeHeight) * rowSamples, sensor.size)
            for (y in 0 until activeHeight) {
                checkCancelled()
                val rowStart = (activeTop + y) * rowSamples
                val row = rowStart + activeLeft * channels
                val blackRow = y % repeat[0] * repeat[1]
                val rowDelta = deltaV?.get(y) ?: 0f
                sensor.fill(0, rowStart, row)
                sensor.fill(0, row + activeWidth * channels, rowStart + rowSamples)
                for (x in 0 until activeWidth) {
                    val blackAt = (blackRow + x % repeat[1]) * channels
                    val delta = rowDelta + (deltaH?.get(x) ?: 0f)
                    for (channel in 0 until channels) {
                        val at = row + x * channels + channel
                        val stored = sensor[at].toInt() and 0xFFFF
                        val value = table?.let { it[stored.coerceAtMost(it.size - 1)] } ?: stored
                        val level = black[blackAt + channel] + delta
                        sensor[at] = ((value - level) * scale[channel]).roundToInt().coerceIn(0, MAX_SENSOR).toShort()
                    }
                }
            }
        }
    }

    /**
     * A gain map (DNG opcode 9): factors on a coarse grid over the picture, for the photosites of one rectangle
     * taken every so many rows and columns, that undo how the lens darkens and tints towards the corners.
     */
    private class GainMap(
        val top: Int, val left: Int, val bottom: Int, val right: Int,
        val rowPitch: Int, val columnPitch: Int,
        val pointsDown: Int, val pointsAcross: Int,
        val spacingDown: Double, val spacingAcross: Double,
        val originDown: Double, val originAcross: Double,
        val mapPlanes: Int, val gains: FloatArray,
        /** The samples of a pixel the map is for: from [plane] on, [planes] of them. A mosaic has one. */
        val plane: Int = 0, val planes: Int = 1,
    ) {
        /** Multiplies the photosites of the active area, placed at [activeLeft], [activeTop] in [sensor], by the map. */
        fun apply(sensor: ShortArray, width: Int, activeLeft: Int, activeTop: Int, activeWidth: Int, activeHeight: Int, channels: Int, checkCancelled: () -> Unit) {
            val columns = (left.coerceAtLeast(0) until right.coerceAtMost(activeWidth) step columnPitch).toList()
            // Where each column falls between the map's points, worked out once: the index of the point before and how far past it.
            val columnPoint = IntArray(columns.size)
            val columnPast = FloatArray(columns.size)
            columns.forEachIndexed { index, x ->
                val along = (((x + 0.5) / activeWidth - originAcross) / spacingAcross).coerceIn(0.0, pointsAcross - 1.0)
                columnPoint[index] = along.toInt().coerceAtMost(pointsAcross - 2).coerceAtLeast(0)
                columnPast[index] = (along - columnPoint[index]).toFloat()
            }
            for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(activeHeight) step rowPitch) {
                checkCancelled()
                val along = (((y + 0.5) / activeHeight - originDown) / spacingDown).coerceIn(0.0, pointsDown - 1.0)
                val row = along.toInt().coerceAtMost(pointsDown - 2).coerceAtLeast(0)
                val rowPast = (along - row).toFloat()
                val nextRow = if (pointsDown > 1) row + 1 else row
                val rowStart = ((activeTop + y) * width + activeLeft) * channels
                columns.forEachIndexed { index, x ->
                    val column = columnPoint[index]
                    val nextColumn = if (pointsAcross > 1) column + 1 else column
                    val past = columnPast[index]
                    for (sample in plane until minOf(plane + planes, channels)) {
                        // A map with fewer planes than it covers uses its last one for the rest.
                        val mapPlane = (sample - plane).coerceAtMost(mapPlanes - 1)
                        val upper = gain(row, column, mapPlane) + (gain(row, nextColumn, mapPlane) - gain(row, column, mapPlane)) * past
                        val lower = gain(nextRow, column, mapPlane) + (gain(nextRow, nextColumn, mapPlane) - gain(nextRow, column, mapPlane)) * past
                        val factor = upper + (lower - upper) * rowPast
                        val at = rowStart + x * channels + sample
                        sensor[at] = (sensor[at] * factor).roundToInt().coerceIn(0, MAX_SENSOR).toShort()
                    }
                }
            }
        }

        private fun gain(row: Int, column: Int, mapPlane: Int) = gains[(row * pointsAcross + column) * mapPlanes + mapPlane]
    }

    /**
     * The gain maps among the opcodes that apply to the raw image (OpcodeList2), which phones use for the lens's
     * shading; other opcodes are left out. Opcode lists are big-endian whatever the file's byte order.
     */
    private fun gainMaps(list: ByteArray?, channels: Int): List<GainMap> {
        if (list == null || list.size < 4) return emptyList()
        val data = ByteBuffer.wrap(list).order(ByteOrder.BIG_ENDIAN)
        val result = ArrayList<GainMap>()
        try {
            var at = 4
            repeat(data.getInt(0).coerceIn(0, 1024)) {
                val id = data.getInt(at)
                val size = data.getInt(at + 12)
                val body = at + 16
                if (size < 0 || body + size > list.size) return result
                if (id == OPCODE_GAIN_MAP) gainMap(data, body, size, channels)?.let(result::add)
                at = body + size
            }
        } catch (e: IndexOutOfBoundsException) {
            // A damaged list: the maps read so far are kept.
        }
        return result
    }

    private fun gainMap(data: ByteBuffer, at: Int, size: Int, channels: Int): GainMap? {
        if (size < 76) return null
        val plane = data.getInt(at + 16)
        val planes = data.getInt(at + 20)
        val pointsDown = data.getInt(at + 32)
        val pointsAcross = data.getInt(at + 36)
        val mapPlanes = data.getInt(at + 72)
        // A raw mosaic has one plane, a linear picture three; a map for a plane that is not there is of no use.
        if (plane !in 0 until channels || planes < 1 || pointsDown < 1 || pointsAcross < 1 || mapPlanes < 1) return null
        val count = pointsDown.toLong() * pointsAcross * mapPlanes
        if (count > 1 shl 20 || 76 + 4 * count > size) return null
        val map = GainMap(
            top = data.getInt(at), left = data.getInt(at + 4), bottom = data.getInt(at + 8), right = data.getInt(at + 12),
            rowPitch = data.getInt(at + 24).coerceAtLeast(1), columnPitch = data.getInt(at + 28).coerceAtLeast(1),
            pointsDown = pointsDown, pointsAcross = pointsAcross,
            spacingDown = data.getDouble(at + 40), spacingAcross = data.getDouble(at + 48),
            originDown = data.getDouble(at + 56), originAcross = data.getDouble(at + 64),
            mapPlanes = mapPlanes,
            gains = FloatArray(count.toInt()) { data.getFloat(at + 76 + 4 * it) },
            plane = plane,
            planes = planes,
        )
        val usable = map.spacingDown > 0 && map.spacingAcross > 0 && map.spacingDown.isFinite() && map.spacingAcross.isFinite() &&
            map.originDown.isFinite() && map.originAcross.isFinite() && map.gains.all { it.isFinite() && it >= 0f }
        return map.takeIf { usable }
    }

    /**
     * The colour matrix from the camera's colours to linear sRGB, and the white balance the photo was taken with,
     * worked out as dcraw does: the camera's response to sRGB's primaries, each row scaled so that white gives one,
     * and inverted. Two known calibration illuminants are blended for the measured scene white;
     * otherwise the legacy matrix nearest daylight is used.
     */
    private class Colors(floats: (Int) -> FloatArray?, ints: (Int) -> Int?) {
        val matrix: FloatArray
        val whiteBalance: FloatArray

        init {
            val second = floats(TAG_COLOR_MATRIX_2)?.takeIf(::usable)
            val first = floats(TAG_COLOR_MATRIX_1)?.takeIf(::usable)
            val useSecond = second != null && (first == null || rank(ints(TAG_ILLUMINANT_2)) < rank(ints(TAG_ILLUMINANT_1)))
            val colorMatrix = (if (useSecond) second else first) ?: throw IOException("Missing colour matrix")
            val analog = floats(TAG_ANALOG_BALANCE)?.takeIf { it.size == 3 && it.all { value -> value.isFinite() && value > 0f } }
            fun camera(matrix: FloatArray, calibrationTag: Int): FloatArray {
                val calibration = floats(calibrationTag)?.takeIf(::usable)
                val calibrated = calibration?.let { times(it, matrix) } ?: matrix
                return analog?.let { gains -> FloatArray(9) { gains[it / 3] * calibrated[it] } } ?: calibrated
            }
            val fallback = camera(colorMatrix, if (useSecond) TAG_CALIBRATION_2 else TAG_CALIBRATION_1)
            val shotNeutral = floats(TAG_AS_SHOT_NEUTRAL)?.takeIf { it.size == 3 && it.all { value -> value.isFinite() && value > 0f } }
            val temperature1 = if (first != null && second != null) DngIlluminants.kelvin(ints(TAG_ILLUMINANT_1)) else null
            val temperature2 = if (first != null && second != null) DngIlluminants.kelvin(ints(TAG_ILLUMINANT_2)) else null
            var shotWhite: DoubleArray? = null
            val blended = try {
                if (first != null && second != null && temperature1 != null && temperature2 != null && temperature1 != temperature2) {
                    val camera1 = normalized(first)?.let { camera(it, TAG_CALIBRATION_1) }
                    val camera2 = normalized(second)?.let { camera(it, TAG_CALIBRATION_2) }
                    if (camera1 != null && camera2 != null && usableCamera(camera1) && usableCamera(camera2)) {
                        shotWhite = if (shotNeutral != null) {
                            whiteOfNeutral(shotNeutral, camera1, camera2, temperature1, temperature2)
                        } else {
                            floats(TAG_AS_SHOT_WHITE_XY)?.takeIf { it.size == 2 }?.let { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }
                        }
                        shotWhite?.let { xy ->
                            weight(xy, temperature1, temperature2)?.let { blend(camera1, camera2, it) }
                                ?.takeIf(::usableCamera)
                                // Keep the transform and WB together when this white is outside the camera model.
                                ?.takeIf { candidate -> shotNeutral != null || vector(candidate, xyz(xy)).all { it.isFinite() && it > 0f } }
                        }
                    } else null
                } else null
            } catch (_: IOException) {
                // Optional metadata for the other illuminant must not break the working legacy selection.
                null
            }
            val cameraOfXyz = blended ?: fallback

            val cameraOfRgb = times(cameraOfXyz, XYZ_OF_SRGB)
            // How the camera sees sRGB's white, which is also the white balance for daylight.
            val white = FloatArray(3) { row -> cameraOfRgb[3 * row] + cameraOfRgb[3 * row + 1] + cameraOfRgb[3 * row + 2] }
            if (white.any { !(it > 0f) }) throw IOException("Unusable colour matrix")
            matrix = inverse(FloatArray(9) { cameraOfRgb[it] / white[it / 3] }) ?: throw IOException("Unusable colour matrix")

            val neutralFromWhite = if (blended != null && shotWhite != null) {
                vector(cameraOfXyz, xyz(shotWhite)).takeIf { it.all { value -> value.isFinite() && value > 0f } }
            } else null
            val neutral = shotNeutral ?: neutralFromWhite ?: white
            whiteBalance = floatArrayOf(neutral[1] / neutral[0], 1f, neutral[1] / neutral[2])
        }

        private fun normalized(matrix: FloatArray): FloatArray? {
            val maximum = vector(matrix, xyz(doubleArrayOf(0.3457, 0.3585))).max()
            if (!maximum.isFinite() || maximum <= 0f) return null
            val scale = if (maximum in 0.99f..1.01f) 1.0 else 1.0 / maximum
            // DNG SDK profile normalization removes arbitrary exposure scale before mixing calibrations.
            return FloatArray(9) { (kotlin.math.floor(matrix[it] * scale * 10000 + 0.5) / 10000).toFloat() }.takeIf(::usable)
        }

        private fun weight(xy: DoubleArray, temperature1: Double, temperature2: Double): Float? =
            DngIlluminants.mired(xy[0], xy[1])?.let {
                ((it - 1e6 / temperature2) / (1e6 / temperature1 - 1e6 / temperature2)).coerceIn(0.0, 1.0).toFloat()
            }

        private fun blend(first: FloatArray, second: FloatArray, weight: Float): FloatArray = when {
            weight >= 1f -> first
            weight <= 0f -> second
            else -> FloatArray(9) { weight * first[it] + (1f - weight) * second[it] }
        }

        private fun usableCamera(matrix: FloatArray): Boolean {
            if (!usable(matrix) || inverse(matrix)?.takeIf(::usable) == null) return false
            val rgb = times(matrix, XYZ_OF_SRGB)
            return (0..2).all { row -> (rgb[3 * row] + rgb[3 * row + 1] + rgb[3 * row + 2]).let { it.isFinite() && it > 0f } }
        }

        private fun vector(matrix: FloatArray, value: FloatArray) = FloatArray(3) { row ->
            matrix[3 * row] * value[0] + matrix[3 * row + 1] * value[1] + matrix[3 * row + 2] * value[2]
        }

        private fun xyz(xy: DoubleArray) = floatArrayOf((xy[0] / xy[1]).toFloat(), 1f, ((1 - xy[0] - xy[1]) / xy[1]).toFloat())

        private fun whiteOfNeutral(
            neutral: FloatArray, first: FloatArray, second: FloatArray, temperature1: Double, temperature2: Double,
        ): DoubleArray? {
            var last = doubleArrayOf(0.3457, 0.3585) // D50, as in DNG SDK NeutralToXY.
            repeat(30) { pass ->
                val weight = weight(last, temperature1, temperature2) ?: return null
                val cameraToXyz = inverse(blend(first, second, weight))?.takeIf(::usable) ?: return null
                val value = vector(cameraToXyz, neutral)
                val total = value.sum().toDouble()
                if (!total.isFinite() || total <= 0) return null
                val next = doubleArrayOf(value[0] / total, value[1] / total)
                if (DngIlluminants.mired(next[0], next[1]) == null) return null
                if (kotlin.math.abs(next[0] - last[0]) + kotlin.math.abs(next[1] - last[1]) < 1e-7) return next
                if (pass == 29) return doubleArrayOf((last[0] + next[0]) / 2, (last[1] + next[1]) / 2)
                last = next
            }
            return last
        }

        private fun usable(matrix: FloatArray) = matrix.size == 9 && matrix.all(Float::isFinite)

        private fun rank(illuminant: Int?) = DAYLIGHT_FIRST.indexOf(illuminant ?: -1).let { if (it < 0) DAYLIGHT_FIRST.size else it }

        private fun times(a: FloatArray, b: FloatArray) = FloatArray(9) { index ->
            val row = index / 3
            val column = index % 3
            a[3 * row] * b[column] + a[3 * row + 1] * b[3 + column] + a[3 * row + 2] * b[6 + column]
        }

        private fun inverse(m: FloatArray): FloatArray? {
            val cofactors = floatArrayOf(
                m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
                m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
                m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
            )
            val determinant = m[0] * cofactors[0] + m[1] * cofactors[3] + m[2] * cofactors[6]
            if (!(kotlin.math.abs(determinant) > 1e-6f)) return null
            return FloatArray(9) { cofactors[it] / determinant }
        }
    }
}
