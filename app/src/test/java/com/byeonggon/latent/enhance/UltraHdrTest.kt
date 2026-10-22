package com.byeonggon.latent.enhance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Telling an Ultra HDR JPEG by its header: Adobe's gain map XMP, or ISO 21496-1's mark. */
class UltraHdrTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun segment(marker: Int, payload: ByteArray) = bytes(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF) + payload

    private val startOfImage = bytes(0xFF, 0xD8)
    private val exif = segment(0xE1, "Exif".toByteArray() + bytes(0, 0) + "MM".toByteArray())
    private val iccProfile = segment(0xE2, "ICC_PROFILE".toByteArray() + bytes(0, 1, 1))
    private val pictureData = bytes(0xFF, 0xDA, 0x00, 0x02, 0x12, 0x34, 0xFF, 0xD9)

    private fun xmp(body: String) = segment(0xE1, "http://ns.adobe.com/xap/1.0/".toByteArray() + bytes(0) + body.toByteArray())

    private fun file(vararg parts: ByteArray) = folder.newFile().apply { writeBytes(parts.reduce(ByteArray::plus)) }

    @Test
    fun knowsAnUltraHdrPictureByItsGainMapXmp() {
        val gainMap = xmp("""<x:xmpmeta><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/></x:xmpmeta>""")

        assertTrue(UltraHdr.hasGainMap(file(startOfImage, exif, gainMap, pictureData)))
    }

    @Test
    fun knowsAnIsoGainMapPictureByItsMark() {
        val iso = segment(0xE2, "urn:iso:std:iso:ts:21496:-1".toByteArray() + bytes(0, 0, 0))

        assertTrue(UltraHdr.hasGainMap(file(startOfImage, exif, iccProfile, iso, pictureData)))
    }

    @Test
    fun readsPastFillBytesBeforeAMarker() {
        val gainMap = xmp("""xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"""")

        assertTrue(UltraHdr.hasGainMap(file(startOfImage, bytes(0xFF, 0xFF), exif, gainMap, pictureData)))
    }

    @Test
    fun findsNoGainMapInAnOrdinaryJpeg() {
        assertFalse(UltraHdr.hasGainMap(file(startOfImage, exif, iccProfile, xmp("<x:xmpmeta/>"), pictureData)))
    }

    @Test
    fun looksNoFurtherThanThePictureData() {
        // A gain map's XMP belongs before the picture; the same words in the compressed data, or in a second picture after
        // it, are not this picture's.
        val after = xmp("""xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"""")

        assertFalse(UltraHdr.hasGainMap(file(startOfImage, exif, pictureData, after)))
    }

    @Test
    fun findsNoGainMapInWhatIsNoJpegOrIsCutShort() {
        assertFalse(UltraHdr.hasGainMap(file("not a picture".toByteArray())))
        assertFalse(UltraHdr.hasGainMap(file(startOfImage, exif.copyOfRange(0, 6))))
        assertFalse(UltraHdr.hasGainMap(file(startOfImage, bytes(0xFF, 0xE1, 0x00, 0x01))))
        assertFalse(UltraHdr.hasGainMap(folder.root.resolve("missing.jpg")))
    }
}
