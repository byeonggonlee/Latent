package com.byeonggon.latent.data

import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.MediaDao
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.splitMediaExtension
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.location.MediaMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Where deleted photos go first, as in a gallery app: out of the library and out of other apps'
 * sight, but not gone. They can be put back, or deleted for good; after the days set in the settings ([KEPT_DAYS] at first) that happens by
 * itself. Along with a file go the original kept for an edited JPEG and a raw file's location sidecar.
 */
class Trash(
    private val storage: LibraryStorage,
    private val mediaDao: MediaDao,
    private val cameraZone: suspend () -> ZoneId = { ZoneId.systemDefault() },
) {
    val items: Flow<List<MediaItemEntity>> = mediaDao.observeTrash()

    /**
     * Lists intact bundles whose database was lost. Files stay where they are until restore/empty is chosen.
     * Original paths, delete times and database-only edits cannot be recovered; use the usual capture-date
     * folder as the restore destination and never expire these photos automatically.
     */
    suspend fun rediscover(): Int = withContext(Dispatchers.IO) {
        if (!storage.isAvailable()) return@withContext 0
        storage.moving.withLock {
            val knownFolders = mediaDao.all().mapTo(HashSet()) { File(it.path).parentFile?.path }
            val root = storage.trashRoot
            if (root.canonicalFile != File(storage.root.canonicalFile, ".trash")) return@withLock 0
            val zone = cameraZone()
            var added = 0
            for (entry in storage.children(root).orEmpty()) {
                coroutineContext.ensureActive()
                val folder = entry.file
                if (!entry.isDirectory || !BUNDLE_NAME.matches(folder.name) || folder.path in knownFolders) continue
                // Do not follow a symlink into another folder, even one elsewhere in the library.
                if (folder.canonicalFile != File(root.canonicalFile, folder.name)) continue
                val children = storage.children(folder) ?: continue
                val candidates = children.filter {
                    !it.isDirectory && !it.file.name.startsWith(".") && !it.file.name.startsWith(ORIGINAL) && MediaKind.fromFileName(it.file.name) != null
                }
                val photo = candidates.singleOrNull()?.file ?: continue
                // Let makeOwn finish or restart its own interrupted copies, but never register one as a photo.
                val allowed = setOf(
                    photo.name, ORIGINAL + photo.name, sidecarOf(photo).name,
                    ".${photo.nameWithoutExtension}-copying.${photo.extension}",
                    ".${photo.nameWithoutExtension}-copied.${photo.extension}",
                )
                if (children.any { it.isDirectory || it.file.name !in allowed || it.file.canonicalFile != File(folder.canonicalFile, it.file.name) }) continue
                // Existing takeover copies beside the file and swaps only a complete copy into its place.
                if (!storage.makeOwn(photo)) continue
                val kind = MediaKind.fromFile(photo) ?: continue
                val metadata = MediaMetadataReader.read(photo, kind, zone)
                val now = System.currentTimeMillis()
                val capturedAt = metadata.capturedAt ?: photo.lastModified().takeIf { it > 0 } ?: now
                val target = storage.datedFile(photo.name, capturedAt)
                val place = metadata.location
                mediaDao.insertUnlessKnown(MediaItemEntity(
                    path = photo.path,
                    fileName = photo.name,
                    kind = kind,
                    sizeBytes = photo.length(),
                    capturedAt = capturedAt,
                    importedAt = now,
                    latitude = place?.latitude,
                    longitude = place?.longitude,
                    altitude = place?.altitude,
                    geoSource = if (place == null) GeoSource.NONE else GeoSource.CAMERA,
                    edit = if (kind == MediaKind.PHOTO && children.any { it.file.name == ORIGINAL + photo.name }) PhotoEdit().toJson() else null,
                    trashedAt = RECOVERED_AT,
                    trashedFrom = target.path,
                ))
                knownFolders.add(folder.path)
                added++
            }
            added
        }
    }

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
        storage.moving.withLock {
            items.forEach { item ->
                mediaDao.byId(item.id)?.takeIf { it.trashedAt != null }?.let { restore(it) }
            }
        }
    }

    private suspend fun restore(item: MediaItemEntity) {
        val from = item.trashedFrom ?: return
        val kept = File(item.path)
        val folder = kept.parentFile ?: return
        val target = storage.freeRestoreFileLike(File(from).also { it.parentFile?.mkdirs() })
        // Once files start moving, finish the DB update or rollback before observing cancellation.
        withContext(NonCancellable) {
            val moved = mutableListOf<Pair<File, File>>()
            fun move(source: File, destination: File) {
                storage.move(source, destination)
                moved += source to destination
            }
            try {
                move(kept, target)
                sidecarOf(kept).takeIf(storage::exists)?.let { move(it, sidecarOf(target)) }
                File(folder, ORIGINAL + kept.name).takeIf(storage::exists)?.let { original ->
                    storage.originalOf(target).also { it.parentFile?.mkdirs() }.let { move(original, it) }
                }
                mediaDao.setPlace(item.id, target.path, null, null)
            } catch (failure: Exception) {
                for ((source, destination) in moved.asReversed()) {
                    try {
                        // A concurrent outside writer must not lose its file to rollback either.
                        if (storage.exists(source)) throw IOException("Cannot roll back ${source.name}: its place is occupied")
                        storage.move(destination, source)
                    } catch (rollbackFailure: Exception) {
                        failure.addSuppressed(rollbackFailure)
                    }
                }
                throw failure
            }
        }
        // A file added after discovery is not part of this restore. Only remove an empty directory.
        folder.delete()
        storage.publish(target)
    }

    /**
     * Deletes files in the trash for good. What they were imported as is still remembered, so a sync
     * does not bring them back.
     */
    suspend fun deleteForever(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        storage.moving.withLock {
            for (item in items) {
                val current = mediaDao.byId(item.id)?.takeIf { it.trashedAt != null } ?: continue
                val folder = File(current.path).parentFile ?: throw IOException("Cannot find the trash folder")
                if (!storage.delete(folder)) throw IOException("Cannot empty ${current.fileName}")
                mediaDao.deleteByIds(listOf(current.id))
            }
        }
    }

    /** Deletes what has been in the trash for longer than [keptDays]. */
    suspend fun emptyExpired(now: Long = System.currentTimeMillis(), keptDays: Int = KEPT_DAYS) {
        // While storage cannot be reached the files cannot be deleted either; their entries wait.
        if (!storage.isAvailable()) return
        for (item in mediaDao.trashedBefore(now - TimeUnit.DAYS.toMillis(keptDays.toLong()))) {
            coroutineContext.ensureActive()
            try {
                deleteForever(listOf(item))
            } catch (_: IOException) {
                // Background expiry retries next refresh. Explicit delete/empty still reports the failure.
            }
        }
    }

    private fun sidecarOf(file: File) = File(file.parentFile, "${splitMediaExtension(file.name).first}.xmp")

    companion object {
        /** How long, in days, photos stay unless the settings say otherwise. */
        const val KEPT_DAYS = 30
        private const val ORIGINAL = "original-"
        // A sentinel, not a deletion date: old files must survive all automatic expiration checks.
        private const val RECOVERED_AT = Long.MAX_VALUE
        private val BUNDLE_NAME = Regex("[1-9][0-9]*(?:-[1-9][0-9]*)?")
    }
}
