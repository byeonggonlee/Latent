package com.byeonggon.cameramanager.data

import androidx.room.withTransaction
import com.byeonggon.cameramanager.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps what the database knows about the library and past imports in line with the files on the phone. */
class LibraryIndex(private val storage: LibraryStorage, private val database: AppDatabase) {
    /** How many imported camera files are no longer in the library. A sync skips them. */
    val deletedFromPhone: Flow<Int> = database.syncRecordDao().observeCountWithoutLibraryFile()

    /** Drops library entries whose files were removed outside the app, e.g. from a gallery app. */
    suspend fun forgetMissingFiles() = withContext(Dispatchers.IO) {
        // While storage is unavailable every file looks missing, which must not empty the library.
        if (!storage.isAvailable()) return@withContext
        val dao = database.mediaDao()
        // A file the app cannot see by its path may still be there; the granted folder sees everything.
        val missing = dao.all().filterNot { storage.exists(File(it.path)) }
        // The hidden original of an edited photo would otherwise stay behind, taking up space unseen.
        missing.forEach { storage.delete(storage.originalOf(File(it.path))) }
        missing.map { it.id }.chunked(500).forEach { dao.deleteByIds(it) }
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
