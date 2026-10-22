package com.byeonggon.latent.location

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.data.db.MediaKind
import java.io.File
import java.io.IOException
import java.time.ZoneId

data class MediaMetadata(val capturedAt: Long?, val location: GeoPoint?)

object MediaMetadataReader {
    /** [cameraZone] is where a photo whose time has no zone of its own is taken to have been shot. */
    fun read(file: File, kind: MediaKind, cameraZone: ZoneId = ZoneId.systemDefault()): MediaMetadata = try {
        if (kind == MediaKind.VIDEO) readVideo(file) else readExif(file, cameraZone)
    } catch (e: IOException) {
        MediaMetadata(null, null)
    } catch (e: RuntimeException) {
        // The platform parsers throw unchecked exceptions on files they cannot make sense of.
        MediaMetadata(null, null)
    }

    private fun readExif(file: File, cameraZone: ZoneId): MediaMetadata {
        val exif = ExifInterface(file)
        val original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        // A camera may note the zone only beside the time it digitized the picture, which is the time it was shot too.
        val digitizedOffset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
            ?.takeIf { original != null && original == exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED) }
        val capturedAt = MediaTime.parseExif(
            dateTime = original ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
            offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                ?: digitizedOffset
                ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME),
            fallbackZone = cameraZone,
        )
        // Exif's degrees are ratios, which a damaged file can make 200 north, or a degree over nothing: no place on the Earth.
        val location = exif.latLong?.takeIf { (latitude, longitude) -> latitude in -90.0..90.0 && longitude in -180.0..180.0 }?.let { (latitude, longitude) ->
            GeoPoint(latitude, longitude, exif.getAltitude(Double.NaN).takeIf { it.isFinite() })
        }
        return MediaMetadata(capturedAt, location)
    }

    private fun readVideo(file: File): MediaMetadata = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        val date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        MediaMetadata(MediaTime.parseVideo(date), null)
    }
}
