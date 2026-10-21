package com.byeonggon.latent.raw.api

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

internal enum class DecodeFailureReason { UNSUPPORTED, CORRUPT, RESOURCE_LIMIT, IO }
internal class DecodeFailure(val reason: DecodeFailureReason, message: String, cause: Throwable? = null) : IOException(message, cause)

/** Explicit ceilings cover input bytes and the final sample array, not codec scratch or total phone heap. */
internal class DecodeRequest(
    val file: File,
    val maxInputBytes: Long = Long.MAX_VALUE,
    val maxDecodedBytes: Long = Long.MAX_VALUE,
    val checkCancelled: () -> Unit = { if (Thread.currentThread().isInterrupted) throw CancellationException("RAW decoding interrupted") },
) {
    init { require(maxInputBytes >= 0 && maxDecodedBytes >= 0) }

    fun checkSamples(samples: Long) {
        checkCancelled()
        if (samples < 0 || samples > Int.MAX_VALUE || samples > maxDecodedBytes / 2) {
            throw DecodeFailure(DecodeFailureReason.RESOURCE_LIMIT, "RAW samples exceed the request's byte limit")
        }
    }
}

internal class DecoderCapabilities {
    val formats: Set<RawFormat> = setOf(RawFormat.SONY_ARW, RawFormat.DNG)
    val supportsDownsampling: Boolean = false
    val canInterruptJpegXl: Boolean = false
}
