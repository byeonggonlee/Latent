package com.byeonggon.cameramanager.data

import com.byeonggon.cameramanager.data.db.MediaDao
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Where deleted photos go first, as in a gallery app: out of the library and out of other apps'
 * sight, but not gone. They can be put back, or deleted for good; after [KEPT_DAYS] that happens by
 * itself. Along with a file go the original kept for an edited JPEG and a raw file's location sidecar.
 */
class Trash(private val storage: LibraryStorage, private val mediaDao: MediaDao) {
    val items: Flow<List<MediaItemEntity>> = mediaDao.observeTrash()

    @Throws(IOException::class)
    suspend fun throwAway(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        for (item in items) {
            if (item.trashedAt != null) continue
            val file = File(item.path)
            val folder = storage.trashFolder(item.id)
            val kept = File(folder, file.name)
            move(file, kept)
            sidecarOf(file).takeIf { it.exists() }?.let { move(it, sidecarOf(kept)) }
            storage.originalOf(file).takeIf { it.exists() }?.let { move(it, File(folder, ORIGINAL + file.name)) }
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
            move(kept, target)
            sidecarOf(kept).takeIf { it.exists() }?.let { move(it, sidecarOf(target)) }
            File(folder, ORIGINAL + kept.name).takeIf { it.exists() }?.let { original ->
                storage.originalOf(target).also { it.parentFile?.mkdirs() }.let { move(original, it) }
            }
            folder.delete()
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
            File(item.path).parentFile?.deleteRecursively()
        }
        items.filter { it.trashedAt != null }.map { it.id }.chunked(500).forEach { mediaDao.deleteByIds(it) }
    }

    /** Deletes what has been in the trash for longer than [KEPT_DAYS]. */
    suspend fun emptyExpired(now: Long = System.currentTimeMillis()) {
        // While storage cannot be reached the files cannot be deleted either; their entries wait.
        if (!storage.isAvailable()) return
        deleteForever(mediaDao.trashedBefore(now - TimeUnit.DAYS.toMillis(KEPT_DAYS)))
    }

    private fun sidecarOf(file: File) = File(file.parentFile, "${file.nameWithoutExtension}.xmp")

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("Cannot move ${from.name}")
    }

    companion object {
        const val KEPT_DAYS = 30L
        private const val ORIGINAL = "original-"
    }
}
