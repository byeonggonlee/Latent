package com.byeonggon.latent.data

import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** How a photo was taken, as far as it is searched by. */
data class ExifSummary(val camera: String?, val lens: String?, val iso: Int?, val focalLength: Double?) {
    companion object {
        val NONE = ExifSummary(null, null, null, null)

        /** Reads [file]; null when it cannot be read just now, which is not the same as saying nothing. */
        fun read(file: File): ExifSummary? {
            val exif = try {
                ExifInterface(file)
            } catch (e: IOException) {
                return null
            } catch (e: RuntimeException) {
                return NONE
            }
            val equivalent = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
            return ExifSummary(
                camera = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
                lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
                iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
                focalLength = (if (equivalent > 0) equivalent.toDouble() else exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)).takeIf { it > 0 },
            )
        }
    }
}

/**
 * Reads the Exif that the library is searched by (camera, lens, ISO, focal length) from each file the
 * first time, in the background, and keeps it in the database. A file that cannot be read now is left
 * for the next time.
 */
class ExifIndex(private val storage: LibraryStorage, private val database: AppDatabase) {
    private companion object {
        const val BATCH = 200
        const val READERS = 4
    }

    /** Reads what has not been read yet. Returns how many items it filled in. */
    suspend fun run(canReadPhoneFiles: Boolean = true): Int = withContext(Dispatchers.IO) {
        if (!storage.isAvailable()) return@withContext 0
        val dao = database.mediaDao()
        val permits = Semaphore(READERS)
        var filled = 0
        var after = 0L
        while (true) {
            ensureActive()
            val batch = dao.withoutExif(after, BATCH)
            if (batch.isEmpty()) break
            after = batch.last().id
            val read: List<Pair<MediaItemEntity, ExifSummary?>> = coroutineScope {
                batch.map { item -> async { item to permits.withPermit { summaryOf(item, canReadPhoneFiles) } } }.awaitAll()
            }
            database.withTransaction {
                for ((item, summary) in read) {
                    if (summary == null) continue
                    dao.setExif(item.id, summary.camera, summary.lens, summary.iso, summary.focalLength)
                    filled++
                }
            }
        }
        filled
    }

    private fun summaryOf(item: MediaItemEntity, canReadPhoneFiles: Boolean): ExifSummary? = when {
        // A video carries no Exif.
        item.kind == MediaKind.VIDEO -> ExifSummary.NONE
        item.origin == MediaOrigin.PHONE && !canReadPhoneFiles -> null
        else -> ExifSummary.read(File(item.path))
    }
}
