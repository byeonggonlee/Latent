package com.byeonggon.latent.raw

import com.traneptora.jxlatte.JXLDecoder
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Reads the JPEG XL streams (DNG compression 52546) in which a Galaxy stores the samples of an Expert RAW
 * file, with jxlatte (third_party/jxlatte). Each stream is one tile or strip, its channels apart; they are
 * stored here side by side, as uncompressed and lossless JPEG samples are.
 */
internal object JpegXl {
    /**
     * Decodes the stream of [length] bytes at [offset] in [file] into [into], a picture [width] pixels across and
     * [height] down with [channels] samples a pixel, as the tile of [tileWidth] by [tileLength] pixels whose corner is at
     * [left], [top]. Samples of [bits] bits.
     */
    fun decode(
        file: ByteArray, offset: Int, length: Int, into: ShortArray, width: Int, height: Int,
        left: Int, top: Int, tileWidth: Int, tileLength: Int, channels: Int, bits: Int,
    ) {
        if (offset < 0 || length <= 0 || offset.toLong() + length > file.size) throw IOException("Raw image data is cut short")
        val image = try {
            JXLDecoder(ByteArrayInputStream(file, offset, length)).decode()
        } catch (e: IOException) {
            throw IOException("Damaged JPEG XL data", e)
        } catch (e: RuntimeException) {
            // jxlatte reports some damage in the stream with exceptions of the runtime.
            throw IOException("Damaged JPEG XL data", e)
        } ?: throw IOException("Damaged JPEG XL data")
        val planes = image.getBuffer(false)
        val columns = minOf(tileWidth, width - left)
        val rows = minOf(tileLength, height - top)
        if (planes.size < channels || image.width < columns || image.height < rows) {
            throw IOException("JPEG XL data of ${image.width}x${image.height}x${planes.size} does not fill a tile of ${columns}x${rows}x$channels")
        }
        val most = (1 shl bits) - 1
        for (channel in 0 until channels) {
            val plane = planes[channel]
            if (plane.isInt) {
                val samples = plane.intBuffer
                for (y in 0 until rows) {
                    val source = samples[y]
                    var at = ((top + y) * width + left) * channels + channel
                    for (x in 0 until columns) {
                        into[at] = source[x].coerceIn(0, most).toShort()
                        at += channels
                    }
                }
            } else {
                // Floating point samples run from 0 to 1.
                val samples = plane.floatBuffer
                for (y in 0 until rows) {
                    val source = samples[y]
                    var at = ((top + y) * width + left) * channels + channel
                    for (x in 0 until columns) {
                        into[at] = (source[x] * most).roundToInt().coerceIn(0, most).toShort()
                        at += channels
                    }
                }
            }
        }
    }
}
