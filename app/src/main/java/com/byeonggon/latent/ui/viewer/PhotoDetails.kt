package com.byeonggon.latent.ui.viewer

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/** The parts the EXIF of a photo is shown in. */
enum class ExifSection { CAMERA, EXPOSURE, IMAGE, TIME, PLACE }

/** One value of the EXIF, under the name the standard gives it ("FNumber"). */
data class ExifRow(val name: String, val value: String)

data class ExifGroup(val section: ExifSection, val rows: List<ExifRow>)

/** How a photo was taken, as the camera recorded it, in one line; and everything the file records, for those who want it. */
object PhotoDetails {
    private val SECTIONS: List<Pair<ExifSection, List<String>>> = listOf(
        ExifSection.CAMERA to listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION, ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_CAMERA_OWNER_NAME,
        ),
        ExifSection.EXPOSURE to listOf(
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_SENSITIVITY_TYPE, ExifInterface.TAG_ISO_SPEED, ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY,
            ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_SUBJECT_DISTANCE, ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_FLASH, ExifInterface.TAG_FLASH_ENERGY, ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_SCENE_TYPE, ExifInterface.TAG_GAIN_CONTROL, ExifInterface.TAG_CONTRAST, ExifInterface.TAG_SATURATION,
            ExifInterface.TAG_SHARPNESS, ExifInterface.TAG_SENSING_METHOD, ExifInterface.TAG_CUSTOM_RENDERED,
        ),
        ExifSection.IMAGE to listOf(
            ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_X_RESOLUTION, ExifInterface.TAG_Y_RESOLUTION, ExifInterface.TAG_RESOLUTION_UNIT,
            ExifInterface.TAG_BITS_PER_SAMPLE, ExifInterface.TAG_SAMPLES_PER_PIXEL, ExifInterface.TAG_COMPRESSION,
            ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL,
            ExifInterface.TAG_COMPONENTS_CONFIGURATION, ExifInterface.TAG_EXIF_VERSION,
            ExifInterface.TAG_FLASHPIX_VERSION, ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ),
        ExifSection.TIME to listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_OFFSET_TIME,
        ),
        // Latitude and longitude are shown as one position, below.
        ExifSection.PLACE to listOf(
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_VERSION_ID,
        ),
    )

    /**
     * Every value of the EXIF that the file records and that is worth a line, as recorded (the names are
     * the standard's, so not translated), in the order of [ExifSection]. Empty if the file tells nothing.
     * Reads the file, so not for the main thread.
     */
    fun all(file: File): List<ExifGroup> {
        val exif = try {
            ExifInterface(file)
        } catch (e: IOException) {
            return emptyList()
        } catch (e: RuntimeException) {
            return emptyList()
        }
        return SECTIONS.mapNotNull { (section, tags) ->
            val rows = tags.mapNotNull { tag -> exif.getAttribute(tag)?.let { value -> readable(tag, value)?.let { ExifRow(tag, it) } } }
            val position = if (section == ExifSection.PLACE) exif.latLong?.let { (latitude, longitude) -> ExifRow("GPSPosition", String.format(Locale.US, "%.6f, %.6f", latitude, longitude)) } else null
            val all = listOfNotNull(position) + rows
            all.takeIf { it.isNotEmpty() }?.let { ExifGroup(section, it) }
        }
    }

    private val RATIO = Regex("^(-?\\d+)/(-?\\d+)$")
    private val DECIMAL = Regex("^-?\\d+\\.\\d+$")

    // The library fills these in with zero for a file that says nothing of them.
    private val ZERO_MEANS_NOTHING = setOf(
        ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_LIGHT_SOURCE,
    )

    /**
     * A value as people write it: a ratio or a long decimal as a short number, a short exposure as
     * 1/250 s; anything else as it is. Null for a value with nothing in it.
     */
    internal fun readable(tag: String, raw: String): String? {
        val value = raw.trim().trimEnd('\u0000').trim()
        if (value.isEmpty() || (value == "0" && tag in ZERO_MEANS_NOTHING)) return null
        val ratio = RATIO.matchEntire(value)
        val number = when {
            ratio != null -> ratio.groupValues[2].toDouble().takeIf { it != 0.0 }?.let { ratio.groupValues[1].toDouble() / it }
            DECIMAL.matches(value) -> value.toDouble()
            else -> null
        } ?: return value
        return if (tag == ExifInterface.TAG_EXPOSURE_TIME && number > 0) shutter(number) else decimal(number)
    }

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

    /** Up to three decimals, without the trailing zeros. */
    private fun decimal(value: Double): String =
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun plain(value: Double): String =
        if (value == Math.rint(value)) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
}
