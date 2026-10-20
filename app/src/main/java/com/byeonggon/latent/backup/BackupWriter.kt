package com.byeonggon.latent.backup

import com.byeonggon.latent.data.PhoneLibrary
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.enhance.LutStore
import com.byeonggon.latent.enhance.MaskStore
import com.byeonggon.latent.enhance.PhotoEdit
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The parts of a backup and of a restore, in the order they are done, to say how far it has come. */
enum class BackupStep { CHECKING, FINDING, PHOTOS, ALBUMS, VERSIONS, FILTERS, LUTS, MASKS, CAMERA, TIMELINE, SETTINGS }

fun interface BackupProgress {
    /** [done] of [total] in [step]; [total] is 0 where it is not known. */
    fun report(step: BackupStep, done: Int, total: Int)

    companion object {
        val NONE = BackupProgress { _, _, _ -> }
    }
}

/**
 * Writes what lives only in the app's database and files into one backup file: never the photos, their
 * thumbnails or anything kept in a cache. The database is read a page at a time, so that a long location
 * history goes through in little memory.
 */
class BackupWriter(
    private val database: AppDatabase,
    private val settings: SettingsRepository,
    libraryRoot: File,
    private val app: AppIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
    phoneRelativePath: (String) -> String = PhoneLibrary::relativePathOf,
) {
    private val paths = MediaPaths(libraryRoot, phoneRelativePath)

    /** Writes the backup to [output], which is left open. Returns what it holds. */
    suspend fun write(output: OutputStream, progress: BackupProgress = BackupProgress.NONE): BackupCounts = withContext(Dispatchers.IO) {
        val dao = database.backupDao()
        val zip = ZipLines(output)
        val luts = LinkedHashSet<String>()
        val masks = LinkedHashSet<String>()
        fun refer(edit: String?) {
            val parsed = PhotoEdit.fromJson(edit) ?: return
            parsed.lut?.id?.let(luts::add)
            parsed.locals.mapNotNullTo(masks) { it.mask.detection?.raster }
        }

        zip.begin(BackupFormat.SETTINGS)
        zip.text(BackupSettings.toJson(settings.settings.first()).toString())
        zip.end()

        val inAlbums = dao.mediaInAlbums().toHashSet()
        val withVersions = dao.mediaWithVersions().toHashSet()
        val written = HashSet<Long>()
        val mediaTotal = dao.countMedia()
        var seen = 0
        zip.begin(BackupFormat.MEDIA)
        var after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.mediaAfter(after, PAGE)
            if (page.isEmpty()) break
            for (item in page) {
                if (!worthKeeping(item, inAlbums, withVersions)) continue
                zip.line(MediaRecord.of(item, paths.of(item)).toJson())
                written += item.id
                refer(item.edit)
            }
            after = page.last().id
            seen += page.size
            progress.report(BackupStep.PHOTOS, seen, mediaTotal)
        }
        zip.end()

        val albums = dao.albums()
        progress.report(BackupStep.ALBUMS, 0, albums.size)
        zip.begin(BackupFormat.ALBUMS)
        albums.forEach { zip.line(AlbumRecord.of(it)) }
        zip.end()
        var albumItems = 0
        zip.begin(BackupFormat.ALBUM_ITEMS)
        var afterAlbum = Long.MIN_VALUE
        var afterMedia = Long.MIN_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.albumItemsAfter(afterAlbum, afterMedia, PAGE)
            if (page.isEmpty()) break
            for (item in page) {
                if (item.mediaId !in written) continue
                zip.line(AlbumItemRecord.of(item))
                albumItems++
            }
            afterAlbum = page.last().albumId
            afterMedia = page.last().mediaId
        }
        zip.end()

        var versions = 0
        zip.begin(BackupFormat.VERSIONS)
        after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.versionsAfter(after, PAGE)
            if (page.isEmpty()) break
            for (version in page) {
                if (version.mediaId !in written) continue
                zip.line(VersionRecord.of(version))
                refer(version.edit)
                versions++
            }
            after = page.last().id
            progress.report(BackupStep.VERSIONS, versions, 0)
        }
        zip.end()

        val filters = dao.filters()
        progress.report(BackupStep.FILTERS, 0, filters.size)
        zip.begin(BackupFormat.FILTERS)
        for (filter in filters) {
            zip.line(FilterRecord.of(filter))
            refer(filter.look)
        }
        zip.end()

        val lutList = dao.luts()
        zip.begin(BackupFormat.LUTS)
        for (lut in lutList) {
            zip.line(LutRecord.of(lut))
            luts += lut.hash
        }
        zip.end()

        val syncTotal = dao.countSyncRecords()
        var syncRecords = 0
        zip.begin(BackupFormat.SYNC_RECORDS)
        var afterKey = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.syncRecordsAfter(afterKey, PAGE)
            if (page.isEmpty()) break
            page.forEach { zip.line(SyncRecordRecord.of(it)) }
            syncRecords += page.size
            afterKey = page.last().sourceKey
            progress.report(BackupStep.CAMERA, syncRecords, syncTotal)
        }
        zip.end()
        val folders = dao.syncedFolders()
        zip.begin(BackupFormat.SYNCED_FOLDERS)
        folders.forEach { zip.line(SyncedFolderRecord.of(it)) }
        zip.end()

        val locationTotal = dao.countLocations()
        var locations = 0
        zip.begin(BackupFormat.LOCATIONS)
        after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.locationsAfter(after, LOCATION_PAGE)
            if (page.isEmpty()) break
            page.forEach { zip.line(LocationRecord.of(it)) }
            locations += page.size
            after = page.last().id
            progress.report(BackupStep.TIMELINE, locations, locationTotal)
        }
        zip.end()

        // What edits and filters name, and what is on the list, as long as its file is there.
        var lutFiles = 0
        for (id in luts) {
            currentCoroutineContext().ensureActive()
            val file = LutStore.fileOf(id)?.takeIf { it.isFile } ?: continue
            zip.copy(BackupFormat.lutEntry(id), file)
            progress.report(BackupStep.LUTS, ++lutFiles, luts.size)
        }
        var maskFiles = 0
        for (id in masks) {
            currentCoroutineContext().ensureActive()
            val file = MaskStore.fileOf(id)?.takeIf { it.isFile } ?: continue
            zip.copy(BackupFormat.maskEntry(id), file)
            progress.report(BackupStep.MASKS, ++maskFiles, masks.size)
        }

        val counts = BackupCounts(
            media = written.size,
            albums = albums.size,
            albumItems = albumItems,
            versions = versions,
            filters = filters.size,
            luts = lutList.size,
            lutFiles = lutFiles,
            masks = maskFiles,
            syncRecords = syncRecords,
            syncedFolders = folders.size,
            locations = locations,
        )
        // Written last, with what was really written: a file with a manifest is a whole one.
        zip.begin(BackupFormat.MANIFEST)
        zip.text(BackupManifest(BackupFormat.VERSION, Instant.ofEpochMilli(clock()), app, counts).toJson().toString())
        zip.end()
        zip.finish()
        counts
    }

    /** Whether a photo has anything a restore could give back; the rest the app finds again in the files themselves. */
    private fun worthKeeping(item: MediaItemEntity, inAlbums: Set<Long>, withVersions: Set<Long>): Boolean =
        item.rating != 0 || item.flag != 0 || item.edit != null || item.geoSource == GeoSource.TRACK || item.sourceKey != null ||
            item.id in inAlbums || item.id in withVersions

    /** A zip of text entries, written one line at a time. */
    private class ZipLines(output: OutputStream) {
        private val zip = ZipOutputStream(output.buffered(BUFFER))
        private val text = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8), BUFFER)

        fun begin(name: String) = zip.putNextEntry(ZipEntry(name))

        fun line(json: JsonObject) {
            text.write(json.toString())
            text.write('\n'.code)
        }

        fun text(value: String) = text.write(value)

        fun end() {
            text.flush()
            zip.closeEntry()
        }

        fun copy(name: String, file: File) {
            begin(name)
            file.inputStream().use { it.copyTo(zip) }
            end()
        }

        fun finish() {
            text.flush()
            zip.finish()
            zip.flush()
        }
    }

    private companion object {
        const val PAGE = 500
        const val LOCATION_PAGE = 2_000
        const val BUFFER = 64 * 1024
    }
}
