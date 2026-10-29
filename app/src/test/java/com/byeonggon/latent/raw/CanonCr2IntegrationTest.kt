package com.byeonggon.latent.raw

import com.byeonggon.latent.enhance.LinearImage

import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.raw.api.DecodeFailure
import com.byeonggon.latent.raw.api.DecodeFailureReason
import com.byeonggon.latent.raw.api.DecodeRequest
import com.byeonggon.latent.raw.api.PixelLayout
import com.byeonggon.latent.raw.api.RawFormat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CanonCr2IntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun validFile(clippedAt: Int? = null, whiteLevel: Int = 12279): ByteArray {
        val file=CanonCr2MetadataTest().fixture()
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putShort(1100 + 2 * 0x2d0, whiteLevel.toShort())
        val flat = IntArray(96) { i ->
            if (clippedAt != null) return@IntArray clippedAt
            val inSlice = i % 32
            if (inSlice / 4 % 2 == 0) { if (inSlice % 2 == 0) 4000 else 3000 }
            else { if (inSlice % 2 == 0) 5000 else 4000 }
        }
        val rows = Array(8) { y -> IntArray(12) { x -> flat[y * 12 + x] } }
        val stream=LosslessJpegEncoder.encode(rows,components=2,precision=14,predictor=1)
        stream.copyInto(file,6000)
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putInt(4000+2+2*12+8,stream.size)
        return file
    }

    @Test fun identifiesAndDevelopsACanonBayerRawWithItsOwnColourLayout() {
        val file=temp.newFile("canon.CR2").apply { writeBytes(validFile()) }
        assertEquals(MediaKind.RAW,MediaKind.fromFileName(file.name))
        val frame=DecoderDispatch.decode(DecodeRequest(file))
        assertEquals(RawFormat.CANON_CR2,frame.format)
        assertEquals(PixelLayout.GBRG_AT_CROP,frame.layout)
        assertEquals(6,frame.orientation)
        assertArrayEquals(floatArrayOf(2f,1f,1.5f),frame.legacyImage.whiteBalance,0.0001f)
        assertEquals(1.24f,frame.legacyImage.toneExponent,0f)
        val expected = ShortArray(96) { i ->
            if (i / 12 % 2 == 0) { if (i % 2 == 0) 4000 else 3000 }
            else { if (i % 2 == 0) 5000 else 4000 }
        }
        assertArrayEquals(expected,frame.legacyImage.sensor)
        val preview=RawDeveloper.developPreview(frame.legacyImage)
        assertTrue(preview.red.first() > preview.green.first())
        assertTrue(preview.blue.first() < preview.green.first())
    }

    private fun calibration(red: Int, blue: Int): FloatArray {
        val bytes = validFile()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.putShort(1100 + 2 * 63, red.toShort())
        data.putShort(1100 + 2 * 66, blue.toShort())
        val file = temp.newFile().apply { writeBytes(bytes) }
        val image = DecoderDispatch.decode(DecodeRequest(file)).legacyImage
        assertArrayEquals(floatArrayOf(red / 1024f, 1f, blue / 1024f), image.whiteBalance, 0.000001f)
        return image.colorMatrix
    }

    @Test fun clampsWarmerWhiteBalanceToTheAdobeStandardACalibration() {
        // Independent NumPy calculation: invert row-normalized (Adobe ColorMatrix1 * sRGB-to-XYZ).
        // Gains are slightly warmer than Standard A, so interpolation must clamp at that endpoint.
        val expected = floatArrayOf(1.3483000f, 0.3803899f, -0.7286898f,
            -0.2262151f, 1.7218883f, -0.4956733f, -0.0169115f, -0.3936337f, 1.4105452f)
        assertArrayEquals(expected, calibration(1300, 2600), 0.003f)
    }

    @Test fun clampsCoolerWhiteBalanceToTheAdobeD65Calibration() {
        // Same independent calculation with ColorMatrix2 and D65; gains are slightly cooler than D65.
        val expected = floatArrayOf(1.9203930f, -1.0413070f, 0.1209140f,
            -0.2469543f, 1.7593473f, -0.5123931f, -0.0069546f, -0.4895519f, 1.4965065f)
        assertArrayEquals(expected, calibration(2420, 1360), 0.003f)
    }

    @Test fun usesTheShotsWhiteLevelInsteadOfTheBitDepthMaximum() {
        for (white in intArrayOf(11222, 12279)) {
            val file = temp.newFile("white-$white.CR2").apply { writeBytes(validFile(whiteLevel = white)) }
            val image = DecoderDispatch.decode(DecodeRequest(file)).legacyImage
            assertEquals(white, image.whiteLevel)
        }
    }

    @Test fun fullySaturatedPhotositesDevelopNeutralInsteadOfMagenta() {
        val file = temp.newFile("clipped.CR2").apply { writeBytes(validFile(clippedAt = 12648, whiteLevel = 11222)) }
        val image = DecoderDispatch.decode(DecodeRequest(file)).legacyImage
        for (developed in listOf(RawDeveloper.developPreview(image), RawDeveloper.develop(image))) {
            for (channel in listOf(developed.red, developed.green, developed.blue)) {
                assertTrue("Every clipped channel must be white", channel.all { it.toInt() == LinearImage.MAX_LEVEL })
            }
        }
    }

    @Test fun rejectsMissingOrInvalidWhiteLevelsInsteadOfFallingBackTo16383() {
        for (white in intArrayOf(0, 2048, 2049, 2063, 16384, 65535)) {
            val file = temp.newFile("invalid-white-$white.CR2").apply { writeBytes(validFile(whiteLevel = white)) }
            assertThrows(DecodeFailure::class.java) { DecoderDispatch.decode(DecodeRequest(file)) }
        }
    }

    @Test fun anUncalibratedCanonModelIsRejectedAsUnsupported() {
        val bytes=validFile()
        "Canon EOS 5D\u0000".toByteArray().copyInto(bytes,410)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(1100, 8)
        val file=temp.newFile("other.CR2").apply { writeBytes(bytes) }
        val error=assertThrows(DecodeFailure::class.java) { DecoderDispatch.decode(DecodeRequest(file)) }
        assertEquals(DecodeFailureReason.UNSUPPORTED,error.reason)
    }

    @Test fun enforcesTheDecodeRequestMemoryLimitBeforeAllocating() {
        val file=temp.newFile("bounded.CR2").apply { writeBytes(validFile()) }
        val error=assertThrows(DecodeFailure::class.java) { DecoderDispatch.decode(DecodeRequest(file,maxDecodedBytes=10)) }
        assertEquals(DecodeFailureReason.RESOURCE_LIMIT,error.reason)
    }
}
