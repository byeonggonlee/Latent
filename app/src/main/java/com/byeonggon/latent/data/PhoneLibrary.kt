package com.byeonggon.latent.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.byeonggon.latent.BuildConfig
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.location.MediaMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The photos and videos that other apps keep on the phone, such as its camera's, listed in the library
 * without being copied or changed. What is done to them is kept as settings. The person chooses the
 * folders; the library's own folder is never among them.
 */
class PhoneLibrary(private val context: Context, private val database: AppDatabase) {
    /** A folder of the phone with photos or videos in it: [path] as MediaStore names it, such as "DCIM/Camera/". */
    data class Folder(val path: String, val count: Int)

    private class Row(val path: String, val folder: String, val name: String, val kind: MediaKind, val size: Long, val takenAt: Long)

    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** The folders that hold photos or videos, the most filled first, leaving out the library's own and phone raw files. */
    suspend fun folders(): List<Folder> = withContext(Dispatchers.IO) {
        rows().groupingBy { it.folder }.eachCount().map { (path, count) -> Folder(path, count) }.sortedByDescending { it.count }
    }

    /**
     * Lists the photos and videos in [chosen] folders that the library does not have yet, and drops
     * what is gone from the phone, and what lay in folders no longer chosen unless it was edited.
     * Returns how many were added.
     */
    suspend fun refresh(chosen: Set<String>): Int = withContext(Dispatchers.IO) { listFrom(chosen) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val readers = Dispatchers.IO.limitedParallelism(READERS)

    private suspend fun listFrom(chosen: Set<String>): Int = coroutineScope {
        val dao = database.mediaDao()
        val known = dao.fromPhone()
        val gone = known.filter { !File(it.path).exists() || (folderOf(it.path) !in chosen && it.edit == null) }
        gone.map { it.id }.chunked(500).forEach { dao.deleteByIds(it) }
        if (chosen.isEmpty()) return@coroutineScope 0

        val have = known.mapTo(HashSet()) { it.path }
        val fresh = rows().filter { it.folder in chosen && it.path !in have }
        val now = System.currentTimeMillis()
        for (batch in fresh.chunked(BATCH)) {
            ensureActive()
            // Reading the place out of each file is what takes the time; a few at once make the most of the storage.
            val items = batch.map { row -> async(readers) { row to MediaMetadataReader.read(File(row.path), row.kind) } }.awaitAll().map { (row, metadata) ->
                MediaItemEntity(
                    path = row.path,
                    fileName = row.name,
                    kind = row.kind,
                    sizeBytes = row.size,
                    capturedAt = row.takenAt.takeIf { it > 0 } ?: metadata.capturedAt ?: File(row.path).lastModified(),
                    importedAt = now,
                    latitude = metadata.location?.latitude,
                    longitude = metadata.location?.longitude,
                    altitude = metadata.location?.altitude,
                    geoSource = if (metadata.location != null) GeoSource.CAMERA else GeoSource.NONE,
                    origin = MediaOrigin.PHONE,
                )
            }
            database.withTransaction { items.forEach { dao.insertUnlessKnown(it) } }
        }
        fresh.size
    }

    /** The address by which the system knows the file at [path], for asking it to delete the file; null if it does not know it. */
    fun uriOf(path: String): Uri? =
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null)?.use {
            if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null
        }

    private fun rows(): List<Row> {
        val result = ArrayList<Row>()
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val types = "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
        resolver.query(collection, projection, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($types)", null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0) ?: continue
                val folder = groupOf(cursor.getString(1) ?: continue)
                val name = cursor.getString(2) ?: continue
                val kind = MediaKind.fromFileName(name) ?: continue
                if (LIBRARY_FOLDERS.any(folder::startsWith)) continue
                result += Row(path, folder, name, kind, cursor.getLong(3), cursor.getLong(4))
            }
        }
        return result
    }

    companion object {
        private const val BATCH = 200
        private const val READERS = 4

        /** The library's own folder as MediaStore names it. */
        const val LIBRARY_FOLDER = "DCIM/${BuildConfig.LIBRARY_FOLDER}/"

        /** The libraries of both builds, the installed app's and the debug one's: neither lists the other's as the phone's. */
        val LIBRARY_FOLDERS = listOf("DCIM/Latent/", "DCIM/Latent Debug/")

        /** The phone's own camera. */
        const val CAMERA_FOLDER = "DCIM/Camera/"

        private val VOLUME_PREFIX = Regex("^/storage/(emulated/\\d+|[^/]+)/")

        /**
         * The folder of a file by the path, as MediaStore names folders: "DCIM/Camera/" for ".../0/DCIM/Camera/a.jpg".
         * Folders of years and days are counted with the folder they are kept in: see [groupOf].
         */
        fun folderOf(path: String): String {
            val folder = relativePathOf(path).substringBeforeLast('/', "")
            return groupOf(if (folder.isEmpty()) "" else "$folder/")
        }

        /**
         * Where a file is on its storage volume, as MediaStore's relative path and name put it: "DCIM/Camera/a.jpg"
         * for ".../0/DCIM/Camera/a.jpg". The same on every phone and for every user of one.
         */
        fun relativePathOf(path: String): String {
            val internal = Environment.getExternalStorageDirectory().path + "/"
            return if (path.startsWith(internal)) path.removePrefix(internal) else path.replace(VOLUME_PREFIX, "")
        }

        private val DATED = Regex("^\\d{4}(-\\d{2}(-\\d{2})?)?$")

        /**
         * A folder as the person chooses it: one that is sorted into folders of years and days, as a
         * library is, is a single choice, such as "DCIM/CameraManager/" for ".../2026/2026-10-08/".
         */
        fun groupOf(folder: String): String {
            val parts = folder.trimEnd('/').split('/')
            val dated = parts.indexOfFirst { DATED.matches(it) }
            if (dated < 0) return folder
            return parts.take(dated).joinToString("/") { "$it/" }.replace("//", "/")
        }
    }
}
