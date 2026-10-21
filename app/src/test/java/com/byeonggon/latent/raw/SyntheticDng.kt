package com.byeonggon.latent.raw

import com.byeonggon.latent.raw.SyntheticArw.Entry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds small DNG files, laid out as phones and Adobe's converter write them, so the reader can be tested without anyone's photos. */
internal object SyntheticDng {
    const val WIDTH = 32
    const val HEIGHT = 24
    const val BLACK = 64
    const val WHITE = 1023

    /** The active area as top, left, bottom, right: a border of masked photosites around it, as on a real sensor. */
    val ACTIVE = intArrayOf(2, 2, 22, 30)

    /** A plausible phone sensor's XYZ to camera matrix under daylight, from a DNG written by Android. */
    val COLOR_MATRIX = floatArrayOf(1.1f, -0.4f, -0.1f, -0.35f, 1.25f, 0.1f, -0.05f, 0.2f, 0.55f)
    val NEUTRAL = floatArrayOf(0.5f, 1f, 0.7f)

    enum class Layout { ANDROID, ADOBE }
    enum class Storage { WORDS, PACKED_12, LOSSLESS_JPEG }

    /**
     * A file whose red, green and blue photosites hold [red], [green] and [blue] above black. [pattern] is the colour
     * of each photosite of the repeating two-by-two block (0 red, 1 green, 2 blue), counted from the active area's corner.
     */
    fun file(
        red: Int,
        green: Int,
        blue: Int,
        pattern: IntArray = intArrayOf(0, 1, 1, 2),
        layout: Layout = Layout.ANDROID,
        storage: Storage = Storage.WORDS,
        blacks: IntArray = intArrayOf(BLACK),
        linearization: IntArray? = null,
        gainMap: GainMap? = null,
        photometric: Int = 32803,
        compression: Int? = null,
        orientation: Int = 1,
    ): ByteArray = file(
        samples = { x, y ->
            val ax = x - ACTIVE[1]
            val ay = y - ACTIVE[0]
            if (ax < 0 || ay < 0 || y >= ACTIVE[2] || x >= ACTIVE[3]) {
                0
            } else {
                val level = when (pattern[(ay and 1) * 2 + (ax and 1)]) {
                    0 -> red
                    1 -> green
                    else -> blue
                }
                val black = if (blacks.size == 4) blacks[(ay and 1) * 2 + (ax and 1)] else blacks[0]
                val stored = level + black
                linearization?.let { table -> table.indexOfFirst { it >= stored } } ?: stored
            }
        },
        pattern = pattern, layout = layout, storage = storage, blacks = blacks, linearization = linearization,
        gainMap = gainMap, photometric = photometric, compression = compression, orientation = orientation,
    )

