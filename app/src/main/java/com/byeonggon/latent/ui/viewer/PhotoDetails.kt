package com.byeonggon.latent.ui.viewer

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/** How a photo was taken, as the camera recorded it, in one line. */
object PhotoDetails {
    /** Reads the line for [file], or null if the file tells nothing. Reads the file, so not for the main thread. */
    fun of(file: File): String? {
        val exif = try {
            ExifInterface(file)
        } catch (e: IOException) {
            return null
        }
        return describe(
            camera = exif.getAttribute(ExifInterface.TAG_MODEL),
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
            focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0),
            aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0),
            exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0),
            sensitivity = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0),
        )
    }

    /** Puts together whatever is known; zero and blank stand for unknown. Null if nothing is known. */
    fun describe(camera: String?, lens: String?, focalLength: Double, aperture: Double, exposureTime: Double, sensitivity: Int): String? =
        listOfNotNull(
            camera?.trim()?.takeIf { it.isNotEmpty() },
            lens?.trim()?.takeIf { it.isNotEmpty() },
            focalLength.takeIf { it > 0 }?.let { "${it.roundToInt()}mm" },
            aperture.takeIf { it > 0 }?.let { "f/" + plain(it) },
            exposureTime.takeIf { it > 0 }?.let(::shutter),
            sensitivity.takeIf { it > 0 }?.let { "ISO $it" },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }

    /** Short exposures as a fraction of a second, the way cameras show them; long ones in seconds. */
    private fun shutter(seconds: Double): String =
        if (seconds < 0.4) "1/${(1 / seconds).roundToInt()} s" else plain(seconds) + " s"

    private fun plain(value: Double): String =
        if (value == Math.rint(value)) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
}
