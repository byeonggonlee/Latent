package com.byeonggon.latent.enhance

import com.byeonggon.latent.fuzz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Damaged JPEG headers, as a camera or another app might write them: moving the Exif block across ([JpegMetadata])
 * gives a JPEG or throws an [IOException], and looking for a gain map ([UltraHdr.hasGainMap]) says yes or no.
 */
class JpegHeaderFuzzTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun segment(marker: Int, payload: ByteArray) = bytes(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF) + payload

    private val startOfImage = bytes(0xFF, 0xD8)
    private val jfif = segment(0xE0, "JFIF".toByteArray() + bytes(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val quantization = segment(0xDB, ByteArray(65) { it.toByte() })
    private val pictureData = bytes(0xFF, 0xDA, 0x00, 0x08, 1, 1, 0, 0, 0x3F, 0, 0x12, 0x34, 0xFF, 0x00, 0x56, 0xFF, 0xD9)

    /** An Exif block whose first directory holds a make, an orientation and a link to a thumbnail directory. */
    private fun exifBlock(littleEndian: Boolean): ByteArray {
        fun int16(value: Int) = if (littleEndian) bytes(value and 0xFF, value shr 8) else bytes(value shr 8, value and 0xFF)
        fun int32(value: Int) = if (littleEndian) int16(value and 0xFFFF) + int16(value shr 16) else int16(value shr 16) + int16(value and 0xFFFF)
        val tiff = (if (littleEndian) "II" else "MM").toByteArray() + int16(42) + int32(8) +
            int16(2) +
            int16(0x010F) + int16(2) + int32(4) + "ab\u0000\u0000".toByteArray() +
            int16(0x0112) + int16(3) + int32(1) + int16(6) + int16(0) +
            int32(38) +
            int16(1) + int16(0x0201) + int16(4) + int32(1) + int32(0) + int32(0)
        return segment(0xE1, "Exif".toByteArray() + bytes(0, 0) + tiff)
    }

    private val gainMapXmp = segment(
        0xE1,
        "http://ns.adobe.com/xap/1.0/".toByteArray() + bytes(0) +
            """<x:xmpmeta><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/></x:xmpmeta>""".toByteArray(),
    )
    private val isoMark = segment(0xE2, "urn:iso:std:iso:ts:21496:-1".toByteArray() + bytes(0, 0, 0))

    private val camera = startOfImage + exifBlock(littleEndian = true) + quantization + pictureData
    private val cameraBigEndian = startOfImage + jfif + exifBlock(littleEndian = false) + quantization + pictureData
    private val edited = startOfImage + jfif + quantization + pictureData
    private val ultraHdr = startOfImage + exifBlock(littleEndian = true) + gainMapXmp + isoMark + quantization + pictureData

    @Test
    fun theSeedsAreReadAsTheyAre() {
        assertTrue(JpegMetadata.withExifOf(camera, edited, upright = true).size > edited.size)
        assertTrue(UltraHdr.hasGainMap(folder.newFile().apply { writeBytes(ultraHdr) }))
        assertFalse(UltraHdr.hasGainMap(folder.newFile().apply { writeBytes(camera) }))
    }

    @Test
    fun movingADamagedExifBlockGivesAJpegOrSaysItCannot() = fuzz(seed = 31, cases = 6_000) { mutator ->
        val original = mutator.bytes(if (mutator.random.nextBoolean()) camera else cameraBigEndian)
        val image = if (mutator.random.nextInt(4) == 0) mutator.bytes(edited) else edited
        val result = try {
            JpegMetadata.withExifOf(original, image, upright = mutator.random.nextBoolean())
        } catch (e: IOException) {
            return@fuzz
        }
        assertEquals(0xFF, result[0].toInt() and 0xFF)
        assertEquals(0xD8, result[1].toInt() and 0xFF)
    }

    @Test
    fun aDamagedHeaderHasAGainMapOrNot() {
        val file = folder.newFile()
        fuzz(seed = 32, cases = 6_000) { mutator ->
            file.writeBytes(mutator.bytes(if (mutator.random.nextBoolean()) ultraHdr else camera))
            UltraHdr.hasGainMap(file)
        }
    }
}