    fun file(
        samples: (x: Int, y: Int) -> Int,
        pattern: IntArray = intArrayOf(0, 1, 1, 2),
        layout: Layout = Layout.ANDROID,
        storage: Storage = Storage.WORDS,
        blacks: IntArray = intArrayOf(BLACK),
        linearization: IntArray? = null,
        gainMap: GainMap? = null,
        photometric: Int = 32803,
        compression: Int? = null,
        orientation: Int = 1,
    ): ByteArray {
        val values = Array(HEIGHT) { y -> IntArray(WIDTH) { x -> samples(x, y) } }
        val data = when (storage) {
            Storage.WORDS -> ByteBuffer.allocate(WIDTH * HEIGHT * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                values.forEach { row -> row.forEach { putShort(it.toShort()) } }
            }.array()
            Storage.PACKED_12 -> packed(values, 12)
            // Two columns side by side as the components of one, as Adobe's converter stores a mosaic.
            Storage.LOSSLESS_JPEG -> LosslessJpegEncoder.encode(values, components = 2, precision = 16, predictor = 1)
        }
        val tiff = SyntheticArw.Tiff()
        val rawAt = if (layout == Layout.ANDROID) 8 else tiff.reserve()
        val dataAt = tiff.reserve()
        val rawEntries = listOfNotNull(
            Entry.longs(0x00FE, 0),
            Entry.shorts(0x0100, WIDTH),
            Entry.shorts(0x0101, HEIGHT),
            Entry.shorts(0x0102, if (storage == Storage.PACKED_12) 12 else 16),
            Entry.shorts(0x0103, compression ?: if (storage == Storage.LOSSLESS_JPEG) 7 else 1),
            Entry.shorts(0x0106, photometric),
            Entry.longs(0x0111, dataAt),
            Entry.shorts(0x0115, 1),
            Entry.shorts(0x0116, HEIGHT),
            Entry.longs(0x0117, data.size),
            Entry.shorts(0x828D, 2, 2),
            Entry.bytes(0x828E, *pattern),
            linearization?.let { Entry.shorts(0xC618, *it) },
            if (blacks.size == 4) Entry.shorts(0xC619, 2, 2) else null,
            rationals(0xC61A, *FloatArray(blacks.size) { blacks[it].toFloat() }),
            Entry.shorts(0xC61D, linearization?.last() ?: WHITE),
            Entry.longs(0xC61F, 1, 1),
            Entry.longs(0xC620, 26, 18),
            Entry.longs(0xC68D, *ACTIVE),
            gainMap?.let { Entry(0xC741, 7, it.bytes().size, it.bytes()) },
        )
        val colorEntries = listOf(
            Entry.ascii(0x010F, "samsung"),
            Entry.shorts(0x0112, orientation),
            Entry.bytes(0xC612, 1, 4, 0, 0),
            signedRationals(0xC621, 0.9f, -0.3f, 0f, -0.4f, 1.3f, 0.1f, -0.1f, 0.3f, 0.6f),
            signedRationals(0xC622, *COLOR_MATRIX),
            Entry.shorts(0xC65A, 17),
            Entry.shorts(0xC65B, 21),
            rationals(0xC628, *NEUTRAL),
        )
        if (layout == Layout.ANDROID) {
            tiff.directory(8, *(rawEntries + colorEntries).sortedBy { it.tag }.toTypedArray())
        } else {
            tiff.directory(
                8,
                *(colorEntries + listOf(Entry.longs(0x00FE, 1), Entry.shorts(0x0106, 2), Entry.longs(0x014A, rawAt))).sortedBy { it.tag }.toTypedArray(),
            )
            tiff.directory(rawAt, *rawEntries.toTypedArray())
        }
        tiff.write(dataAt, data)
        return tiff.bytes()
    }

    /**
     * A linear DNG, as a Galaxy writes in Pro mode: three samples a pixel, red, green and blue, already filled in, with
     * [red], [green] and [blue] above black in the active area; as words or in lossless JPEG with three components.
     * With [jpegXl], as Expert RAW stores them: the samples of linear(192, 380, 264) from linear-192-380-264.jxl,
     * which also has a gradient in the active area's top left corner (see linear-192-380-264.py beside it).
     */
    fun linear(
        red: Int, green: Int, blue: Int, lossless: Boolean = false, blacks: IntArray = intArrayOf(BLACK, BLACK + 8, BLACK + 16),
        jpegXl: Boolean = false, baselineExposure: Float? = null,
    ): ByteArray {
        require(!jpegXl || (red == 192 && green == 380 && blue == 264 && blacks.contentEquals(intArrayOf(BLACK, BLACK + 8, BLACK + 16))))
        val levels = intArrayOf(red, green, blue)
        val values = Array(HEIGHT) { y ->
            IntArray(WIDTH * 3) { i ->
                val x = i / 3
                val inside = x >= ACTIVE[1] && y >= ACTIVE[0] && y < ACTIVE[2] && x < ACTIVE[3]
                if (inside) levels[i % 3] + blacks[i % 3] else 0
            }
        }
        val data = if (jpegXl) {
            SyntheticDng::class.java.getResourceAsStream("/raw/linear-192-380-264.jxl")!!.use { it.readBytes() }
        } else if (lossless) {
            LosslessJpegEncoder.encode(values, components = 3, precision = 16, predictor = 1)
        } else {
            ByteBuffer.allocate(WIDTH * HEIGHT * 6).order(ByteOrder.LITTLE_ENDIAN).apply {
                values.forEach { row -> row.forEach { putShort(it.toShort()) } }
            }.array()
        }
        val tiff = SyntheticArw.Tiff()
        // Three samples a pixel take more than one reserved block.
        val dataAt = tiff.reserve().also { tiff.reserve() }
        tiff.directory(
            8,
            *listOf(
                Entry.longs(0x00FE, 0),
                Entry.shorts(0x0100, WIDTH),
                Entry.shorts(0x0101, HEIGHT),
                Entry.shorts(0x0102, 16),
                Entry.shorts(0x0103, if (jpegXl) 52546 else if (lossless) 7 else 1),
                Entry.shorts(0x0106, 34892),
                Entry.ascii(0x010F, "samsung"),
                Entry.longs(0x0111, dataAt),
                Entry.shorts(0x0112, 6),
                Entry.shorts(0x0115, 3),
                Entry.shorts(0x0116, HEIGHT),
                Entry.longs(0x0117, data.size),
                Entry.bytes(0xC612, 1, 4, 0, 0),
                Entry.shorts(0xC619, 1, 1),
                rationals(0xC61A, *FloatArray(3) { blacks[it].toFloat() }),
                Entry.shorts(0xC61D, WHITE),
                Entry.longs(0xC61F, 0, 0),
                Entry.longs(0xC620, 28, 20),
                signedRationals(0xC621, *COLOR_MATRIX),
                rationals(0xC628, *NEUTRAL),
                Entry.shorts(0xC65A, 21),
                Entry.longs(0xC68D, *ACTIVE),
            ).plus(listOfNotNull(baselineExposure?.let { signedRationals(0xC62A, it) })).sortedBy { it.tag }.toTypedArray(),
        )
        tiff.write(dataAt, data)
        return tiff.bytes()
    }

