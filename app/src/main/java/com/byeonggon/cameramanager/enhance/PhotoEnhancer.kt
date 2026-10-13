package com.byeonggon.cameramanager.enhance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Runs [AutoEnhance] over JPEG files. All calls decode bitmaps and must run off the main thread. */
object PhotoEnhancer {
    private const val ANALYSIS_EDGE = 256
    private const val ROWS_PER_PASS = 64
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

    /** Decodes [source] no larger than [maxEdge] on its long side and returns the enhanced bitmap. */
    fun preview(source: File, maxEdge: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val longEdge = max(info.size.width, info.size.height)
            if (longEdge > maxEdge) {
                val scale = maxEdge.toFloat() / longEdge
                decoder.setTargetSize(
                    max(1, (info.size.width * scale).roundToInt()),
                    max(1, (info.size.height * scale).roundToInt()),
                )
            }
        }.also(::enhance)

    /**
     * Replaces [photo] with its enhanced version. The original moves to [backup], from where
     * [restoreOriginal] brings it back; enhancing again starts over from that original.
     */
    @Throws(IOException::class)
    fun enhanceInPlace(photo: File, backup: File) {
        val original = if (backup.exists()) backup else photo
        // Decoded as stored, without applying the Exif orientation, so the copied Exif block still fits.
        val options = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = BitmapFactory.decodeFile(original.path, options) ?: throw IOException("Cannot decode ${photo.name}")
        val encoded = ByteArrayOutputStream()
        try {
            enhance(bitmap)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, encoded)) {
                throw IOException("Cannot encode ${photo.name}")
            }
        } finally {
            bitmap.recycle()
        }
        val enhanced = File(photo.parentFile, ".${photo.name}.tmp")
        enhanced.writeBytes(JpegMetadata.withExifOf(original.readBytes(), encoded.toByteArray()))

        if (!backup.exists()) {
            backup.parentFile?.mkdirs()
            if (!photo.renameTo(backup)) {
                enhanced.delete()
                throw IOException("Cannot keep the original of ${photo.name}")
            }
        }
        if (!enhanced.renameTo(photo)) {
            enhanced.delete()
            backup.renameTo(photo)
            throw IOException("Cannot replace ${photo.name}")
        }
    }

    /** Puts the original kept at [backup] back in place of the enhanced [photo]. */
    @Throws(IOException::class)
    fun restoreOriginal(photo: File, backup: File) {
        if (!backup.renameTo(photo)) throw IOException("Cannot restore the original of ${photo.name}")
    }

    /** Carries the camera settings and location of [source] over to a picture made from it. */
    @Throws(IOException::class)
    fun copyExif(source: File, target: File) {
        val from = ExifInterface(source)
        val to = ExifInterface(target)
        for (tag in COPIED_EXIF_TAGS) {
            from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
        }
        to.saveAttributes()
    }

    private fun enhance(bitmap: Bitmap) {
        val transform = AutoEnhance.analyze(sample(bitmap))
        val width = bitmap.width
        // Strips keep the working memory small; a 24 MP frame would not fit twice.
        val buffer = IntArray(width * ROWS_PER_PASS)
        var y = 0
        while (y < bitmap.height) {
            val rows = minOf(ROWS_PER_PASS, bitmap.height - y)
            bitmap.getPixels(buffer, 0, width, 0, y, width, rows)
            transform.apply(buffer, width * rows)
            bitmap.setPixels(buffer, 0, width, 0, y, width, rows)
            y += rows
        }
    }

    private fun sample(bitmap: Bitmap): IntArray {
        val scale = ANALYSIS_EDGE.toFloat() / max(bitmap.width, bitmap.height)
        val small = if (scale < 1f) {
            bitmap.scale(
                max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()),
            )
        } else {
            bitmap
        }
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        if (small !== bitmap) small.recycle()
        return pixels
    }
}
