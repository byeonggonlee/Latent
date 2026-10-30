package com.byeonggon.latent.raw

import com.byeonggon.latent.raw.api.DecodeFailure
import com.byeonggon.latent.raw.api.DecodeFailureReason
import com.byeonggon.latent.raw.api.DecodeRequest
import com.byeonggon.latent.raw.api.RawFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DecoderDispatchTest {
    @get:Rule val files = TemporaryFolder(File("build/decoder-test-files").apply { mkdirs() })

    private fun file(bytes: ByteArray, name: String = "picture.bin"): File = files.newFile(name).also { it.writeBytes(bytes) }

    @Test
    fun selectsByHeaderEvenWhenTheExtensionSaysSomethingElse() {
        val dng = DecoderDispatch.decode(DecodeRequest(file(SyntheticDng.file(200, 400, 300), "sony.ARW")))
        val arw = DecoderDispatch.decode(DecodeRequest(file(SyntheticArw.file(300, 600, 400), "phone.DNG")))
        assertEquals(RawFormat.DNG, dng.format)
        assertEquals(RawFormat.SONY_ARW, arw.format)
        assertEquals(600, arw.samples[0])
    }

    @Test
    fun distinguishesAnUnknownFormatFromADamagedTiff() {
        assertEquals(DecodeFailureReason.UNSUPPORTED, failure(file("not a raw file".toByteArray())).reason)
        assertEquals(DecodeFailureReason.CORRUPT, failure(file(byteArrayOf(73, 73, 42), "short.dng")).reason)
    }

    @Test
    fun refusesUnsupportedCompressionAndThenReadsANormalDng() {
        val unsupported = file(SyntheticDng.file(200, 400, 300, compression = 99))
        assertEquals(DecodeFailureReason.UNSUPPORTED, failure(unsupported).reason)
        val normal = DecoderDispatch.decode(DecodeRequest(file(SyntheticDng.file(200, 400, 300), "normal.dng")))
        assertEquals(16383, normal.whiteLevel)
    }

    @Test
    fun refusesAnUnsupportedDngMajorVersion() {
        val bytes = SyntheticDng.file(200, 400, 300)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val at = (0 until (data.getShort(8).toInt() and 0xFFFF)).map { 10 + 12 * it }
            .first { (data.getShort(it).toInt() and 0xFFFF) == 0xC612 }
        data.put(at + 8, 2)
        assertEquals(DecodeFailureReason.UNSUPPORTED, failure(file(bytes)).reason)
    }

    @Test
    fun reportsTheExistingDngDimensionLimitAsAResourceFailure() {
        val bytes = SyntheticDng.file(200, 400, 300)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val at = (0 until (data.getShort(8).toInt() and 0xFFFF)).map { 10 + 12 * it }
            .first { (data.getShort(it).toInt() and 0xFFFF) == 0x0100 }
        data.putShort(at + 2, 4); data.putInt(at + 8, Int.MAX_VALUE)
        assertEquals(DecodeFailureReason.RESOURCE_LIMIT, failure(file(bytes)).reason)
    }

    @Test
    fun appliesAnExplicitInputBudgetBeforeReadingTheFile() {
        val source = file(SyntheticDng.file(200, 400, 300))
        val error = assertThrows(DecodeFailure::class.java) { DecoderDispatch.decode(DecodeRequest(source, maxInputBytes = 8)) }
        assertEquals(DecodeFailureReason.RESOURCE_LIMIT, error.reason)
    }

    @Test
    fun appliesAnExplicitSampleBudgetToBothReaders() {
        for ((name, bytes) in listOf("sony.arw" to SyntheticArw.file(300, 600, 400), "phone.dng" to SyntheticDng.linear(192, 380, 264))) {
            val source = file(bytes, name)
            val error = assertThrows(DecodeFailure::class.java) { DecoderDispatch.decode(DecodeRequest(source, maxDecodedBytes = 100)) }
            assertEquals(DecodeFailureReason.RESOURCE_LIMIT, error.reason)
            assertTrue(DecoderDispatch.decode(DecodeRequest(source)).samples.size > 0)
        }
    }

    @Test
    fun distinguishesIoErrorsAndPreservesTheirCause() {
        val error = failure(File(files.root, "missing.dng"))
        assertEquals(DecodeFailureReason.IO, error.reason)
        assertTrue(error.cause is java.io.IOException)
    }

    @Test
    fun cancellationBeforeIoIsNotWrappedAsADecodeError() {
        assertThrows(CancellationException::class.java) {
            DecoderDispatch.decode(DecodeRequest(File(files.root, "missing.dng"), checkCancelled = { throw CancellationException("stopped") }))
        }
    }

    @Test
    fun cancellationDuringRowsDiscardsTheResultAndAllowsTheNextRequest() {
        val pictures = listOf(
            SyntheticArw.file(300, 600, 400),
            SyntheticDng.file(200, 400, 300),
            SyntheticDng.file(200, 400, 300, storage = SyntheticDng.Storage.LOSSLESS_JPEG),
            SyntheticDng.jpegXl(javaClass.getResourceAsStream("/raw/groups.jxl")!!.use { it.readBytes() }),
        )
        pictures.forEachIndexed { index, bytes ->
            val source = file(bytes, "rows$index.bin")
            var checkpoints = 0
            assertThrows(CancellationException::class.java) {
                DecoderDispatch.decode(DecodeRequest(source, checkCancelled = {
                    if (++checkpoints >= 10) throw CancellationException("stopped between rows")
                }))
            }
            assertTrue(DecoderDispatch.decode(DecodeRequest(source)).samples.size > 0)
        }
    }

    @Test
    fun advertisesFullDecodeAndTheJpegXlCancellationLimit() {
        val capabilities = DecoderDispatch.capabilities
        assertEquals(setOf(RawFormat.SONY_ARW, RawFormat.DNG, RawFormat.CANON_CR2), capabilities.formats)
        assertFalse(capabilities.supportsDownsampling)
        assertFalse(capabilities.canInterruptJpegXl)
    }

    private fun failure(source: File): DecodeFailure = assertThrows(DecodeFailure::class.java) { DecoderDispatch.decode(DecodeRequest(source)) }
}