    /** A multi-group JPEG XL stream in one linear DNG strip, without anyone's camera metadata. */
    fun jpegXl(bytes: ByteArray, width: Int = 600, height: Int = 520): ByteArray {
        val tiff = SyntheticArw.Tiff()
        val dataAt = tiff.reserve()
        repeat((bytes.size - 1).coerceAtLeast(0) / 4096) { tiff.reserve() }
        tiff.directory(8,
            Entry.longs(0x00FE, 0), Entry.longs(0x0100, width), Entry.longs(0x0101, height),
            Entry.shorts(0x0102, 16), Entry.shorts(0x0103, 52546), Entry.shorts(0x0106, 34892),
            Entry.longs(0x0111, dataAt), Entry.shorts(0x0115, 3), Entry.longs(0x0116, height),
            Entry.longs(0x0117, bytes.size), Entry.bytes(0xC612, 1, 4, 0, 0),
            Entry.longs(0xC61D, 65535), signedRationals(0xC621, *COLOR_MATRIX),
            rationals(0xC628, *NEUTRAL), Entry.shorts(0xC65A, 21),
        )
        tiff.write(dataAt, bytes)
        return tiff.bytes()
    }

    /** One gain map over the whole active area, for every photosite. */
    class GainMap(val pointsDown: Int, val pointsAcross: Int, val gains: FloatArray) {
        fun bytes(): ByteArray {
            val body = ByteBuffer.allocate(76 + 4 * gains.size).order(ByteOrder.BIG_ENDIAN)
            body.putInt(0).putInt(0).putInt(ACTIVE[2] - ACTIVE[0]).putInt(ACTIVE[3] - ACTIVE[1])
            body.putInt(0).putInt(1).putInt(1).putInt(1)
            body.putInt(pointsDown).putInt(pointsAcross)
            body.putDouble(1.0 / (pointsDown - 1)).putDouble(1.0 / (pointsAcross - 1)).putDouble(0.0).putDouble(0.0)
            body.putInt(1)
            gains.forEach { body.putFloat(it) }
            val list = ByteBuffer.allocate(4 + 16 + body.capacity()).order(ByteOrder.BIG_ENDIAN)
            list.putInt(1).putInt(9).putInt(0x01030000).putInt(1).putInt(body.capacity()).put(body.array())
            return list.array()
        }
    }

    fun rationals(tag: Int, vararg values: Float) = Entry(tag, 5, values.size, fractions(values))

    fun signedRationals(tag: Int, vararg values: Float) = Entry(tag, 10, values.size, fractions(values))

