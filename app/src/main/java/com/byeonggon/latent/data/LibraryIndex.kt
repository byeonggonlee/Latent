package com.byeonggon.latent.data

import androidx.room.withTransaction
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.location.GeoPoint
import com.byeonggon.latent.location.MediaMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** Keeps what the database knows about the library and past imports in line with the files on the phone. */
class LibraryIndex(
    private val storage: LibraryStorage,
    private val database: AppDatabase,
    /** The zone to read the time of a photo in when it does not say which one it is: the camera's. */
    private val cameraZone: suspend () -> ZoneId = { ZoneId.systemDefault() },
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
        // Photos listed from the phone are not the library's: PhoneLibrary looks after those.
        val missing = dao.all().filter { it.origin == MediaOrigin.LIBRARY }.filterNot { it.id in saving || storage.exists(File(it.path)) }
        // The hidden original of an edited photo would otherwise stay behind, taking up space unseen.
        missing.forEach { storage.delete(storage.originalOf(File(it.path))) }
        missing.map { it.id }.chunked(500).forEach { dao.deleteByIds(it) }
    }

    /**
     * Reads the time of each library photo again, in [zone] where the photo does not say which zone it
     * was taken in; for after the camera's time zone has been set or corrected. Returns how many changed.
     */
    suspend fun rereadCaptureTimes(zone: ZoneId): Int = withContext(Dispatchers.IO) {
        if (!storage.isAvailable()) return@withContext 0
        val dao = database.mediaDao()
        var changed = 0
        // Videos carry their time in UTC already.
        for (item in dao.all().filter { it.origin == MediaOrigin.LIBRARY && it.kind != MediaKind.VIDEO && it.trashedAt == null }) {
            ensureActive()
            val file = File(item.path)
            if (!storage.exists(file)) continue
            val time = MediaMetadataReader.read(file, item.kind, zone).capturedAt ?: continue
            if (time != item.capturedAt) {
                dao.setCapturedAt(item.id, time)
                changed++
            }
        }
        changed
    }

    /**
     * Adds the photos and videos in the library's folder that the library does not know yet: copied
     * there from another phone, say, or left behind by an earlier installation. The time and place of
     * each are read from the file; a raw file without a place takes its JPEG's. Returns how many.
     */
    suspend fun adoptUnknownFiles(): Int = withContext(Dispatchers.IO) {
        if (!storage.isAvailable()) return@withContext 0
        val dao = database.mediaDao()
        val zone = cameraZone()
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
                // An edited JPEG whose original came along can be put back; how it was edited is not known. An
                // original that another installation kept is made the app's own, for the editor to read; where
                // that fails, it is still there to be put back.
                val original = storage.originalOf(file)
                val edited = kind == MediaKind.PHOTO && storage.exists(original)
                if (edited) storage.makeOwn(original)
                val metadata = MediaMetadataReader.read(file, kind, zone)
                val shot = shotOf(file)
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
                    edit = if (edited) PhotoEdit().toJson() else null,
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
