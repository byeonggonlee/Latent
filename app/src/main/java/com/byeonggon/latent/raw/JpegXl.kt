package com.byeonggon.latent.raw

import com.traneptora.jxlatte.JXLDecoder
import com.traneptora.jxlatte.JXLOptions
import com.traneptora.jxlatte.bundle.ImageHeader
import com.traneptora.jxlatte.io.Bitreader
import com.traneptora.jxlatte.io.Demuxer
import com.traneptora.jxlatte.io.Loggers
import com.traneptora.jxlatte.io.PushbackInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.math.roundToInt

/**
 * Reads the JPEG XL streams (DNG compression 52546) in which a Galaxy stores the samples of an Expert RAW
 * file, with jxlatte (third_party/jxlatte). Each stream is one tile or strip, its channels apart; they are
 * stored here side by side, as uncompressed and lossless JPEG samples are.
 */
internal object JpegXl {
    /** Checks the header before either the DNG sensor or the codec's full frame is allocated. */
    fun checkSize(file: ByteArray, offset: Int, length: Int, widths: IntRange, heights: IntRange, channels: Int) {
        if (offset < 0 || length <= 0 || offset.toLong() + length > file.size) throw IOException("Raw image data is cut short")
        try {
            Demuxer(ByteArrayInputStream(file, offset, length)).use { demuxer ->
                val reader = Bitreader(PushbackInputStream(demuxer))
                reader.showBits(16) // Reading the signature establishes the container's level, too.
                val header = ImageHeader.read(Loggers(JXLOptions(), PrintWriter(StringWriter())), reader, demuxer.level)
                val size = header.orientedSize
                if (size.width !in widths || size.height !in heights || header.colorChannelCount != channels || header.extraChannelCount != 0) {
                    throw IOException("JPEG XL header does not match the DNG tile")
                }
            }
        } catch (e: IOException) {
            throw IOException("Damaged JPEG XL header", e)
        } catch (e: RuntimeException) {
            throw IOException("Damaged JPEG XL header", e)
        }
    }

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
            checkSize(file, offset, length, minOf(tileWidth, width - left)..tileWidth, minOf(tileLength, height - top)..tileLength, channels)
            JXLDecoder(ByteArrayInputStream(file, offset, length)).use { it.decode() }
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
