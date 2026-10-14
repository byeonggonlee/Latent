package com.byeonggon.latent.data

import androidx.room.withTransaction
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.location.GeoPoint
import com.byeonggon.latent.location.MediaMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps what the database knows about the library and past imports in line with the files on the phone. */
class LibraryIndex(
    private val storage: LibraryStorage,
    private val database: AppDatabase,
    /** The entries whose edits are being saved: saving moves a photo aside for a moment. */
    private val beingSaved: () -> Set<Long> = { emptySet() },
) {
    private companion object {
        const val BATCH = 200
    }

    /** How many imported camera files are no longer in the library. A sync skips them. */
    val deletedFromPhone: Flow<Int> = database.syncRecordDao().observeCountWithoutLibraryFile()

    /** Drops library entries whose files were removed outside the app, e.g. from a gallery app. */
    suspend fun forgetMissingFiles() = withContext(Dispatchers.IO) {
        // While storage is unavailable every file looks missing, which must not empty the library.
        if (!storage.isAvailable()) return@withContext
        val dao = database.mediaDao()
        // A file the app cannot see by its path may still be there; the granted folder sees everything.
        val saving = beingSaved()
        val missing = dao.all().filterNot { it.id in saving || storage.exists(File(it.path)) }
        // The hidden original of an edited photo would otherwise stay behind, taking up space unseen.
        missing.forEach { storage.delete(storage.originalOf(File(it.path))) }
        missing.map { it.id }.chunked(500).forEach { dao.deleteByIds(it) }
    }

    /**
     * Adds the photos and videos in the library's folder that the library does not know yet: copied
     * there from another phone, say, or left behind by an earlier installation. The time and place of
     * each are read from the file; a raw file without a place takes its JPEG's. Returns how many.
     */
    suspend fun adoptUnknownFiles(): Int = withContext(Dispatchers.IO) {
        if (!storage.isAvailable()) return@withContext 0
        val dao = database.mediaDao()
        val known = dao.all().mapTo(HashSet()) { it.path }
        val found = storage.root.walkTopDown()
            // Staging, kept originals and the trash live in folders whose names start with a dot.
            .onEnter { it == storage.root || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") && it.absolutePath !in known }
            .mapNotNull { file -> MediaKind.fromFileName(file.name)?.let { file to it } }
            // JPEGs first, so that their raw files can take their place from them.
            .sortedBy { (_, kind) -> kind != MediaKind.PHOTO }
            .toList()
        val places = HashMap<String, GeoPoint>()
        val now = System.currentTimeMillis()
        for (batch in found.chunked(BATCH)) {
            ensureActive()
            val items = batch.map { (file, kind) ->
                val metadata = MediaMetadataReader.read(file, kind)
                val shot = File(file.parentFile, file.nameWithoutExtension.lowercase()).path
                val place = metadata.location ?: places[shot].takeIf { kind == MediaKind.RAW }
                if (kind == MediaKind.PHOTO && metadata.location != null) places[shot] = metadata.location
                MediaItemEntity(
                    path = file.absolutePath,
                    fileName = file.name,
                    kind = kind,
                    sizeBytes = file.length(),
                    capturedAt = metadata.capturedAt ?: file.lastModified(),
                    importedAt = now,
                    latitude = place?.latitude,
                    longitude = place?.longitude,
                    altitude = place?.altitude,
                    geoSource = if (place != null) GeoSource.CAMERA else GeoSource.NONE,
                    // An edited JPEG whose original came along can be put back; how it was edited is not known.
                    edit = if (kind == MediaKind.PHOTO && storage.exists(storage.originalOf(file))) PhotoEdit().toJson() else null,
                )
            }
            database.withTransaction { items.forEach { dao.insertUnlessKnown(it) } }
        }
        found.size
    }

    /**
     * Forgets that the files deleted from the phone were ever imported, so that a sync brings them
     * back. Files still in the library keep their history and are not imported twice.
     * Returns how many files were forgotten.
     */
    suspend fun allowReimportOfDeletedFiles(): Int {
        forgetMissingFiles()
        return database.withTransaction {
            // Folders remembered as fully imported would be skipped without ever looking for these files.
            database.syncedFolderDao().deleteAll()
            database.syncRecordDao().deleteWithoutLibraryFile()
        }
    }
}