    private fun fractions(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(8 * values.size).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putInt(Math.round(it * 10000)).putInt(10000) }
        return buffer.array()
    }

    /** Samples of [bits] each, packed most significant bit first, each row starting on a byte. */
    private fun packed(values: Array<IntArray>, bits: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (row in values) {
            var buffer = 0L
            var count = 0
            for (value in row) {
                buffer = (buffer shl bits) or value.toLong()
                count += bits
                while (count >= 8) {
                    out.write((buffer ushr (count - 8)).toInt() and 0xFF)
                    count -= 8
                }
            }
            if (count > 0) out.write((buffer shl (8 - count)).toInt() and 0xFF)
        }
        return out.toByteArray()
    }
}

/** Writes lossless JPEG with one Huffman table in which every difference size has a five-bit code. */
internal object LosslessJpegEncoder {
    /**
     * [rows] of samples, each row [components] samples to a column, side by side. With [restartLines], a restart
     * marker comes every so many lines.
     */
    fun encode(rows: Array<IntArray>, components: Int, precision: Int, predictor: Int, restartLines: Int = 0): ByteArray {
        val lines = rows.size
        val columns = rows[0].size / components
        val out = ByteArrayOutputStream()
        fun marker(code: Int, vararg body: Int) {
            out.write(0xFF)
            out.write(code)
            out.write((body.size + 2) ushr 8)
            out.write((body.size + 2) and 0xFF)
            body.forEach(out::write)
        }
        out.write(0xFF)
        out.write(0xD8)
        marker(0xC3, precision, lines ushr 8, lines and 0xFF, columns ushr 8, columns and 0xFF, components, *IntArray(3 * components) { if (it % 3 == 0) it / 3 + 1 else if (it % 3 == 1) 0x11 else 0 })
        marker(0xC4, 0x00, *IntArray(16) { if (it == 4) 17 else 0 }, *IntArray(17) { it })
        if (restartLines > 0) marker(0xDD, (restartLines * columns) ushr 8, (restartLines * columns) and 0xFF)
        marker(0xDA, components, *IntArray(2 * components) { if (it % 2 == 0) it / 2 + 1 else 0 }, predictor, 0, 0)

        val bits = BitWriter(out)
        var restarts = 0
        for (line in 0 until lines) {
            val first = line == 0 || (restartLines > 0 && line % restartLines == 0)
            if (first && line > 0) {
                bits.flush()
                out.write(0xFF)
                out.write(0xD0 + restarts++ % 8)
            }
            val row = rows[line]
            val above = if (line > 0) rows[line - 1] else row
            for (index in row.indices) {
                val prediction = when {
                    index < components -> if (first) 1 shl (precision - 1) else above[index]
                    first -> row[index - components]
                    else -> {
                        val left = row[index - components]
                        val up = above[index]
                        val upLeft = above[index - components]
                        when (predictor) {
                            1 -> left
                            2 -> up
                            3 -> upLeft
                            4 -> left + up - upLeft
                            5 -> left + ((up - upLeft) shr 1)
                            6 -> up + ((left - upLeft) shr 1)
                            else -> (left + up) ushr 1
                        }
                    }
                }
                var difference = (row[index] - prediction) and 0xFFFF
                if (difference >= 32768) difference -= 65536
                val size = if (difference == 0) 0 else 32 - Integer.numberOfLeadingZeros(kotlin.math.abs(difference))
                bits.write(size, 5)
                if (size in 1..15) bits.write(if (difference > 0) difference else difference + (1 shl size) - 1, size)
            }
        }
        bits.flush()
        out.write(0xFF)
        out.write(0xD9)
        return out.toByteArray()
    }

    private class BitWriter(private val out: ByteArrayOutputStream) {
        private var buffer = 0L
        private var count = 0

        fun write(value: Int, size: Int) {
            buffer = (buffer shl size) or (value.toLong() and ((1L shl size) - 1))
            count += size
            while (count >= 8) {
                put((buffer ushr (count - 8)).toInt() and 0xFF)
                count -= 8
            }
        }

        /** Pads the last byte with ones, as JPEG asks. */
        fun flush() {
            if (count > 0) write((1 shl (8 - count)) - 1, 8 - count)
        }

        private fun put(byte: Int) {
            out.write(byte)
            if (byte == 0xFF) out.write(0)
        }
    }
}
