package com.byeonggon.latent.raw

import java.io.IOException

/** CR2 structure and camera settings; unfamiliar layouts are refused instead of guessed. */
internal object CanonCr2Metadata {
    data class Metadata(val model: String, val width: Int, val height: Int, val orientation: Int, val crop: IntArray, val whiteBalance: FloatArray, val slices: IntArray, val offset: Int, val length: Int)

    fun read(bytes: ByteArray): Metadata = try {
        if (bytes.size < 16 || bytes[8] != 'C'.code.toByte() || bytes[9] != 'R'.code.toByte() ||
            bytes[10] != 2.toByte() || bytes[11] != 0.toByte()) throw IOException("Not a supported CR2 header")
        val (data, firstOffset) = TiffDirectory.open(bytes)
        val main = TiffDirectory.read(data, firstOffset)
        if (main.text(0x10f) != "Canon") throw IOException("Not a Canon raw file")
        val model = main.text(0x110)?.takeIf { it.isNotEmpty() } ?: throw IOException("Missing camera model")
        val exif = TiffDirectory.read(data, main.int(0x8769) ?: throw IOException("Missing EXIF directory"))
        val maker = exif.embeddedDirectory(0x927c) ?: throw IOException("Missing Canon MakerNote")
        val sensor = maker.ints(0xe0)?.takeIf { it.size >= 9 } ?: throw IOException("Missing sensor information")
        val width = sensor[1]; val height = sensor[2]
        val left = sensor[5]; val top = sensor[6]
        val cropWidth = sensor[7].toLong() - left + 1; val cropHeight = sensor[8].toLong() - top + 1
        if (width <= 0 || height <= 0 || width.toLong() * height > Int.MAX_VALUE || left < 0 || top < 0 ||
            left % 2 != 0 || top % 2 != 0 || cropWidth <= 0 || cropHeight <= 0 ||
            left.toLong() + cropWidth > width || top.toLong() + cropHeight > height) throw IOException("Invalid sensor crop")
        val colour = maker.ints(0x4001)?.takeIf { it.size == 1337 && it[0] == 7 }
            ?: throw IOException("Unsupported Canon colour metadata")
        val wb = colour.copyOfRange(63, 67)
        if (wb.any { it <= 0 } || wb[1] != wb[2]) throw IOException("Unsupported as-shot white balance")
        val raw = TiffDirectory.read(data, data.getInt(12))
        if (raw.int(0x103) != 6) throw IOException("Unsupported Canon raw compression")
        val offset = raw.int(0x111) ?: throw IOException("Missing raw data offset")
        val length = raw.int(0x117) ?: throw IOException("Missing raw data length")
        if (offset < 0 || length <= 0 || offset.toLong() + length > bytes.size) throw IOException("Invalid raw data range")
        val slices = raw.ints(0xc640) ?: throw IOException("Missing raw slice layout")
        Metadata(model, width, height, main.int(0x112)?.takeIf { it in 1..8 } ?: 1,
            intArrayOf(left, top, cropWidth.toInt(), cropHeight.toInt()),
            floatArrayOf(wb[0].toFloat() / wb[1], 1f, wb[3].toFloat() / wb[1]), slices, offset, length)
    } catch (e: IndexOutOfBoundsException) {
        throw IOException("Damaged Canon raw metadata", e)
    }
}
