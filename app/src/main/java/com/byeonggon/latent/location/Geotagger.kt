package com.byeonggon.latent.location

import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.data.LibraryStorage
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.LocationDao
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.MediaDao
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class Geotagger(
    private val locationDao: LocationDao,
    private val mediaDao: MediaDao,
    private val storage: LibraryStorage,
) {
    /** Looks up where the phone was at [timestamp] in the recorded track. */
    suspend fun locate(timestamp: Long, maxGapMs: Long): GeoPoint? = TrackMatcher.locate(
        before = locationDao.lastAtOrBefore(timestamp)?.toFix(),
        after = locationDao.firstAtOrAfter(timestamp)?.toFix(),
        timestamp = timestamp,
        maxGapMs = maxGapMs,
    )

    /** Writes GPS tags into a JPEG. Returns false when the format cannot be rewritten. */
    fun writeExif(file: File, point: GeoPoint): Boolean = try {
        val modifiedAt = file.lastModified()
        if (file.canWrite()) {
            ExifInterface(file).tag(point)
        } else {
            // A photo another installation of the app made can only be changed through the granted folder.
            storage.folder.openReadWrite(file).use { ExifInterface(it.fileDescriptor).tag(point) }
        }
        file.setLastModified(modifiedAt)
        true
    } catch (e: IOException) {
        false
    }

    private fun ExifInterface.tag(point: GeoPoint) {
        setLatLong(point.latitude, point.longitude)
        point.altitude?.let { setAltitude(it) }
        saveAttributes()
    }

    /** Writes an XMP sidecar next to a RAW file. An existing sidecar is left untouched. */
    fun writeSidecar(rawFile: File, point: GeoPoint): Boolean {
        val sidecar = File(rawFile.parentFile, "${rawFile.nameWithoutExtension}.xmp")
        if (storage.exists(sidecar)) return false
        return try {
            storage.writeNew(sidecar, XmpSidecar.build(point))
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Geotags library items that have no location yet. Returns how many got one. */
    suspend fun tagPending(maxGapMs: Long): Int = withContext(Dispatchers.IO) {
        var tagged = 0
        for (item in mediaDao.withoutLocation()) {
            ensureActive()
            val point = locate(item.capturedAt, maxGapMs) ?: continue
            val file = File(item.path)
            when (item.kind) {
                MediaKind.PHOTO -> if (writeExif(file, point)) storage.publish(file)
                MediaKind.RAW -> writeSidecar(file, point)
                MediaKind.VIDEO -> Unit
            }
            mediaDao.setLocation(item.id, point.latitude, point.longitude, point.altitude, GeoSource.TRACK)
            tagged++
        }
        tagged
    }

    private fun LocationPointEntity.toFix() = TrackFix(timestamp, latitude, longitude, altitude)
}
