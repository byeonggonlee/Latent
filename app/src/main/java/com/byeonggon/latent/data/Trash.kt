package com.byeonggon.latent.data

import com.byeonggon.latent.data.db.MediaDao
import com.byeonggon.latent.data.db.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Where deleted photos go first, as in a gallery app: out of the library and out of other apps'
 * sight, but not gone. They can be put back, or deleted for good; after the days set in the settings ([KEPT_DAYS] at first) that happens by
 * itself. Along with a file go the original kept for an edited JPEG and a raw file's location sidecar.
 */
class Trash(private val storage: LibraryStorage, private val mediaDao: MediaDao) {
    val items: Flow<List<MediaItemEntity>> = mediaDao.observeTrash()

    @Throws(IOException::class)
    suspend fun throwAway(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        for (item in items) {
            if (item.trashedAt != null) continue
            val file = File(item.path)
            // Asked before anything moves: a photo whose original cannot go along stays where it is.
            val original = storage.keptOriginal(file)
            val folder = storage.trashFolder(item.id)
            val kept = File(folder, file.name)
            storage.move(file, kept)
            sidecarOf(file).takeIf(storage::exists)?.let { storage.move(it, sidecarOf(kept)) }
            original?.let { storage.move(it, File(folder, ORIGINAL + file.name)) }
            mediaDao.setPlace(item.id, kept.path, System.currentTimeMillis(), item.path)
            // Gallery apps see the file gone.
            storage.publish(file)
        }
    }

    /** Puts files back where they were in the library; beside it under another name if that place is taken now. */
    @Throws(IOException::class)
    suspend fun restore(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        for (item in items) {
            val from = item.trashedFrom ?: continue
            val kept = File(item.path)
            val folder = kept.parentFile ?: continue
            val target = storage.freeFileLike(File(from).also { it.parentFile?.mkdirs() })
            storage.move(kept, target)
            sidecarOf(kept).takeIf(storage::exists)?.let { storage.move(it, sidecarOf(target)) }
            File(folder, ORIGINAL + kept.name).takeIf(storage::exists)?.let { original ->
                storage.originalOf(target).also { it.parentFile?.mkdirs() }.let { storage.move(original, it) }
            }
            storage.delete(folder)
            mediaDao.setPlace(item.id, target.path, null, null)
            storage.publish(target)
        }
    }

    /**
     * Deletes files in the trash for good. What they were imported as is still remembered, so a sync
     * does not bring them back.
     */
    suspend fun deleteForever(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        for (item in items) {
            if (item.trashedAt == null) continue
            File(item.path).parentFile?.let(storage::delete)
        }
        items.filter { it.trashedAt != null }.map { it.id }.chunked(500).forEach { mediaDao.deleteByIds(it) }
    }

    /** Deletes what has been in the trash for longer than [keptDays]. */
    suspend fun emptyExpired(now: Long = System.currentTimeMillis(), keptDays: Int = KEPT_DAYS) {
        // While storage cannot be reached the files cannot be deleted either; their entries wait.
        if (!storage.isAvailable()) return
        deleteForever(mediaDao.trashedBefore(now - TimeUnit.DAYS.toMillis(keptDays.toLong())))
    }

    private fun sidecarOf(file: File) = File(file.parentFile, "${file.nameWithoutExtension}.xmp")

    companion object {
        /** How long, in days, photos stay unless the settings say otherwise. */
        const val KEPT_DAYS = 30
        private const val ORIGINAL = "original-"
    }
}
