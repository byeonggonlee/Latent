package com.byeonggon.cameramanager.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class JpegMetadataTest {
    private val startOfImage = bytes(0xFF, 0xD8)
    private val pictureData = bytes(0xFF, 0xDA, 0x00, 0x02, 0x12, 0x34, 0x56, 0xFF, 0xD9)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun segment(marker: Int, payload: ByteArray) =
        bytes(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF) + payload

    private fun exif(content: String) = segment(0xE1, "Exif".toByteArray() + bytes(0, 0) + content.toByteArray())

    private val jfif = segment(0xE0, "JFIF".toByteArray() + bytes(0, 1, 2))
    private val quantization = segment(0xDB, bytes(1, 2, 3, 4))

    @Test
    fun putsTheOriginalsExifBlockAtTheFrontOfTheImage() {
        val original = startOfImage + exif("camera settings") + segment(0xE2, "MPF".toByteArray()) + pictureData
        val image = startOfImage + jfif + quantization + pictureData

        val result = JpegMetadata.withExifOf(original, image)

        assertArrayEquals(startOfImage + exif("camera settings") + quantization + pictureData, result)
    }

    @Test
    fun replacesExifTheImageAlreadyHad() {
        val original = startOfImage + exif("from the camera") + pictureData
        val image = startOfImage + exif("from the encoder") + quantization + pictureData

        val result = JpegMetadata.withExifOf(original, image)

        assertArrayEquals(startOfImage + exif("from the camera") + quantization + pictureData, result)
    }

    @Test
    fun keepsOtherApplicationDataOfTheImage() {
        val colourProfile = segment(0xE2, "ICC_PROFILE".toByteArray())
        val adobeXmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/".toByteArray())
        val original = startOfImage + exif("camera settings") + pictureData
        val image = startOfImage + jfif + adobeXmp + colourProfile + quantization + pictureData

        val result = JpegMetadata.withExifOf(original, image)

        assertArrayEquals(
            startOfImage + exif("camera settings") + adobeXmp + colourProfile + quantization + pictureData,
            result,
        )
    }

    @Test
    fun onlyDropsTheImagesOwnHeaderWhenTheOriginalHasNoExif() {
        val original = startOfImage + quantization + pictureData
        val image = startOfImage + jfif + quantization + pictureData

        assertEquals((startOfImage + quantization + pictureData).toList(), JpegMetadata.withExifOf(original, image).toList())
    }

    @Test
    fun rejectsDataThatIsNotAJpeg() {
        val image = startOfImage + quantization + pictureData

        assertThrows(IOException::class.java) { JpegMetadata.withExifOf("PNG".toByteArray(), image) }
        assertThrows(IOException::class.java) { JpegMetadata.withExifOf(image, "PNG".toByteArray()) }
        assertThrows(IOException::class.java) {
            JpegMetadata.withExifOf(image, startOfImage + bytes(0xFF, 0xE1, 0x40, 0x00, 1, 2, 3))
        }
    }
}
