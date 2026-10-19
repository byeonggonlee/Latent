package com.byeonggon.latent.raw

import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Reads Adobe's Digital Negative (DNG), the raw format of phones such as a Galaxy's in Pro mode: the
 * sensor's mosaic of red, green and blue, uncompressed or in lossless JPEG, and what the DNG specification
 * says is needed to develop it (black and white levels, the colour matrices, white balance, the default crop
 * and the gain maps that undo the lens's shading). Linear DNGs, whose colours a phone has already filled in
 * (a Galaxy's Expert RAW), and JPEG XL compression are refused with a reason.
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
    private const val TAG_ILLUMINANT_1 = 0xC65A
    private const val TAG_ILLUMINANT_2 = 0xC65B
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
    fun read(file: ByteArray): ArwImage = try {
        parse(file)
    } catch (e: IndexOutOfBoundsException) {
        throw IOException("Damaged or unsupported DNG file", e)
    } catch (e: BufferUnderflowException) {
        throw IOException("Damaged or unsupported DNG file", e)
    } catch (e: NegativeArraySizeException) {
        throw IOException("Damaged or unsupported DNG file", e)
    }

    private fun parse(file: ByteArray): ArwImage {
        val (data, firstOffset) = TiffDirectory.open(file)
        val main = TiffDirectory.read(data, firstOffset)
        if (TAG_DNG_VERSION !in main) throw IOException("Not a DNG file")
        // The full-size raw image is the first directory itself (as Android writes it) or one under it (as Adobe does).
        val directories = listOf(main) + (main.ints(TAG_SUB_IFDS)?.map { TiffDirectory.read(data, it) } ?: emptyList())
        val fullSize = directories.filter { (it.int(TAG_NEW_SUBFILE_TYPE) ?: 0) == 0 }
        val raw = fullSize.firstOrNull { it.int(TAG_PHOTOMETRIC) == PHOTOMETRIC_CFA } ?: throw IOException(
            if (fullSize.any { it.int(TAG_PHOTOMETRIC) == PHOTOMETRIC_LINEAR_RAW }) {
                "Linear DNGs, whose colours are already filled in (such as Expert RAW), are not supported yet"
            } else {
                "The file holds no raw image"
            },
        )
        // Colour settings belong in the first directory, but some writers put them with the raw image.
        fun floatsOf(tag: Int) = main.floats(tag) ?: raw.floats(tag)
        fun intOf(tag: Int) = main.int(tag) ?: raw.int(tag)

        val width = raw.int(TAG_WIDTH) ?: throw IOException("Missing raw image size")
        val height = raw.int(TAG_HEIGHT) ?: throw IOException("Missing raw image size")
        if (width < 8 || height < 8 || width.toLong() * height > MAX_PHOTOSITES) throw IOException("Unsupported raw image size")
        val bits = raw.int(TAG_BITS_PER_SAMPLE) ?: 16
        if (bits !in 8..16) throw IOException("Unsupported raw sample size: $bits bits")
        if ((raw.int(TAG_SAMPLES_PER_PIXEL) ?: 1) != 1) throw IOException("Unsupported raw image layout")

        val active = raw.ints(TAG_ACTIVE_AREA)?.takeIf { it.size == 4 } ?: intArrayOf(0, 0, height, width)
        val (activeTop, activeLeft, activeBottom, activeRight) = active
        if (activeTop < 0 || activeLeft < 0 || activeBottom > height || activeRight > width ||
            activeBottom - activeTop < 8 || activeRight - activeLeft < 8
        ) {
            throw IOException("Damaged active area")
        }
        val activeWidth = activeRight - activeLeft
        val activeHeight = activeBottom - activeTop
        val redAt = redSite(raw)

        val sensor = ShortArray(width * height)
        readSamples(file, data.order(), raw, sensor, width, height, bits)
        Levels(raw, bits, activeWidth, activeHeight).apply(sensor, width, activeLeft, activeTop)
        gainMaps(raw.bytes(TAG_OPCODES_2)).forEach { it.apply(sensor, width, activeLeft, activeTop, activeWidth, activeHeight) }

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
        return ArwImage(
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
            orientation = main.int(TAG_ORIENTATION) ?: 1,
            sensor = sensor,
        )
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
            throw IOException("Unsupported sensor colour layout")
        }
        val red = pattern.indexOf(0)
        if (pattern[3 - red] != 2) throw IOException("Unsupported sensor colour layout")
        return (red % 2) to (red / 2)
    }

    /** Reads the strips or tiles of samples into [into], as they are stored. */
    private fun readSamples(file: ByteArray, order: ByteOrder, raw: TiffDirectory, into: ShortArray, width: Int, height: Int, bits: Int) {
        val tiled = TAG_TILE_OFFSETS in raw
        val offsets = raw.ints(if (tiled) TAG_TILE_OFFSETS else TAG_STRIP_OFFSETS) ?: throw IOException("Missing raw image data")
        val lengths = raw.ints(if (tiled) TAG_TILE_BYTE_COUNTS else TAG_STRIP_BYTE_COUNTS)?.takeIf { it.size == offsets.size }
            ?: throw IOException("Missing raw image data")
        val tileWidth = if (tiled) raw.int(TAG_TILE_WIDTH) ?: 0 else width
        // Rows per strip is unsigned, and its largest value, the default, means one strip for the whole image.
        val rowsPerStrip = raw.int(TAG_ROWS_PER_STRIP)?.let { it.toLong() and 0xFFFFFFFFL } ?: height.toLong()
        val tileLength = if (tiled) raw.int(TAG_TILE_LENGTH) ?: 0 else rowsPerStrip.coerceAtMost(height.toLong()).toInt()
        if (tileWidth <= 0 || tileLength <= 0) throw IOException("Damaged raw image layout")
        val across = (width + tileWidth - 1) / tileWidth
        val down = (height + tileLength - 1) / tileLength
        if (across.toLong() * tileWidth * down * tileLength > 2 * MAX_PHOTOSITES) throw IOException("Damaged raw image layout")
        if (offsets.size < across * down) throw IOException("Missing raw image data")
        val compression = raw.int(TAG_COMPRESSION) ?: COMPRESSION_NONE
        for (index in 0 until across * down) {
            val left = index % across * tileWidth
            val top = index / across * tileLength
            when (compression) {
                COMPRESSION_NONE -> unpack(file, order, offsets[index], lengths[index], into, width, height, left, top, tileWidth, tileLength, bits)
                COMPRESSION_LOSSLESS_JPEG -> LosslessJpeg.decode(file, offsets[index], lengths[index], into, width, height, left, top, tileWidth, tileLength)
                COMPRESSION_JPEG_XL -> throw IOException("DNGs compressed with JPEG XL are not supported yet")
                else -> throw IOException("Unsupported DNG compression: $compression")
            }
        }
    }

    /** Uncompressed samples: 8 or 16 bits as bytes or words in the file's byte order, other sizes packed most significant bit first. */
    private fun unpack(
        file: ByteArray, order: ByteOrder, offset: Int, length: Int, into: ShortArray,
        width: Int, height: Int, left: Int, top: Int, tileWidth: Int, tileLength: Int, bits: Int,
    ) {
        val rowBytes = (tileWidth.toLong() * bits + 7) / 8
        if (offset < 0 || offset + rowBytes * tileLength.coerceAtMost(height - top) > file.size) throw IOException("Raw image data is cut short")
        val littleEndian = order == ByteOrder.LITTLE_ENDIAN
        for (row in 0 until minOf(tileLength, height - top)) {
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
     * Linearization, black level and white level: brings each photosite of the active area from what the file
     * stores to light above black, scaled so that the sensor's white is [WHITE]. Photosites outside the active
     * area are set to black.
     */
    private class Levels(raw: TiffDirectory, bits: Int, private val activeWidth: Int, private val activeHeight: Int) {
        private val table = raw.ints(TAG_LINEARIZATION)?.takeIf { it.isNotEmpty() }
        private val repeat = raw.ints(TAG_BLACK_REPEAT)?.takeIf { it.size == 2 && it[0] in 1..16 && it[1] in 1..16 } ?: intArrayOf(1, 1)
        private val black = raw.floats(TAG_BLACK_LEVEL)?.takeIf { it.size == repeat[0] * repeat[1] && it.all(Float::isFinite) }
            ?: FloatArray(repeat[0] * repeat[1])
        private val deltaH = raw.floats(TAG_BLACK_DELTA_H)?.takeIf { it.size == activeWidth && it.all(Float::isFinite) }
        private val deltaV = raw.floats(TAG_BLACK_DELTA_V)?.takeIf { it.size == activeHeight && it.all(Float::isFinite) }
        private val white = raw.floats(TAG_WHITE_LEVEL)?.firstOrNull()?.takeIf { it.isFinite() && it > 0 }
            ?: ((table?.maxOrNull() ?: ((1 shl bits) - 1)).toFloat())

        fun apply(sensor: ShortArray, width: Int, activeLeft: Int, activeTop: Int) {
            val highestBlack = black.max() + (deltaH?.max() ?: 0f) + (deltaV?.max() ?: 0f)
            if (!highestBlack.isFinite() || !(white - highestBlack >= 16f) || !(white - highestBlack).isFinite()) {
                throw IOException("Damaged black or white level")
            }
            val scale = WHITE / (white - highestBlack)
            sensor.fill(0, 0, activeTop * width)
            sensor.fill(0, (activeTop + activeHeight) * width, sensor.size)
            for (y in 0 until activeHeight) {
                val row = (activeTop + y) * width + activeLeft
                val blackRow = y % repeat[0] * repeat[1]
                val rowDelta = deltaV?.get(y) ?: 0f
                sensor.fill(0, (activeTop + y) * width, row)
                sensor.fill(0, row + activeWidth, (activeTop + y + 1) * width)
                for (x in 0 until activeWidth) {
                    val stored = sensor[row + x].toInt() and 0xFFFF
                    val linear = table?.let { it[stored.coerceAtMost(it.size - 1)] } ?: stored
                    val level = black[blackRow + x % repeat[1]] + rowDelta + (deltaH?.get(x) ?: 0f)
                    sensor[row + x] = ((linear - level) * scale).roundToInt().coerceIn(0, MAX_SENSOR).toShort()
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
    ) {
        /** Multiplies the photosites of the active area, placed at [activeLeft], [activeTop] in [sensor], by the map. */
        fun apply(sensor: ShortArray, width: Int, activeLeft: Int, activeTop: Int, activeWidth: Int, activeHeight: Int) {
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
                val along = (((y + 0.5) / activeHeight - originDown) / spacingDown).coerceIn(0.0, pointsDown - 1.0)
                val row = along.toInt().coerceAtMost(pointsDown - 2).coerceAtLeast(0)
                val rowPast = (along - row).toFloat()
                val nextRow = if (pointsDown > 1) row + 1 else row
                val rowStart = (activeTop + y) * width + activeLeft
                columns.forEachIndexed { index, x ->
                    val column = columnPoint[index]
                    val nextColumn = if (pointsAcross > 1) column + 1 else column
                    val past = columnPast[index]
                    val upper = gain(row, column) + (gain(row, nextColumn) - gain(row, column)) * past
                    val lower = gain(nextRow, column) + (gain(nextRow, nextColumn) - gain(nextRow, column)) * past
                    val factor = upper + (lower - upper) * rowPast
                    val at = rowStart + x
                    sensor[at] = (sensor[at] * factor).roundToInt().coerceIn(0, MAX_SENSOR).toShort()
                }
            }
        }

        private fun gain(row: Int, column: Int) = gains[(row * pointsAcross + column) * mapPlanes]
    }

    /**
     * The gain maps among the opcodes that apply to the raw image (OpcodeList2), which phones use for the lens's
     * shading; other opcodes are left out. Opcode lists are big-endian whatever the file's byte order.
     */
    private fun gainMaps(list: ByteArray?): List<GainMap> {
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
                if (id == OPCODE_GAIN_MAP) gainMap(data, body, size)?.let(result::add)
                at = body + size
            }
        } catch (e: IndexOutOfBoundsException) {
            // A damaged list: the maps read so far are kept.
        }
        return result
    }

    private fun gainMap(data: ByteBuffer, at: Int, size: Int): GainMap? {
        if (size < 76) return null
        val plane = data.getInt(at + 16)
        val planes = data.getInt(at + 20)
        val pointsDown = data.getInt(at + 32)
        val pointsAcross = data.getInt(at + 36)
        val mapPlanes = data.getInt(at + 72)
        // A raw mosaic has one plane, so a map that does not cover the first is of no use here.
        if (plane != 0 || planes < 1 || pointsDown < 1 || pointsAcross < 1 || mapPlanes < 1) return null
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
        )
        val usable = map.spacingDown > 0 && map.spacingAcross > 0 && map.spacingDown.isFinite() && map.spacingAcross.isFinite() &&
            map.originDown.isFinite() && map.originAcross.isFinite() && map.gains.all { it.isFinite() && it >= 0f }
        return map.takeIf { usable }
    }

    /**
     * The colour matrix from the camera's colours to linear sRGB, and the white balance the photo was taken with,
     * worked out as dcraw does: the camera's response to sRGB's primaries, each row scaled so that white gives one,
     * and inverted. The colour matrix measured under the light nearest to daylight is used.
     */
    private class Colors(floats: (Int) -> FloatArray?, ints: (Int) -> Int?) {
        val matrix: FloatArray
        val whiteBalance: FloatArray

        init {
            val second = floats(TAG_COLOR_MATRIX_2)?.takeIf(::usable)
            val first = floats(TAG_COLOR_MATRIX_1)?.takeIf(::usable)
            val useSecond = second != null && (first == null || rank(ints(TAG_ILLUMINANT_2)) < rank(ints(TAG_ILLUMINANT_1)))
            val colorMatrix = (if (useSecond) second else first) ?: throw IOException("Missing colour matrix")
            val calibration = floats(if (useSecond) TAG_CALIBRATION_2 else TAG_CALIBRATION_1)?.takeIf(::usable)
            val analog = floats(TAG_ANALOG_BALANCE)?.takeIf { it.size == 3 && it.all { value -> value.isFinite() && value > 0f } }
            var cameraOfXyz = colorMatrix
            if (calibration != null) cameraOfXyz = times(calibration, cameraOfXyz)
            if (analog != null) cameraOfXyz = FloatArray(9) { analog[it / 3] * cameraOfXyz[it] }

            val cameraOfRgb = times(cameraOfXyz, XYZ_OF_SRGB)
            // How the camera sees sRGB's white, which is also the white balance for daylight.
            val white = FloatArray(3) { row -> cameraOfRgb[3 * row] + cameraOfRgb[3 * row + 1] + cameraOfRgb[3 * row + 2] }
            if (white.any { !(it > 0f) }) throw IOException("Unusable colour matrix")
            matrix = inverse(FloatArray(9) { cameraOfRgb[it] / white[it / 3] }) ?: throw IOException("Unusable colour matrix")

            val neutral = floats(TAG_AS_SHOT_NEUTRAL)?.takeIf { it.size == 3 && it.all { value -> value.isFinite() && value > 0f } } ?: white
            whiteBalance = floatArrayOf(neutral[1] / neutral[0], 1f, neutral[1] / neutral[2])
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
