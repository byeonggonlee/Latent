package com.byeonggon.latent.raw

import com.byeonggon.latent.raw.api.DecodeFailure
import com.byeonggon.latent.raw.api.DecodeFailureReason
import com.byeonggon.latent.raw.api.DecodeRequest
import com.byeonggon.latent.raw.api.DecodedFrame
import com.byeonggon.latent.raw.api.DecoderCapabilities
import java.io.IOException

/** Static selection, independent of the filename. Scheduling and the shared decode gate belong to the app. */
internal object DecoderDispatch {
    val capabilities = DecoderCapabilities()

    fun decode(request: DecodeRequest): DecodedFrame {
        request.checkCancelled()
        val bytes = readBytes(request)
        request.checkCancelled()
        try {
            if (bytes.size < 2 || !((bytes[0] == 73.toByte() && bytes[1] == 73.toByte()) ||
                    (bytes[0] == 77.toByte() && bytes[1] == 77.toByte()))) {
                throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Unsupported raw format")
            }
            val (data, offset) = TiffDirectory.open(bytes)
            val main = TiffDirectory.read(data, offset)
            val frame = when {
                0xC612 in main -> Dng.decode(bytes, request)
                main.text(0x010F)?.startsWith("SONY") == true -> SonyArw.decode(bytes, request)
                else -> throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Unsupported TIFF raw format")
            }
            request.checkCancelled()
            return frame
        } catch (e: DecodeFailure) {
            throw e
        } catch (e: IOException) {
            throw DecodeFailure(DecodeFailureReason.CORRUPT, e.message ?: "Damaged raw data", e)
        } catch (e: IndexOutOfBoundsException) {
            throw DecodeFailure(DecodeFailureReason.CORRUPT, "Damaged raw structure", e)
        }
    }

    private fun readBytes(request: DecodeRequest): ByteArray = try {
        val size = request.file.length()
        if (size > Int.MAX_VALUE || size > request.maxInputBytes) {
            throw DecodeFailure(DecodeFailureReason.RESOURCE_LIMIT, "RAW file exceeds the request's input limit")
        }
        request.file.inputStream().use { input ->
            val bytes = ByteArray(size.toInt())
            var at = 0
            while (at < bytes.size) {
                request.checkCancelled()
                val count = input.read(bytes, at, minOf(64 * 1024, bytes.size - at))
                if (count < 0) throw IOException("Raw file changed while being read")
                at += count
            }
            if (input.read() != -1) throw IOException("Raw file changed while being read")
            bytes
        }
    } catch (e: DecodeFailure) {
        throw e
    } catch (e: IOException) {
        throw DecodeFailure(DecodeFailureReason.IO, "Cannot read ${request.file.name}", e)
    }
}
