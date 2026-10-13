package com.byeonggon.cameramanager.raw

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.byeonggon.cameramanager.enhance.AutoEnhance
import com.byeonggon.cameramanager.enhance.PhotoEnhancer
import java.io.File
import java.io.IOException
import kotlin.math.max

/** What to do to a raw file on its way to becoming a picture. */
data class RawOptions(val lensCorrection: Boolean, val autoEnhance: Boolean)

/** Develops raw files into bitmaps and JPEGs. All calls are slow and must run off the main thread. */
object RawRenderer {
    private const val TAG = "RawDevelop"
    private const val ANALYSIS_EDGE = 256

    @Throws(IOException::class)
    fun read(file: File): ArwImage = timed("decode ${file.name}") { SonyArw.read(file.readBytes()) }

    /** A half-resolution rendering for the screen, turned the way the camera was held. */
    fun preview(image: ArwImage, options: RawOptions): Bitmap {
        val developed = timed("preview") { finish(RawDeveloper.developPreview(image), image.lens, options) }
        val bitmap = Bitmap.createBitmap(developed.pixels, developed.width, developed.height, Bitmap.Config.ARGB_8888)
        val degrees = rotationOf(image.orientation)
        if (degrees == 0) return bitmap
        val rotation = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotation, true).also { bitmap.recycle() }
    }

    /** Develops [source] at full resolution into a JPEG at [target], carrying over its camera settings. */
    @Throws(IOException::class)
    fun export(source: File, target: File, options: RawOptions) {
        val developed = timed("full development") { developFull(source, options) }
        val bitmap = Bitmap.createBitmap(developed.pixels, developed.width, developed.height, Bitmap.Config.ARGB_8888)
        try {
            timed("encode") {
                target.outputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PhotoEnhancer.JPEG_QUALITY, output)) {
                        throw IOException("Cannot encode ${target.name}")
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
        // The pixels stay as the sensor recorded them; the copied orientation tells viewers how to turn them.
        PhotoEnhancer.copyExif(source, target)
    }

    // A function of its own so the decoded sensor data can be freed before the result is encoded.
    private fun developFull(source: File, options: RawOptions): DevelopedImage {
        val image = read(source)
        return finish(RawDeveloper.develop(image), image.lens, options)
    }

    private fun finish(developed: DevelopedImage, lens: LensProfile?, options: RawOptions): DevelopedImage {
        val straightened = if (options.lensCorrection && lens != null) {
            LensCorrection(lens, developed.width, developed.height).straighten(developed)
        } else {
            developed
        }
        if (options.autoEnhance) AutoEnhance.analyze(sample(straightened)).apply(straightened.pixels)
        return straightened
    }

    /** An evenly spread selection of pixels, enough to judge the picture's tones by. */
    private fun sample(image: DevelopedImage): IntArray {
        val step = max(1, max(image.width, image.height) / ANALYSIS_EDGE)
        val columns = image.width / step
        val rows = image.height / step
        return IntArray(columns * rows) { index ->
            image.pixels[(index / columns) * step * image.width + (index % columns) * step]
        }
    }

    private fun rotationOf(orientation: Int): Int = when (orientation) {
        3 -> 180
        6 -> 90
        8 -> 270
        else -> 0
    }

    private inline fun <T> timed(what: String, block: () -> T): T {
        val startedAt = System.nanoTime()
        return block().also { Log.d(TAG, "$what: ${(System.nanoTime() - startedAt) / 1_000_000} ms") }
    }
}
