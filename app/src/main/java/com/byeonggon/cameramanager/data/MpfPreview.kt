package com.byeonggon.cameramanager.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The preview a camera puts inside its JPEGs, about 1600 pixels wide, listed in the Multi-Picture
 * Format (MPF) block. Decoding it is a hundred times less work than decoding the photo itself.
 */
internal object MpfPreview {
    /** The preview's JPEG bytes, or null if [file] has none. A gain map of an HDR photo is not a preview. */
    fun of(file: File): ByteArray? = try {
        RandomAccessFile(file, "r").use { input ->
            val head = ByteArray(minOf(HEAD_BYTES.toLong(), input.length()).toInt())
            input.readFully(head)
            val (start, size) = previewIn(head) ?: return null
            if (size !in 1..MAX_PREVIEW_BYTES || start + size > input.length()) return null
            val preview = ByteArray(size)
            input.seek(start)
            input.readFully(preview)
            preview.takeIf { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
        }
    } catch (e: IOException) {
        null
    }

    /** Where in the file the largest preview starts, and how long it is, from the first bytes of a JPEG. */
    internal fun previewIn(head: ByteArray): Pair<Long, Int>? {
        if (head.size < 4 || head.u8(0) != 0xFF || head.u8(1) != 0xD8) return null
        var at = 2
        while (at + 4 <= head.size && head.u8(at) == 0xFF) {
            val marker = head.u8(at + 1)
            // Image data follows the start of a scan; MPF comes before it.
            if (marker == 0xDA || marker == 0xD9) return null
            val length = (head.u8(at + 2) shl 8) or head.u8(at + 3)
            val payload = at + 4
            if (marker == 0xE2 && payload + 8 <= head.size && head.ascii(payload, 4) == "MPF\u0000") {
                return entriesIn(head, tiffStart = payload + 4)
            }
            at += 2 + length
        }
        return null
    }

    private fun entriesIn(head: ByteArray, tiffStart: Int): Pair<Long, Int>? {
        val little = when (head.ascii(tiffStart, 2)) {
            "II" -> true
            "MM" -> false
            else -> return null
        }
        fun u16(at: Int) = if (little) head.u8(at) or (head.u8(at + 1) shl 8) else (head.u8(at) shl 8) or head.u8(at + 1)
        fun u32(at: Int) = if (little) {
            u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        } else {
            (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }

        val ifd = tiffStart + u32(tiffStart + 4).toInt()
        if (ifd + 2 > head.size) return null
        for (index in 0 until u16(ifd)) {
            val tag = ifd + 2 + index * 12
            if (tag + 12 > head.size) return null
            if (u16(tag) != MP_ENTRY) continue
            val count = u32(tag + 4).toInt() / ENTRY_BYTES
            val entries = tiffStart + u32(tag + 8).toInt()
            var best: Pair<Long, Int>? = null
            for (entry in 0 until count) {
                val at = entries + entry * ENTRY_BYTES
                if (at + ENTRY_BYTES > head.size) return best
                val type = u32(at) and TYPE_MASK
                val size = u32(at + 4)
                if (type != LARGE_THUMBNAIL_VGA && type != LARGE_THUMBNAIL_FULL_HD) continue
                if (size > Int.MAX_VALUE || (best != null && size <= best.second)) continue
                best = (tiffStart + u32(at + 8)) to size.toInt()
            }
            return best
        }
        return null
    }

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF

    private fun ByteArray.ascii(at: Int, length: Int) = if (at + length > size) "" else String(this, at, length, Charsets.ISO_8859_1)

    private const val HEAD_BYTES = 256 * 1024
    private const val MAX_PREVIEW_BYTES = 8 * 1024 * 1024
    private const val MP_ENTRY = 0xB002
    private const val ENTRY_BYTES = 16
    private const val TYPE_MASK = 0xFFFFFFL
    private const val LARGE_THUMBNAIL_VGA = 0x010001L
    private const val LARGE_THUMBNAIL_FULL_HD = 0x010002L
}
