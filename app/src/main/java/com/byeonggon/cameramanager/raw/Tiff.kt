package com.byeonggon.cameramanager.raw

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One directory of a TIFF file: just enough of the format to find a camera's sensor data and settings. */
internal class TiffDirectory private constructor(
    private val data: ByteBuffer,
    private val entries: Map<Int, Entry>,
) {
    private class Entry(val type: Int, val count: Int, val position: Int)

    operator fun contains(tag: Int) = tag in entries

    /** The integer values stored under [tag], or null when it is absent or holds something else. */
    fun ints(tag: Int): IntArray? {
        val entry = entries[tag] ?: return null
        if (entry.count < 0 || entry.count > MAX_VALUES) return null
        val position = entry.position
        return when (entry.type) {
            BYTE, UNDEFINED -> IntArray(entry.count) { data.get(position + it).toInt() and 0xFF }
            SHORT -> IntArray(entry.count) { data.getShort(position + 2 * it).toInt() and 0xFFFF }
            SIGNED_SHORT -> IntArray(entry.count) { data.getShort(position + 2 * it).toInt() }
            LONG, SIGNED_LONG -> IntArray(entry.count) { data.getInt(position + 4 * it) }
            else -> null
        }
    }

    fun int(tag: Int): Int? = ints(tag)?.firstOrNull()

    fun text(tag: Int): String? {
        val entry = entries[tag]?.takeIf { it.type == ASCII && it.count in 0..MAX_VALUES } ?: return null
        val bytes = ByteArray(entry.count) { data.get(entry.position + it) }
        return String(bytes, Charsets.US_ASCII).substringBefore('\u0000').trim()
    }

    companion object {
        private const val BYTE = 1
        private const val ASCII = 2
        private const val SHORT = 3
        private const val LONG = 4
        private const val UNDEFINED = 7
        private const val SIGNED_SHORT = 8
        private const val SIGNED_LONG = 9
        private val TYPE_SIZES = intArrayOf(1, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)
        private const val MAX_VALUES = 1 shl 20

        /** Wraps a whole TIFF file and returns it with the offset of its first directory. */
        fun open(file: ByteArray): Pair<ByteBuffer, Int> {
            if (file.size < 8) throw IOException("Not a TIFF file")
            val order = when {
                file[0] == 'I'.code.toByte() && file[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                file[0] == 'M'.code.toByte() && file[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> throw IOException("Not a TIFF file")
            }
            val data = ByteBuffer.wrap(file).order(order)
            return data to data.getInt(4)
        }

        /**
         * Reads the directory at [offset]. Value offsets inside it are relative to the start of the
         * original file; [base] is where [data] starts within that file.
         */
        fun read(data: ByteBuffer, offset: Int, base: Int = 0): TiffDirectory {
            val count = data.getShort(offset).toInt() and 0xFFFF
            val entries = HashMap<Int, Entry>(count * 2)
            for (index in 0 until count) {
                val at = offset + 2 + 12 * index
                val tag = data.getShort(at).toInt() and 0xFFFF
                val type = data.getShort(at + 2).toInt() and 0xFFFF
                val valueCount = data.getInt(at + 4)
                val size = (TYPE_SIZES.getOrNull(type) ?: 1).toLong() * valueCount
                val position = if (size in 0..4) at + 8 else data.getInt(at + 8) - base
                entries[tag] = Entry(type, valueCount, position)
            }
            return TiffDirectory(data, entries)
        }
    }
}
