package com.byeonggon.latent.raw

import android.graphics.Bitmap
import android.util.Log
import com.byeonggon.latent.enhance.Framing
import com.byeonggon.latent.enhance.Healing
import com.byeonggon.latent.enhance.LinearImage
import com.byeonggon.latent.enhance.Orientation
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.enhance.PhotoEnhancer
import com.byeonggon.latent.enhance.PictureRegion
import com.byeonggon.latent.enhance.framed
import com.byeonggon.latent.enhance.renderedBitmap
import java.io.File
import java.io.IOException

/** Develops raw files into bitmaps and JPEGs. All calls are slow and must run off the main thread. */
object RawRenderer {
    private const val TAG = "RawDevelop"

    /** Reads a raw file of either kind the app develops: a DNG, as phones write them, or a Sony ARW. */
    @Throws(IOException::class)
    fun read(file: File): ArwImage = timed("decode ${file.name}") {
        val bytes = file.readBytes()
        try {
            if (Dng.isDng(bytes)) Dng.read(bytes) else SonyArw.read(bytes)
        } catch (e: IOException) {
            // The screens only say that the photo could not be opened; why is kept here.
            Log.w(TAG, "Cannot develop ${file.name}: ${e.message}")
            throw e
        }
    }

    /**
     * The picture in a size for the screen, turned the way the camera was held. [cells] sets the size
     * as in [RawDeveloper.developPreview].
     */
    fun proxy(image: ArwImage, cells: Int, lensCorrection: Boolean): LinearImage = timed("proxy") {
        val linear = RawDeveloper.developPreview(image, cells)
        if (lensCorrection) straighten(linear, image.lens)
        linear.oriented(Orientation.ofExif(image.orientation))
    }

    /**
     * A part of the picture at full size, at most [size] pixels each way, around a centre given as
     * fractions of the upright picture. For judging detail; the lens is not corrected for here.
     */
    fun region(image: ArwImage, centreX: Float, centreY: Float, size: Int): PictureRegion {
        val orientation = Orientation.ofExif(image.orientation)
        val sideways = orientation.isSideways
        val frameWidth = if (sideways) image.cropHeight else image.cropWidth
        val frameHeight = if (sideways) image.cropWidth else image.cropHeight
        val upright = PictureRegion.bounds(frameWidth, frameHeight, centreX, centreY, size)
        val stored = PictureRegion.asStored(upright, orientation, image.cropWidth, image.cropHeight)
        // Starting on an even photosite, as the colours repeat every two.
        val part = image.part(stored[0] and 1.inv(), stored[1] and 1.inv(), stored[2] and 1.inv(), stored[3] and 1.inv())
        return PictureRegion(RawDeveloper.develop(part).oriented(orientation), upright[0], upright[1], frameWidth, frameHeight)
    }

    /** A half-resolution rendering for the screen. */
    fun preview(image: ArwImage, edit: PhotoEdit): Bitmap = timed("render") {
        renderedBitmap(edit, scale = 0.5f) {
            proxy(image, cells = 1, edit.lensCorrection).also { Healing.heal(it, edit.spots) }.framed(edit.framing)
        }
    }

    /** Develops [source] at full resolution into a JPEG at [target] compressed to [quality], carrying over its camera settings. */
    @Throws(IOException::class)
    fun export(source: File, target: File, edit: PhotoEdit, quality: Int = PhotoEnhancer.JPEG_QUALITY) {
        val bitmap = timed("full development") { renderedBitmap(edit, scale = 1f) { developFull(source, edit) } }
        try {
            timed("encode") {
                target.outputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        throw IOException("Cannot encode ${target.name}")
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
        PhotoEnhancer.copyExif(source, target, upright = bakesOrientation(edit))
    }

    /**
     * Whether an export turns the pixels upright. A picture that is reframed is; so is one with masks,
     * which are placed on the upright picture. (A picture taken upright is unaffected either way.)
     */
    private fun bakesOrientation(edit: PhotoEdit) = edit.framing != Framing() || edit.locals.isNotEmpty()

    /**
     * Unless the picture is reframed, its pixels stay as the sensor recorded them and the copied
     * orientation tells viewers how to turn them. Reframing turns them upright on the way.
     */
    private fun developFull(source: File, edit: PhotoEdit): LinearImage {
        val (linear, orientation) = developStraight(source, edit.lensCorrection)
        Healing.heal(linear, edit.spots, orientation)
        return if (bakesOrientation(edit)) linear.framed(edit.framing, orientation) else linear
    }

    // A function of its own so the decoded sensor data can be freed before more copies of the picture are made.
    private fun developStraight(source: File, lensCorrection: Boolean): Pair<LinearImage, Orientation> {
        val image = read(source)
        val linear = RawDeveloper.develop(image)
        if (lensCorrection) straighten(linear, image.lens)
        return linear to Orientation.ofExif(image.orientation)
    }

    private fun straighten(linear: LinearImage, lens: LensProfile?) {
        if (lens != null) LensCorrection(lens, linear.width, linear.height).straighten(linear)
    }

    private inline fun <T> timed(what: String, block: () -> T): T {
        val startedAt = System.nanoTime()
        return block().also { Log.d(TAG, "$what: ${(System.nanoTime() - startedAt) / 1_000_000} ms") }
    }
}
