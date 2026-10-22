package com.byeonggon.latent.enhance

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Moves the Exif block of a JPEG, untouched, onto another JPEG. */
object JpegMetadata {
    private const val MARKER = 0xFF
    private const val START_OF_IMAGE = 0xD8
    private const val START_OF_SCAN = 0xDA
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val TAG_ORIENTATION = 0x0112
    private const val ORIENTATION_NORMAL = 1
    private const val DIRECTORY_ENTRY_SIZE = 12
    private const val TIFF_HEADER_SIZE = 8
    private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

    /**
     * Returns [image] carrying the Exif block of [original] in place of its own metadata. Copying the
     * block byte for byte keeps everything a camera wrote, including maker notes that do not survive
     * being rewritten tag by tag.
     *
     * [upright] says that the image's pixels were turned the way the original was meant to be viewed.
     * The block then no longer asks viewers to turn the picture, and no longer offers the camera's
     * small preview, which shows the picture as it was. A block whose directory cannot be read is then
     * left out: kept, it might still ask viewers to turn a picture that is turned already.
     */
    @Throws(IOException::class)
    fun withExifOf(original: ByteArray, image: ByteArray, upright: Boolean = false): ByteArray {
        val exif = segments(original).firstOrNull { it.isExif(original) }
            ?.let { original.copyOfRange(it.start, it.start + it.length) }
            ?.takeUnless { upright && !markUpright(it) }
        val output = ByteArrayOutputStream(image.size + (exif?.size ?: 0))
        output.write(MARKER)
        output.write(START_OF_IMAGE)
        if (exif != null) output.write(exif)
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

    /**
     * Changes an Exif segment in place: the orientation becomes "as stored", and the first directory
     * no longer leads on to the one that holds the thumbnail. Returns false, having changed nothing, when
     * the first directory does not lie whole within the segment.
     */
    private fun markUpright(exif: ByteArray): Boolean {
        val tiff = 4 + EXIF_HEADER.size
        if (exif.size < tiff + TIFF_HEADER_SIZE) return false
        val littleEndian = exif[tiff] == 'I'.code.toByte()
        fun int16(at: Int) =
            if (littleEndian) exif.byteAt(at) or (exif.byteAt(at + 1) shl 8) else (exif.byteAt(at) shl 8) or exif.byteAt(at + 1)
        // Unsigned, as TIFF has it: an offset past 2 GB is not a negative one.
        fun uint32(at: Int): Long =
            if (littleEndian) int16(at).toLong() or (int16(at + 2).toLong() shl 16) else (int16(at).toLong() shl 16) or int16(at + 2).toLong()

        // Worked out in Long: the sums of a damaged block's offsets do not wrap around into the segment.
        val offset = uint32(tiff + 4)
        if (offset < TIFF_HEADER_SIZE) return false
        val directory = tiff + offset
        if (directory + 2 > exif.size) return false
        val end = directory + 2 + int16(directory.toInt()).toLong() * DIRECTORY_ENTRY_SIZE
        if (end + 4 > exif.size) return false
        for (entry in directory.toInt() + 2 until end.toInt() step DIRECTORY_ENTRY_SIZE) {
            if (int16(entry) == TAG_ORIENTATION) {
                // A 16-bit value, stored in the first two of the entry's four value bytes.
                exif[entry + 8] = (if (littleEndian) ORIENTATION_NORMAL else 0).toByte()
                exif[entry + 9] = (if (littleEndian) 0 else ORIENTATION_NORMAL).toByte()
            }
        }
        exif.fill(0, end.toInt(), end.toInt() + 4)
        return true
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
