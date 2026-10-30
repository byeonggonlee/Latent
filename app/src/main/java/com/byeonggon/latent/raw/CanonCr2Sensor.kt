package com.byeonggon.latent.raw

import java.io.IOException

/** Decode full-resolution Bayer CR2s; reduced-resolution colour JPEGs require a different pipeline. */
internal object CanonCr2Sensor {
    fun decode(file: ByteArray, metadata: CanonCr2Metadata.Metadata, checkCancelled: () -> Unit = {}): ShortArray {
        val offset = metadata.offset
        val length = metadata.length
        if (offset < 0 || length < 4 || offset.toLong() + length > file.size ||
            metadata.width <= 0 || metadata.height <= 0 ||
            metadata.width.toLong() * metadata.height > Int.MAX_VALUE) throw IOException("Invalid CR2 sensor range")
        fun u8(p: Int) = file[p].toInt() and 0xff
        fun u16(p: Int) = (u8(p) shl 8) or u8(p + 1)
        val end = offset + length
        if (u8(offset) != 0xff || u8(offset + 1) != 0xd8) throw IOException("Missing lossless JPEG")
        var at = offset + 2
        var found = false
        while (at + 4 <= end) {
            checkCancelled()
            if (u8(at) != 0xff) throw IOException("Damaged JPEG header")
            if (u8(at + 1) == 0xff) { at++; continue }
            val marker = u8(at + 1)
            val size = u16(at + 2)
            val next = at.toLong() + 2 + size
            if (size < 2 || next > end) throw IOException("Damaged JPEG segment")
            if (marker == 0xc3) {
                val body = at + 4
                if (size < 8) throw IOException("Damaged lossless JPEG frame")
                val precision = u8(body)
                val lines = u16(body + 1)
                val columns = u16(body + 3)
                val components = u8(body + 5)
                if (precision != 14 || components !in listOf(2, 4) || size < 8 + 3 * components ||
                    columns.toLong() * lines * components != metadata.width.toLong() * metadata.height ||
                    (0 until components).any { u8(body + 7 + 3 * it) != 0x11 }) {
                    throw IOException("Unsupported Canon raw JPEG frame")
                }
                found = true
                break
            }
            if (marker == 0xda || marker == 0xd9) break
            at = next.toInt()
        }
        if (!found) throw IOException("Missing lossless JPEG frame")
        val sampleCount = metadata.width * metadata.height
        Cr2Slices.validate(sampleCount, metadata.width, metadata.height, metadata.slices)
        checkCancelled()
        val samples = ShortArray(sampleCount)
        LosslessJpeg.decode(file, offset, length, samples, metadata.width, metadata.height, 0, 0,
            metadata.width, metadata.height, checkCancelled)
        return Cr2Slices.join(samples, metadata.width, metadata.height, metadata.slices, checkCancelled)
    }
}
