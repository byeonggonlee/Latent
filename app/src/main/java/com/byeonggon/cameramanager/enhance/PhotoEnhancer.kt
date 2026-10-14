package com.byeonggon.cameramanager.enhance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max

/** Edits JPEG files. All calls decode bitmaps and must run off the main thread. */
object PhotoEnhancer {
    private const val ROWS_PER_PASS = 256
    const val JPEG_QUALITY = 95

    private val COPIED_EXIF_TAGS = arrayOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
    )

    /**
     * Decodes [source] upright and no larger than [maxEdge] on its long side, ready for editing.
     * It may come out up to half that size, as it is only ever shrunk by whole factors.
     */
    @Throws(IOException::class)
    fun load(source: File, maxEdge: Int): LinearImage = shrunk(source, maxEdge).turned(uprightTurnsOf(source))

    /**
     * Replaces [photo] with its edited version. The original moves to [backup], from where
     * [restoreOriginal] brings it back; editing again starts over from that original.
     */
    @Throws(IOException::class)
    fun saveEdited(photo: File, backup: File, edit: PhotoEdit) {
        val original = if (backup.exists()) backup else photo
        // Masks are placed on the upright picture; one that is stored on its side is turned upright for them too.
        val reframed = edit.framing != Framing() || (edit.locals.isNotEmpty() && uprightTurnsOf(original) != 0)
        val bitmap = if (reframed) reframed(original, edit) else retoned(original, edit)
        val encoded = ByteArrayOutputStream()
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, encoded)) {
                throw IOException("Cannot encode ${photo.name}")
            }
        } finally {
            bitmap.recycle()
        }
        val edited = File(photo.parentFile, ".${photo.name}.tmp")
        edited.writeBytes(JpegMetadata.withExifOf(original.readBytes(), encoded.toByteArray(), upright = reframed))

        if (!backup.exists()) {
            backup.parentFile?.mkdirs()
            if (!photo.renameTo(backup)) {
                edited.delete()
                throw IOException("Cannot keep the original of ${photo.name}")
            }
        }
        if (!edited.renameTo(photo)) {
            edited.delete()
            backup.renameTo(photo)
            throw IOException("Cannot replace ${photo.name}")
        }
    }

    /** Puts the original kept at [backup] back in place of the enhanced [photo]. */
    @Throws(IOException::class)
    fun restoreOriginal(photo: File, backup: File) {
        if (!backup.renameTo(photo)) throw IOException("Cannot restore the original of ${photo.name}")
    }

    /**
     * Carries the camera settings and location of [source] over to a picture made from it. [upright]
     * says that the picture's pixels are already turned the way the camera was held.
     */
    @Throws(IOException::class)
    fun copyExif(source: File, target: File, upright: Boolean = false) {
        val from = ExifInterface(source)
        val to = ExifInterface(target)
        for (tag in COPIED_EXIF_TAGS) {
            from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
        }
        if (upright) to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        to.saveAttributes()
    }

    /**
     * The picture with its tones edited and every pixel where it was: as stored, without applying
     * the Exif orientation, so that the copied Exif block still fits.
     */
    private fun retoned(original: File, edit: PhotoEdit): Bitmap {
        val options = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = BitmapFactory.decodeFile(original.path, options) ?: throw IOException("Cannot decode ${original.name}")
        try {
            render(bitmap, ToneRenderer(edit, shrunk(original, ToneRenderer.OVERVIEW_EDGE)))
            DetailFilter(edit.adjustments, scale = 1f).applyTo(bitmap)
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
        return bitmap
    }

    /** The picture turned upright, cut and levelled as the edit says, with its tones edited. */
    private fun reframed(original: File, edit: PhotoEdit): Bitmap {
        val upright = uprightTurnsOf(original)
        return renderedBitmap(edit, scale = 1f) {
            val bitmap = BitmapFactory.decodeFile(original.path) ?: throw IOException("Cannot decode ${original.name}")
            linear(bitmap).framed(edit.framing, upright)
        }
    }

    /**
     * A part of the picture in [file] at full size, at most [size] pixels each way, around a centre
     * given as fractions of the upright picture. For judging detail.
     */
    @Throws(IOException::class)
    fun region(file: File, centreX: Float, centreY: Float, size: Int): PictureRegion {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val turns = uprightTurnsOf(file)
        val sideways = turns % 2 == 1
        val frameWidth = if (sideways) bounds.outHeight else bounds.outWidth
        val frameHeight = if (sideways) bounds.outWidth else bounds.outHeight
        val upright = PictureRegion.bounds(frameWidth, frameHeight, centreX, centreY, size)
        val (left, top, width, height) = PictureRegion.asStored(upright, turns, bounds.outWidth, bounds.outHeight)
        val decoder = BitmapRegionDecoder.newInstance(file.path)
        try {
            val bitmap = decoder.decodeRegion(Rect(left, top, left + width, top + height), null)
                ?: throw IOException("Cannot decode ${file.name}")
            return PictureRegion(linear(bitmap).turned(turns), upright[0], upright[1], frameWidth, frameHeight)
        } finally {
            decoder.recycle()
        }
    }

    /** The longer side of the picture in [file], in pixels. */
    fun longSideOf(file: File): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return max(bounds.outWidth, bounds.outHeight)
    }

    /** The quarter turns clockwise that show the picture the way the camera was held. */
    private fun uprightTurnsOf(file: File): Int =
        when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 1
            ExifInterface.ORIENTATION_ROTATE_180 -> 2
            ExifInterface.ORIENTATION_ROTATE_270 -> 3
            else -> 0
        }

    private fun render(bitmap: Bitmap, renderer: ToneRenderer) {
        val width = bitmap.width
        // Strips keep the working memory small; a 24 MP frame would not fit several times over.
        val buffer = IntArray(width * ROWS_PER_PASS)
        var y = 0
        while (y < bitmap.height) {
            val rows = minOf(ROWS_PER_PASS, bitmap.height - y)
            bitmap.getPixels(buffer, 0, width, 0, y, width, rows)
            renderer.render(LinearImage.fromArgb(buffer, width, rows), buffer, top = y, frameHeight = bitmap.height)
            bitmap.setPixels(buffer, 0, width, 0, y, width, rows)
            y += rows
        }
    }

    /** The picture as it is stored, without regard to its Exif orientation, no larger than [maxEdge] on its long side. */
    private fun shrunk(file: File, maxEdge: Int): LinearImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options = BitmapFactory.Options().apply {
            // The decoder averages as it shrinks, in powers of two; the rest of the way is done here.
            inSampleSize = Integer.highestOneBit(max(1, max(bounds.outWidth, bounds.outHeight) / maxEdge))
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("Cannot decode ${file.name}")
        return linear(bitmap).downscaled(maxEdge)
    }

    private fun linear(bitmap: Bitmap): LinearImage {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return LinearImage.fromArgb(pixels, bitmap.width, bitmap.height).also { bitmap.recycle() }
    }
}
