package com.byeonggon.latent.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class MpfPreviewTest {
    private val small = jpeg(8, 6)
    private val large = jpeg(64, 48)

    private fun jpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            image.setRGB(x, y, ((x * 37 % 256) shl 16) or ((y * 71 % 256) shl 8) or ((x * y) % 256))
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    /** Real JPEGs with an MPF directory and primary/secondary entry offsets, relative to its TIFF header. */
    private fun mpf(vararg previews: Pair<Int, ByteArray>, little: Boolean = true): ByteArray {
        val count = 1 + previews.size
        val payloadSize = 4 + 26 + 16 * count
        val segment = ByteBuffer.allocate(4 + payloadSize).apply {
            put(0xff.toByte()); put(0xe2.toByte()); putShort((payloadSize + 2).toShort())
            put("MPF\u0000".toByteArray())
            order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            put(if (little) "II".toByteArray() else "MM".toByteArray()); putShort(42); putInt(8)
            putShort(1); putShort(0xb002.toShort()); putShort(7); putInt(count * 16); putInt(26); putInt(0)
            putInt(0x030000); putInt(small.size + capacity()); putInt(0); putInt(0)
            var offset = small.size + capacity() - TIFF_START
            for ((type, bytes) in previews) {
                putInt(type); putInt(bytes.size); putInt(offset); putInt(0)
                offset += bytes.size
            }
        }.array()
        return small.copyOfRange(0, 2) + segment + small.copyOfRange(2, small.size) + previews.flatMap { it.second.asIterable() }.toByteArray()
    }

    private fun preview(bytes: ByteArray): ByteArray? {
        val file = File.createTempFile("mpf-preview-test", ".jpg")
        return try { file.writeBytes(bytes); MpfPreview.of(file) } finally { file.delete() }
    }

    @Test
    fun readsTheLargestCameraPreviewInEitherByteOrder() {
        for (little in listOf(true, false)) {
            assertArrayEquals(large, preview(mpf(VGA to small, FULL_HD to large, little = little)))
        }
    }

    @Test
    fun doesNotUseAnHdrGainMapAsThePreview() {
        assertArrayEquals(small, preview(mpf(VGA to small, 0x030000 to large)))
        assertNull(preview(mpf(0x030000 to large)))
    }

    @Test
    fun aJpegWithoutMpfHasNoEmbeddedPreview() {
        assertNull(preview(small))
    }

    @Test
    fun unsignedDirectoryOffsetsCannotWrapIntoTheHeader() {
        for (offset in intArrayOf(-11, Int.MAX_VALUE)) {
            val bytes = mpf(VGA to small)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(TIFF_START + 4, offset)
            assertNull(preview(bytes))
        }
    }

    @Test
    fun aTruncatedTiffHeaderHasNoPreview() {
        val bytes = mpf(VGA to small).copyOf(TIFF_START + 4)
        // The APP2 ends after byte order and magic, without the first-directory offset.
        ByteBuffer.wrap(bytes).putShort(4, (bytes.size - 4).toShort())
        assertNull(preview(bytes))
    }

    @Test
    fun aOneBytePreviewCannotBeReadAsAJpegSignature() {
        assertNull(preview(mpf(VGA to byteArrayOf(0xff.toByte()))))
    }

    @Test
    fun anEntryTableOutsideItsApp2IsNotReadFromPictureData() {
        val bytes = mpf(VGA to small)
        val validEntry = bytes.copyOfRange(TIFF_START + 26 + 16, TIFF_START + 26 + 32)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(TIFF_START + 14, 16)
            putInt(TIFF_START + 18, bytes.size - TIFF_START)
        }
        assertNull(preview(bytes + validEntry))
    }

    @Test
    fun aHugeEntryCountDoesNotWalkOutsideTheDirectory() {
        val bytes = mpf(VGA to small)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(TIFF_START + 14, -1)
        assertNull(preview(bytes))
    }

    @Test
    fun aPreviewOutsideTheFileIsIgnored() {
        val bytes = mpf(VGA to small)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(TIFF_START + 26 + 16 + 8, -1)
        assertNull(preview(bytes))
    }

    @Test
    fun scanningStopsWhenTheAppSegmentIsTruncatedOrInvalid() {
        val bytes = mpf(VGA to small)
        assertNull(preview(bytes.copyOf(12)))
        ByteBuffer.wrap(bytes).putShort(4, 1)
        assertNull(preview(bytes))
    }

    private companion object {
        const val TIFF_START = 10
        const val VGA = 0x010001
        const val FULL_HD = 0x010002
    }
}
