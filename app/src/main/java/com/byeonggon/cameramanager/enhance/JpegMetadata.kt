package com.byeonggon.cameramanager.enhance

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Moves the Exif block of a JPEG, untouched, onto another JPEG. */
object JpegMetadata {
    private const val MARKER = 0xFF
    private const val START_OF_IMAGE = 0xD8
    private const val START_OF_SCAN = 0xDA
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

    /**
     * Returns [image] carrying the Exif block of [original] in place of its own metadata. Copying the
     * block byte for byte keeps everything a camera wrote, including maker notes that do not survive
     * being rewritten tag by tag.
     */
    @Throws(IOException::class)
    fun withExifOf(original: ByteArray, image: ByteArray): ByteArray {
        val exif = segments(original).firstOrNull { it.isExif(original) }
        val output = ByteArrayOutputStream(image.size + (exif?.length ?: 0))
        output.write(MARKER)
        output.write(START_OF_IMAGE)
        if (exif != null) output.write(original, exif.start, exif.length)
        var copiedUpTo = 2
        for (segment in segments(image)) {
            // The image's own JFIF header and Exif block give way to the original's.
            if (segment.marker == APP0 || segment.isExif(image)) {
                output.write(image, copiedUpTo, segment.start - copiedUpTo)
                copiedUpTo = segment.start + segment.length
            }
        }
        output.write(image, copiedUpTo, image.size - copiedUpTo)
        return output.toByteArray()
    }

    private class Segment(val marker: Int, val start: Int, val length: Int) {
        fun isExif(jpeg: ByteArray): Boolean =
            marker == APP1 && length >= 4 + EXIF_HEADER.size && EXIF_HEADER.indices.all { jpeg[start + 4 + it] == EXIF_HEADER[it] }
    }

    /** The marker segments between the start of the file and the compressed picture data. */
    private fun segments(jpeg: ByteArray): List<Segment> {
        if (jpeg.size < 4 || jpeg.byteAt(0) != MARKER || jpeg.byteAt(1) != START_OF_IMAGE) {
            throw IOException("Not a JPEG file")
        }
        val segments = ArrayList<Segment>()
        var position = 2
        while (position + 4 <= jpeg.size && jpeg.byteAt(position) == MARKER) {
            val marker = jpeg.byteAt(position + 1)
            if (marker == START_OF_SCAN) break
            val length = 2 + ((jpeg.byteAt(position + 2) shl 8) or jpeg.byteAt(position + 3))
            if (position + length > jpeg.size) throw IOException("Damaged JPEG file")
            segments += Segment(marker, position, length)
            position += length
        }
        return segments
    }

    private fun ByteArray.byteAt(index: Int) = this[index].toInt() and 0xFF
}
