package com.byeonggon.latent.location

import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.data.LibraryStorage
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.LocationDao
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.MediaDao
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

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

    /**
     * [writeExif] for a photo of the library, which may be the only copy of it: Exif is rewritten by writing the
     * whole file again, so it is written into a copy beside the photo, which takes the photo's place once whole.
     * One that another installation made is written to where it is, as only the granted folder reaches it.
     */
    private fun writeExifInLibrary(file: File, point: GeoPoint): Boolean {
        if (!file.canWrite()) return writeExif(file, point)
        // Hidden, but still named as a photo: shared storage only takes photos and videos in DCIM.
        val tagging = File(file.parentFile, ".${file.nameWithoutExtension}-tagging.${file.extension}")
        return try {
            file.copyTo(tagging, overwrite = true)
            ExifInterface(tagging).tag(point)
            RandomAccessFile(tagging, "rw").use { it.fd.sync() }
            tagging.setLastModified(file.lastModified())
            tagging.renameTo(file).also { if (!it) tagging.delete() }
        } catch (e: IOException) {
            tagging.delete()
            false
        }
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

    /** Geotags library items that have no location yet, taking the camera's clock to run [clockOffsetMs] ahead. Returns how many got one. */
    suspend fun tagPending(maxGapMs: Long, clockOffsetMs: Long = 0L): Int = withContext(Dispatchers.IO) {
        var tagged = 0
        for (item in mediaDao.withoutLocation()) {
            ensureActive()
            val point = locate(item.capturedAt - clockOffsetMs, maxGapMs) ?: continue
            val file = File(item.path)
            // A photo that another app made is not written to; the place is kept in the database only. One of the
            // library's is not written to while it is being edited or moved.
            if (item.origin == MediaOrigin.LIBRARY) {
                storage.moving.withLock {
                    when (item.kind) {
                        MediaKind.PHOTO -> if (item.isHeif) writeSidecar(file, point) else if (writeExifInLibrary(file, point)) storage.publish(file)
                        MediaKind.RAW -> writeSidecar(file, point)
                        MediaKind.VIDEO -> Unit
                    }
                }
            }
            mediaDao.setLocation(item.id, point.latitude, point.longitude, point.altitude, GeoSource.TRACK)
            tagged++
        }
        tagged
    }

    private fun LocationPointEntity.toFix() = TrackFix(timestamp, latitude, longitude, altitude)
}
