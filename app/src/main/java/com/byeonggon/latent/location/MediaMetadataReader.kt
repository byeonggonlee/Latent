package com.byeonggon.latent.location

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.data.db.MediaKind
import java.io.File
import java.io.IOException
import java.time.ZoneId

data class MediaMetadata(val capturedAt: Long?, val location: GeoPoint?)

object MediaMetadataReader {
    fun read(file: File, kind: MediaKind): MediaMetadata = try {
        if (kind == MediaKind.VIDEO) readVideo(file) else readExif(file)
    } catch (e: IOException) {
        MediaMetadata(null, null)
    } catch (e: RuntimeException) {
        // The platform parsers throw unchecked exceptions on files they cannot make sense of.
        MediaMetadata(null, null)
    }

    private fun readExif(file: File): MediaMetadata {
        val exif = ExifInterface(file)
        val capturedAt = MediaTime.parseExif(
            dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
            offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME),
            fallbackZone = ZoneId.systemDefault(),
        )
        val location = exif.latLong?.let { (latitude, longitude) ->
            GeoPoint(latitude, longitude, exif.getAltitude(Double.NaN).takeUnless { it.isNaN() })
        }
        return MediaMetadata(capturedAt, location)
    }

    private fun readVideo(file: File): MediaMetadata = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        val date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        MediaMetadata(MediaTime.parseVideo(date), null)
    }
}
