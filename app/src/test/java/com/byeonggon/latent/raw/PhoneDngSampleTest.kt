package com.byeonggon.latent.raw

import com.byeonggon.latent.raw.api.RawCorrection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** External photos stay out of Git. Sources, hashes and independent sensor probes are in the report. */
class PhoneDngSampleTest {
    @Test
    fun readsOpenCameraPixel2XlWithItsFourGainMapsAndMaskedBorder() = checkSample(
        "opencamera-2206.dng", "5541093a369f0487fef92bcd1f5fef1be6e4e3cec2aa4d52ad38d2211f3309d7",
        4032, 3032, listOf(9, 17, 4016, 3008), floatArrayOf(1.8550725f, 1f, 1.7777778f), true,
        listOf(Triple(8, 16, 199), Triple(9, 16, 393), Triple(8, 17, 287), Triple(9, 17, 72),
            Triple(2016, 1520, 2782), Triple(2017, 1520, 6102), Triple(2016, 1521, 4963), Triple(2017, 1521, 3018),
            Triple(4028, 3028, 1306), Triple(4029, 3028, 2107), Triple(4028, 3029, 2673), Triple(4029, 3029, 1042)),
    )

    @Test
    fun readsOpenCameraHonor6AWithANonUnitGreenNeutral() = checkSample(
        "opencamera-1877.dng", "85b790b0eb3f0ccb87da8b0d3d0c69acdbd959592306ec0698817da14c9233eb",
        4208, 3120, listOf(9, 8, 4192, 3104), floatArrayOf(1.8695652f, 1f, 1.7916667f), true,
        listOf(Triple(8, 8, 7483), Triple(9, 8, 3422), Triple(8, 9, 4301), Triple(9, 9, 7366),
            Triple(2104, 1560, 3909), Triple(2105, 1560, 2618), Triple(2104, 1561, 1395), Triple(2105, 1561, 4949),
            Triple(4204, 3116, 4040), Triple(4205, 3116, 1993), Triple(4204, 3117, 1653), Triple(4205, 3117, 3767)),
    )

    @Test
    fun readsMotionCamTools10BitFrameWithItsScaledBlackAndWhite() = checkSample(
        "motioncam-0.dng", "737e95b7db07e5ac91c66f3465ae7b450a224b2d5b93954142a84292882aa84c",
        4080, 2288, listOf(0, 1, 4080, 2286), floatArrayOf(2.0940695f, 1f, 1.4905386f), false,
        listOf(Triple(8, 8, 2251), Triple(9, 8, 1482), Triple(8, 9, 912), Triple(9, 9, 1957),
            Triple(2040, 1144, 18), Triple(2041, 1144, 9), Triple(2040, 1145, 14), Triple(2041, 1145, 23),
            Triple(4076, 2284, 41), Triple(4077, 2284, 27), Triple(4076, 2285, 28), Triple(4077, 2285, 41)),
    )

    @Test
    fun readsMotionCamTools12BitDcgFrameWithoutGuessingItsWhiteFromTheCaptureBitDepth() = checkSample(
        "motioncam-1.dng", "562e682ca0c307239dce63bf033835f7f8927fc7a6333c827f0f79228170c6d8",
        4080, 2288, listOf(0, 1, 4080, 2286), floatArrayOf(2.1200829f, 1f, 1.4712644f), false,
        listOf(Triple(8, 8, 1940), Triple(9, 8, 1381), Triple(8, 9, 1151), Triple(9, 9, 1731),
            Triple(2040, 1144, 8), Triple(2041, 1144, 6), Triple(2040, 1145, 4), Triple(2041, 1145, 7),
            Triple(4076, 2284, 31), Triple(4077, 2284, 28), Triple(4076, 2285, 11), Triple(4077, 2285, 31)),
    )

    private fun checkSample(
        name: String, sha256: String, width: Int, height: Int, crop: List<Int>, wb: FloatArray,
        gainMaps: Boolean, probes: List<Triple<Int, Int, Int>>,
    ) {
        val directory = System.getenv("PHONE_DNG_SAMPLE_DIR")
        assumeTrue("Set PHONE_DNG_SAMPLE_DIR to run public-sample checks", directory != null)
        val file = File(directory!!, name)
        assertTrue("Missing $file", file.isFile)
        val bytes = file.readBytes()
        assertEquals(sha256, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val (data, first) = TiffDirectory.open(bytes)
        val raw = TiffDirectory.read(data, first)
        // These Open Camera files explicitly store an empty standard OpcodeList3; MotionCam omits it.
        if (gainMaps) assertArrayEquals(byteArrayOf(0, 0, 0, 0), raw.bytes(51022))
        else assertFalse(51022 in raw)
        assertFalse(0xC742 in raw)
        val frame = Dng.decode(bytes)
        val image = frame.legacyImage
        assertEquals(width to height, image.width to image.height)
        assertEquals(1, image.channels)
        assertEquals(1, image.orientation)
        assertEquals(crop, listOf(image.cropLeft, image.cropTop, image.cropWidth, image.cropHeight))
        assertEquals(0, image.blackLevel)
        assertEquals(16383, image.whiteLevel)
        assertArrayEquals(wb, image.whiteBalance, 0.00001f)
        assertEquals(gainMaps, RawCorrection.GAIN_MAP in frame.corrections)
        assertFalse(RawCorrection.POSITIVE_BASELINE_EXPOSURE in frame.corrections)
        assertEquals(null, image.dngWarp)
        // Independent TIFF/IEEE-754 decoding and scalar bilinear interpolation, not Dng.read output.
        // One count accommodates Float interpolation/rounding versus the double-precision reference.
        for ((x, y, expected) in probes) {
            val actual = image.sensor[y * width + x].toInt()
            assertTrue("$name ($x,$y): expected $expected, got $actual", abs(expected - actual) <= 1)
        }
        val preview = RawDeveloper.developPreview(image)
        assertEquals(crop[2] / 2 to crop[3] / 2, preview.width to preview.height)
        assertTrue(preview.green.any { it > 0 })
    }
}
