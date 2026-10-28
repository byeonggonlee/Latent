package com.byeonggon.latent.raw

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
    private fun validFile(): ByteArray {
        val file=CanonCr2MetadataTest().fixture()
        val flat = IntArray(96) { i ->
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
        val expected = ShortArray(96) { i ->
            if (i / 12 % 2 == 0) { if (i % 2 == 0) 4000 else 3000 }
            else { if (i % 2 == 0) 5000 else 4000 }
        }
        assertArrayEquals(expected,frame.legacyImage.sensor)
        val preview=RawDeveloper.developPreview(frame.legacyImage)
        assertTrue(preview.red.first() > preview.green.first())
        assertTrue(preview.blue.first() < preview.green.first())
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
