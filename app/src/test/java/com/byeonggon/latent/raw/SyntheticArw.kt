package com.byeonggon.latent.raw

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds small files in Sony's raw layout, so the reader can be tested without anyone's photos. */
internal object SyntheticArw {
    const val WIDTH = 64
    const val HEIGHT = 16
    const val CROP_LEFT = 4
    const val CROP_TOP = 4
    const val CROP_WIDTH = 56
    const val CROP_HEIGHT = 8
    const val BLACK = 512
    val WHITE_BALANCE = intArrayOf(2048, 1024, 1024, 1536)
    val DISTORTION = intArrayOf(40, 4, -32, -65, -101, -137, -165, -172, -122, 29, 309)
    val RED_ABERRATION = IntArray(11) { 2944 - 128 * it }
    val BLUE_ABERRATION = IntArray(11) { -768 + 128 * it }

    /** A file whose red, green and blue photosites all hold the given stored (11-bit) values. */
    fun file(
        red: Int, green: Int, blue: Int,
        make: String = "SONY", orientation: Int = 1,
        whiteBalance: IntArray? = WHITE_BALANCE, privateEntries: List<Entry> = emptyList(),
    ): ByteArray {
        val strip = ByteArrayOutputStream()
        for (row in 0 until HEIGHT) {
            repeat(WIDTH / 32) {
                // Even columns first, then odd ones: red/green on even rows, green/blue on odd rows.
                strip.write(flatBlock(if (row % 2 == 0) red else green))
                strip.write(flatBlock(if (row % 2 == 0) green else blue))
            }
        }
        val tiff = Tiff()
        val subDirectory = tiff.reserve()
        val locator = if (privateEntries.isNotEmpty()) tiff.reserve() else null
        tiff.directory(
            8, *listOfNotNull(
                Entry.ascii(0x010F, make),
                Entry.shorts(0x0112, orientation),
                Entry.longs(0x014A, subDirectory),
                locator?.let { Entry.bytes(0xC634, it and 255, (it ushr 8) and 255, (it ushr 16) and 255, (it ushr 24) and 255) },
        ).toTypedArray())
        val stripOffset = tiff.reserve()
        tiff.directory(
            subDirectory, *listOfNotNull(
                Entry.shorts(0x0100, WIDTH),
                Entry.shorts(0x0101, HEIGHT),
                Entry.shorts(0x0103, 32767),
                Entry.longs(0x0111, stripOffset),
                Entry.longs(0x0117, WIDTH * HEIGHT),
                Entry.bytes(0x828E, 0, 1, 1, 2),
                Entry.shorts(0x7010, 8000, 10400, 12900, 14100),
                Entry.signedShorts(0x7035, 22, *RED_ABERRATION, *BLUE_ABERRATION),
                Entry.signedShorts(0x7037, 11, *DISTORTION),
                Entry.shorts(0x7310, BLACK, BLACK, BLACK, BLACK),
                whiteBalance?.let { Entry.signedShorts(0x7313, *it) },
                Entry.shorts(0xC61D, 16383),
                Entry.longs(0xC61F, CROP_LEFT, CROP_TOP),
                Entry.longs(0xC620, CROP_WIDTH, CROP_HEIGHT),
        ).toTypedArray())
        if (locator != null) {
            val privateOffset = tiff.reserve()
            val plain = Tiff(base = privateOffset).apply { directory(0, *privateEntries.toTypedArray()) }.bytes()
            val padded = plain.copyOf((plain.size + 3) / 4 * 4)
            val data = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
            val words = IntArray(padded.size / 4) { data.getInt(4 * it) }
            // XOR scrambling is its own inverse; real camera files independently exercise decryption.
            SonyArw.unscramble(words, 0x44332211)
            words.forEachIndexed { index, word -> data.putInt(4 * index, word) }
            tiff.directory(locator,
                Entry.longs(0x7200, privateOffset), Entry.longs(0x7201, padded.size),
                Entry.bytes(0x7221, 0x11, 0x22, 0x33, 0x44),
            )
            tiff.write(privateOffset, padded)
        }
        tiff.write(stripOffset, strip.toByteArray())
        return tiff.bytes()
    }

    /** Sixteen photosites that all hold [value]: it is both the block's maximum and its minimum. */
    fun flatBlock(value: Int): ByteArray = block(max = value, min = value, maxIndex = 0, minIndex = 1, offsets = IntArray(14))

    /** One compressed block: the extremes, where they sit, and a 7-bit offset for each other photosite. */
    fun block(max: Int, min: Int, maxIndex: Int, minIndex: Int, offsets: IntArray): ByteArray {
        val bits = java.math.BigInteger.valueOf(
            (max.toLong() or (min.toLong() shl 11) or (maxIndex.toLong() shl 22) or (minIndex.toLong() shl 26)),
        ).let { header -> offsets.foldIndexed(header) { index, all, offset -> all.or(java.math.BigInteger.valueOf(offset.toLong()).shiftLeft(30 + 7 * index)) } }
        return ByteArray(16) { index -> bits.shiftRight(8 * index).toInt().toByte() }
    }

    class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray) {
        companion object {
            fun ascii(tag: Int, text: String) = Entry(tag, 2, text.length + 1, text.toByteArray() + 0)
            fun bytes(tag: Int, vararg values: Int) = Entry(tag, 1, values.size, ByteArray(values.size) { values[it].toByte() })
            fun shorts(tag: Int, vararg values: Int) = Entry(tag, 3, values.size, pack(2, values))
            fun signedShorts(tag: Int, vararg values: Int) = Entry(tag, 8, values.size, pack(2, values))
            fun longs(tag: Int, vararg values: Int) = Entry(tag, 4, values.size, pack(4, values))

            private fun pack(size: Int, values: IntArray): ByteArray {
                val buffer = ByteBuffer.allocate(size * values.size).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach { if (size == 2) buffer.putShort(it.toShort()) else buffer.putInt(it) }
                return buffer.array()
            }
        }
    }

    /** Lays directories and their values out one after another in a little-endian TIFF. */
    class Tiff(private val base: Int = 0) {
        private val buffer = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        private var end = 4096 // values and later directories go here, clear of the first directory

        init {
            buffer.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        }

        /** Sets aside room and returns its offset, to be filled with [directory] or [write]. */
        fun reserve(): Int = end.also { end += 4096 }

        fun directory(offset: Int, vararg entries: Entry) {
            buffer.putShort(offset, entries.size.toShort())
            entries.forEachIndexed { index, entry ->
                val at = offset + 2 + 12 * index
                buffer.putShort(at, entry.tag.toShort()).putShort(at + 2, entry.type.toShort()).putInt(at + 4, entry.count)
                if (entry.data.size <= 4) {
                    entry.data.forEachIndexed { byte, value -> buffer.put(at + 8 + byte, value) }
                } else {
                    buffer.putInt(at + 8, end + base)
                    write(end, entry.data)
                    end += entry.data.size + (entry.data.size and 1)
                }
            }
        }

        fun write(offset: Int, data: ByteArray) {
            data.forEachIndexed { index, value -> buffer.put(offset + index, value) }
            end = maxOf(end, offset + data.size)
        }

        fun bytes(): ByteArray = buffer.array().copyOf(end)
    }
}
