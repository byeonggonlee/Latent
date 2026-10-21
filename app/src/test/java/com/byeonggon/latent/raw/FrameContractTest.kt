package com.byeonggon.latent.raw

import com.byeonggon.latent.enhance.Orientation
import com.byeonggon.latent.raw.api.DecodedFrame
import com.byeonggon.latent.raw.api.PixelLayout
import com.byeonggon.latent.raw.api.RawCorrection
import com.byeonggon.latent.raw.api.RawFormat
import com.byeonggon.latent.raw.api.U16Samples
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameContractTest {
    @Test
    fun unsignedStorageDoesNotConfuseHighBitsWithNegativeSamples() {
        val samples = U16Samples(shortArrayOf(0, 32767, 32768.toShort(), 65535.toShort()))
        assertEquals(listOf(0, 32767, 32768, 65535), (0 until samples.size).map { samples[it] })
    }

    @Test
    fun preservesMosaicSamplesAndTheStoredOrientation() {
        val bytes = SyntheticArw.file(300, 600, 400, orientation = 7)
        val legacy = SonyArw.read(bytes)
        val frame = SonyArw.decode(bytes)
        assertEquals(RawFormat.SONY_ARW, frame.format)
        assertEquals(11, frame.originalBitDepth)
        assertEquals(PixelLayout.RGGB_AT_CROP, frame.layout)
        assertEquals(legacy.width, frame.rowStrideSamples)
        assertEquals(7, frame.orientation)
        assertEquals(setOf(RawCorrection.SONY_TONE_EXPANSION), frame.corrections)
        assertArrayEquals(legacy.sensor, frame.legacyImage.sensor)
        val before = frame.legacyImage.sensor.copyOf()
        RawDeveloper.develop(frame.legacyImage)
        assertArrayEquals(before, frame.legacyImage.sensor)
    }

    @Test
    fun unknownArwOrientationsKeepTheUprightFallback() {
        for (orientation in listOf(0, 9, 65535)) {
            val image = SonyArw.read(SyntheticArw.file(300, 600, 400, orientation = orientation))
            assertEquals(1, image.orientation)
            assertEquals(Orientation.UPRIGHT, Orientation.ofExif(image.orientation))
            assertEquals(600, image.sensor[0].toInt())
        }
    }

    @Test
    fun unknownDngOrientationsKeepTheUprightFallback() {
        val expected = Dng.read(SyntheticDng.file(200, 400, 300)).sensor
        for (orientation in listOf(0, 9, 65535)) {
            val image = Dng.read(SyntheticDng.file(200, 400, 300, orientation = orientation))
            assertEquals(1, image.orientation)
            assertEquals(Orientation.UPRIGHT, Orientation.ofExif(image.orientation))
            assertArrayEquals(expected, image.sensor)
        }
    }

    @Test
    fun retainsFiniteArwWhiteBalanceGainsAcceptedByTheLegacyReader() {
        for (gains in listOf(intArrayOf(0, 1024, 1024, 0), intArrayOf(-1024, 1024, 1024, -512))) {
            val image = SonyArw.read(SyntheticArw.file(300, 600, 400, whiteBalance = gains))
            assertArrayEquals(floatArrayOf(gains[0] / 1024f, 1f, gains[3] / 1024f), image.whiteBalance, 0f)
            assertEquals(600, image.sensor[0].toInt())
        }
    }

    @Test
    fun identifiesNormalizedCameraRgbWithoutCallingItSrgb() {
        val bytes = SyntheticDng.linear(192, 380, 264)
        val frame = Dng.decode(bytes)
        assertEquals(RawFormat.DNG, frame.format)
        assertEquals(16, frame.originalBitDepth)
        assertEquals(PixelLayout.CAMERA_RGB_INTERLEAVED, frame.layout)
        assertEquals(SyntheticDng.WIDTH * 3, frame.rowStrideSamples)
        assertEquals(16383, frame.whiteLevel)
        assertEquals(setOf(RawCorrection.DNG_LEVELS), frame.corrections)
        assertArrayEquals(Dng.read(bytes).sensor, frame.legacyImage.sensor)
    }

    @Test
    fun reportsOnlyCorrectionsThatWereApplied() {
        val brightened = Dng.decode(SyntheticDng.linear(192, 380, 264, baselineExposure = 1f))
        assertTrue(RawCorrection.POSITIVE_BASELINE_EXPOSURE in brightened.corrections)
        val map = SyntheticDng.GainMap(2, 2, floatArrayOf(1f, 1f, 2f, 2f))
        val corrected = Dng.decode(SyntheticDng.file(100, 100, 100, gainMap = map))
        assertTrue(RawCorrection.GAIN_MAP in corrected.corrections)
        val table = IntArray(2048) { it / 2 }.also { it[it.lastIndex] = SyntheticDng.WHITE }
        val linearized = Dng.decode(SyntheticDng.file(200, 400, 300, linearization = table))
        assertTrue(RawCorrection.LINEARIZATION in linearized.corrections)
    }

    @Test
    fun preservesAnOpaqueSourceProfileWithoutExposingMutableMetadata() {
        val stream = javaClass.getResourceAsStream("/raw/groups.jxl")!!.use { it.readBytes() }
        val profile = byteArrayOf(1, 2, 3, 4, 5)
        val frame = Dng.decode(SyntheticDng.jpegXl(stream, iccProfile = profile))
        assertArrayEquals(profile, frame.iccProfile())
        frame.iccProfile()!![0] = 99
        assertArrayEquals(profile, frame.iccProfile())
        assertEquals(PixelLayout.CAMERA_RGB_INTERLEAVED, frame.layout)
    }

    @Test
    fun aViewRetainsItsSamplesAfterTheCacheIsCleared() {
        val cache = RawCache { SonyArw.read(SyntheticArw.file(300, 600, 400)) }
        val image = cache.read(java.io.File("view.ARW"))
        val part = image.part(0, 0, 8, 4)
        val frame = DecodedFrame(part, RawFormat.SONY_ARW, 11, setOf(RawCorrection.SONY_TONE_EXPANSION))
        cache.clear()
        assertSame(image.sensor, frame.legacyImage.sensor)
        assertEquals(600, frame.samples[0])
        assertEquals(8, frame.cropWidth)
        assertEquals(image.width * image.height, frame.samples.size)
    }

    @Test
    fun rejectsShapesAndRangesOutsideTheLegacyContract() {
        val image = SonyArw.read(SyntheticArw.file(300, 600, 400))
        val invalidCrop = image.part(0, 0, image.width + 2, 4)
        assertThrows(IllegalArgumentException::class.java) { DecodedFrame(invalidCrop, RawFormat.SONY_ARW, 11, emptySet()) }
        image.sensor[0] = 65535.toShort()
        assertThrows(IllegalArgumentException::class.java) { DecodedFrame(image, RawFormat.SONY_ARW, 11, emptySet()) }
    }
}
