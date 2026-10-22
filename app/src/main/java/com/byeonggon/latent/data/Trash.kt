package com.byeonggon.latent.data

import com.byeonggon.latent.data.db.MediaDao
import com.byeonggon.latent.data.db.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What emptying the trash freed: the [photos] deleted and the [bytes] their files took. */
data class TrashFreed(val photos: Int, val bytes: Long)

/**
 * Where deleted photos go first, as in a gallery app: out of the library and out of other apps'
 * sight, but not gone. They can be put back, or deleted for good; after the days set in the settings ([KEPT_DAYS] at first) that happens by
 * itself. Along with a file go the original kept for an edited JPEG and a raw file's location sidecar.
 */
class Trash(private val storage: LibraryStorage, private val mediaDao: MediaDao) {
    val items: Flow<List<MediaItemEntity>> = mediaDao.observeTrash()

    @Throws(IOException::class)
    suspend fun throwAway(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        // Until its entry says where it went, a file on its way looks deleted to the library's check of its folder.
        storage.moving.withLock { items.forEach { throwAway(it) } }
    }

    private suspend fun throwAway(item: MediaItemEntity) {
        if (item.trashedAt != null) return
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

    /** Puts files back where they were in the library; beside it under another name if that place is taken now. */
    @Throws(IOException::class)
    suspend fun restore(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        storage.moving.withLock { items.forEach { restore(it) } }
    }

    private suspend fun restore(item: MediaItemEntity) {
        val from = item.trashedFrom ?: return
        val kept = File(item.path)
        val folder = kept.parentFile ?: return
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

    /**
     * Deletes files in the trash for good. What they were imported as is still remembered, so a sync
     * does not bring them back.
     */
    suspend fun deleteForever(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        storage.moving.withLock {
            for (item in items) {
                if (item.trashedAt == null) continue
                File(item.path).parentFile?.let(storage::delete)
            }
        }
        items.filter { it.trashedAt != null }.map { it.id }.chunked(500).forEach { mediaDao.deleteByIds(it) }
    }

    /** Deletes what has been in the trash for longer than [keptDays]. */
    suspend fun emptyExpired(now: Long = System.currentTimeMillis(), keptDays: Int = KEPT_DAYS) {
        emptyExpiredCounting(now, keptDays)
    }

    /** Like [emptyExpired], and says how many photos it deleted and how many bytes that freed. */
    suspend fun emptyExpiredCounting(
        now: Long = System.currentTimeMillis(),
        keptDays: Int = KEPT_DAYS,
    ): TrashFreed = withContext(Dispatchers.IO) {
        // While storage cannot be reached the files cannot be deleted either; their entries wait.
        if (!storage.isAvailable()) return@withContext TrashFreed(0, 0L)
        val expired = mediaDao.trashedBefore(now - TimeUnit.DAYS.toMillis(keptDays.toLong()))
        val going = expired.filter { item ->
            try {
                deleteExtras(item)
            } catch (e: IOException) {
                // The file stays and its entry waits for the next pass.
                false
            }
        }
        going.map { it.id }.chunked(500).forEach { mediaDao.deleteByIds(it) }
        var bytes = 0L
        for (item in going) {
            try {
                bytes += deleteFolder(item)
            } catch (e: Exception) {
                // On to the next photo.
            }
        }
        TrashFreed(going.size, bytes)
    }

    private fun deleteExtras(item: MediaItemEntity): Boolean {
        val kept = File(item.path)
        return storage.delete(sidecarOf(kept)) && storage.delete(File(kept.parentFile, ORIGINAL + kept.name))
    }

    private suspend fun deleteFolder(item: MediaItemEntity): Long = withContext(Dispatchers.IO) {
        val kept = File(item.path)
        kept.parentFile?.let(storage::delete)
        kept.length()
    }

    private fun sidecarOf(file: File) = File(file.parentFile, "${file.nameWithoutExtension}.xmp")

    companion object {
        /** How long, in days, photos stay unless the settings say otherwise. */
        const val KEPT_DAYS = 30
        private const val ORIGINAL = "original-"
        private const val MILLIS_PER_DAY = 86_400_000

        /**
         * How many whole days [item] has left in the trash, counting a started day as a whole one; 0 once its
         * time is up, and null for a photo that is not in the trash.
         */
        fun daysLeft(item: MediaItemEntity, keptDays: Int = KEPT_DAYS, now: Long = System.currentTimeMillis()): Int? {
            val trashedAt = item.trashedAt ?: return null
            val left = trashedAt + keptDays * MILLIS_PER_DAY - now
            return if (left <= 0) 0 else ((left + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY).toInt()
        }
    }
}
