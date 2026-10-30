package com.byeonggon.latent.raw

import com.byeonggon.latent.raw.api.DecodeRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/** Optional external files: the CC0 CR2s and their uncompressed Adobe DNG conversions stay out of Git. */
class CanonCr2ReferenceTest {
    @Test fun sceneSensorAndWhiteBalanceAgreeWithAdobeDng() = compareDngSensorAndSharedDevelopment("CANON_7D_SAMPLE", "CANON_7D_DNG")

    @Test fun chartSensorAndWhiteBalanceAgreeWithAdobeDng() = compareDngSensorAndSharedDevelopment("CANON_7D_CHART_SAMPLE", "CANON_7D_CHART_DNG")

    @Test fun sceneAgreesWithIndependentLinearDevelopment() = compareIndependent("CANON_7D_SAMPLE", "CANON_7D_LIBRAW_RGB")

    @Test fun chartAgreesWithIndependentLinearDevelopment() = compareIndependent("CANON_7D_CHART_SAMPLE", "CANON_7D_CHART_LIBRAW_RGB")

    @Test fun sceneUsesTheAdobeDngProfileWithIdentityCameraCalibration() = compareProfile("CANON_7D_SAMPLE", "CANON_7D_DNG")

    @Test fun chartUsesTheAdobeDngProfileWithIdentityCameraCalibration() = compareProfile("CANON_7D_CHART_SAMPLE", "CANON_7D_CHART_DNG")

    private fun compareProfile(cr2Variable: String, dngVariable: String) {
        val cr2Path = System.getenv(cr2Variable)
        val dngPath = System.getenv(dngVariable)
        assumeTrue("Set $cr2Variable and $dngVariable to the external reference pair",
            !cr2Path.isNullOrBlank() && !dngPath.isNullOrBlank())
        val image = DecoderDispatch.decode(DecodeRequest(File(cr2Path!!))).legacyImage
        val (data, first) = TiffDirectory.open(File(dngPath!!).readBytes())
        val main = TiffDirectory.read(data, first)
        // Production CR2 does not have Adobe's per-unit calibration. Match the available profile
        // inputs explicitly, rather than silently overriding the CR2 matrix in the comparison.
        val expected = Dng.Colors(
            { tag -> if (tag == 0xc623 || tag == 0xc624) null else main.floats(tag) },
            main::int,
        )
        assertArrayEquals(expected.whiteBalance, image.whiteBalance, 0.00002f)
        assertArrayEquals(expected.matrix, image.colorMatrix, 0.0001f)
        println("$cr2Variable production Adobe matrix=${image.colorMatrix.contentToString()}")
    }

