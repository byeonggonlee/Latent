package com.byeonggon.latent.raw

import java.io.IOException

/** Rearranges the consecutive vertical slices in Canon's lossless JPEG into sensor rows. */
internal object Cr2Slices {
    fun join(samples: ShortArray, width: Int, height: Int, slices: IntArray, checkCancelled: () -> Unit = {}): ShortArray {
        validate(samples.size, width, height, slices)
        checkCancelled()
        if (slices[0] == 0) return samples
        val joined = ShortArray(samples.size)
        var source = 0
        var left = 0
        for (slice in 0..slices[0]) {
            val sliceWidth = if (slice == slices[0]) slices[2] else slices[1]
            for (row in 0 until height) {
                checkCancelled()
                samples.copyInto(joined, row * width + left, source, source + sliceWidth)
                source += sliceWidth
            }
            left += sliceWidth
        }
        return joined
    }

    /** Validate before allocating or decompressing the claimed frame. */
    fun validate(sampleCount: Int, width: Int, height: Int, slices: IntArray) {
        if (width <= 0 || height <= 0 || width.toLong() * height != sampleCount.toLong() ||
            slices.size != 3 || slices[0] < 0 || slices[1] < 0 || slices[2] <= 0 ||
            (slices[0] > 0 && slices[1] == 0) ||
            slices[0].toLong() * slices[1] + slices[2] != width.toLong()) {
            throw IOException("Invalid Canon raw slice layout")
        }
    }

}