    private fun compareIndependent(cr2Variable: String, referenceVariable: String) {
        val cr2Path = System.getenv(cr2Variable)
        val referencePath = System.getenv(referenceVariable)
        assumeTrue("Set $cr2Variable and $referenceVariable to an external pair made by make_cr2_reference.py",
            !cr2Path.isNullOrBlank() && !referencePath.isNullOrBlank())
        val file = File(cr2Path!!)
        val image = DecoderDispatch.decode(DecodeRequest(file)).legacyImage
        val reference = ByteBuffer.wrap(File(referencePath!!).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8).also(reference::get)
        assertEquals("LCR2REF1", String(magic, Charsets.US_ASCII))
        val sourceHash = ByteArray(32).also(reference::get)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(file.readBytes()), sourceHash)
        val width = reference.int
        val height = reference.int
        assertEquals(image.blackLevel, reference.int)
        assertEquals(image.whiteLevel, reference.int)
        assertArrayEquals(image.whiteBalance, FloatArray(3) { reference.float }, 0.000001f)
        assertArrayEquals(image.colorMatrix, FloatArray(9) { reference.float }, 0.000001f)
        // The oracle is linear light: develop without the 7D's darker brightness, which RawDeveloperTest checks.
        val linear = ArwImage(image.width, image.height, image.cropLeft, image.cropTop, image.cropWidth, image.cropHeight,
            image.blackLevel, image.whiteLevel, image.whiteBalance, image.colorMatrix, image.saturation, image.lens,
            image.orientation, image.sensor, cfaPattern = image.cfaPattern)
        val developed = RawDeveloper.developPreview(linear)
        assertEquals(developed.width, width)
        assertEquals(developed.height, height)
        assertEquals(width * height * 6, reference.remaining())
        var largestDifference = 0
        var totalDifference = 0L
        val channels = arrayOf(developed.red, developed.green, developed.blue)
        for (i in developed.red.indices) {
            for (channel in channels) {
                val difference = abs(channel[i].toInt() - reference.short.toInt())
                largestDifference = maxOf(largestDifference, difference)
                totalDifference += difference
            }
        }
        val meanDifference = totalDifference.toDouble() / (width * height * 3)
        println("$cr2Variable vs independent LibRaw: linear RGB max=$largestDifference, mean=$meanDifference")
        // All pixels, including neutrals and saturated highlights: 8 counts is <0.05% of full scale.
        assertTrue("Independent linear RGB differs by $largestDifference counts", largestDifference <= 8)
        assertTrue("Independent mean linear RGB difference $meanDifference", meanDifference <= 2)
    }

    private fun compareDngSensorAndSharedDevelopment(cr2Variable: String, dngVariable: String) {
        val cr2Path = System.getenv(cr2Variable)
        val dngPath = System.getenv(dngVariable)
        assumeTrue("Set $cr2Variable and $dngVariable to the external reference pair",
            !cr2Path.isNullOrBlank() && !dngPath.isNullOrBlank())
        val cr2 = DecoderDispatch.decode(DecodeRequest(File(cr2Path!!))).legacyImage
        val dngFile = File(dngPath!!)
        val bytes = dngFile.readBytes()
        val dng = DecoderDispatch.decode(DecodeRequest(dngFile)).legacyImage
        val (data, first) = TiffDirectory.open(bytes)
        val main = TiffDirectory.read(data, first)
        val raw = TiffDirectory.read(data, main.int(0x14a)!!)
        val blackLevels = raw.floats(0xc61a)!!
        assertTrue("Reference must have a uniform black level", blackLevels.all { it == blackLevels[0] })
        val black = blackLevels[0].roundToInt()
        val white = raw.int(0xc61d)!!
        assertEquals("Adobe independently declares the same black level", cr2.blackLevel, black)
        assertEquals(cr2.width, dng.width)
        assertEquals(cr2.height, dng.height)
        assertArrayEquals(cr2.whiteBalance, dng.whiteBalance, 0.00002f)
        var largestSensorDifference = 0
        for (y in dng.cropTop until dng.cropTop + dng.cropHeight) {
            for (x in dng.cropLeft until dng.cropLeft + dng.cropWidth) {
                val i = y * dng.width + x
                val expected = ((cr2.sensor[i].toInt() - black).toDouble() * dng.whiteLevel / (white - black))
                    .roundToInt().coerceIn(0, Short.MAX_VALUE.toInt())
                largestSensorDifference = maxOf(largestSensorDifference, abs(expected - dng.sensor[i].toInt()))
            }
        }
        assertTrue("Sensor normalization differs by $largestSensorDifference counts", largestSensorDifference <= 1)
        // Match crop phase, WB, matrix and exposure scale to isolate decoding from rendering choices.
        // The production CR2 still uses its reviewed conservative white cutoff and its own crop.
        val controlled = ArwImage(cr2.width, cr2.height, dng.cropLeft, dng.cropTop, dng.cropWidth, dng.cropHeight,
            black, white, dng.whiteBalance, dng.colorMatrix, 1f, null, cr2.orientation, cr2.sensor,
            cfaPattern = CfaPattern.RGGB)
        // Both images use Latent here: independent evidence is the DNG sensor/WB/black metadata.
        // LibRaw, tested above, supplies the independent development oracle.
        val ours = RawDeveloper.developPreview(controlled)
        val reference = RawDeveloper.developPreview(dng)
        var largestDifference = 0
        var totalDifference = 0L
        val pairs = listOf(ours.red to reference.red, ours.green to reference.green, ours.blue to reference.blue)
        for ((a, b) in pairs) for (i in a.indices) {
            val difference = abs(a[i].toInt() - b[i].toInt())
            largestDifference = maxOf(largestDifference, difference)
            totalDifference += difference
        }
        val meanDifference = totalDifference.toDouble() / (ours.width * ours.height * 3)
        println("$cr2Variable: WB=${dng.whiteBalance.contentToString()}, matrix=${dng.colorMatrix.contentToString()}, " +
            "black=$black, white=$white, crop=${dng.cropLeft},${dng.cropTop}; " +
            "sensor max difference=$largestSensorDifference, shared RawDeveloper RGB max=$largestDifference, mean=$meanDifference")
        // Quantization occurs before WB in DNG and after WB in CR2; allow its small, bounded effect.
        assertTrue("Linear RGB differs by $largestDifference counts", largestDifference <= 16)
        assertTrue("Mean linear RGB difference $meanDifference", meanDifference <= 2)
    }
}
