package com.byeonggon.latent.backup

import androidx.room.withTransaction
import com.byeonggon.latent.data.PhoneLibrary
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.db.AlbumEntity
import com.byeonggon.latent.data.db.AlbumItemEntity
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.EditVersionEntity
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.data.db.SyncRecordEntity
import com.byeonggon.latent.data.db.SyncedFolderEntity
import com.byeonggon.latent.enhance.LutStore
import com.byeonggon.latent.enhance.MaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A restore about to be made: what the backup holds, and how much of it this phone has the photos for. */
data class RestorePlan(
    val manifest: BackupManifest,
    /** Photos and videos in the backup. */
    val photos: Int,
    val found: Int,
    val missing: MissingPhotos,
    /** Photos found here whose own rating, flag, edit or place the backup's will replace. */
    val replaced: Int,
    /** Albums that hold at least one of the photos found. */
    val albums: Int,
    /** Versions of the photos found. */
    val versions: Int,
    val filters: Int,
    val luts: Int,
    val masks: Int,
    val locations: Int,
    val syncRecords: Int,
    val hasSettings: Boolean,
    val onlineMapsInBackup: Boolean,
)

/** The photos of a backup that are not on this phone: how many, and the names of the first of them. */
data class MissingPhotos(val count: Int = 0, val names: List<String> = emptyList())

/** What a restore did. Counts of what was added leave out what the phone had already. */
data class RestoreResult(
    val photos: Int,
    val missing: MissingPhotos,
    val albums: Int,
    val versionsAdded: Int,
    val filtersAdded: Int,
    val lutsAdded: Int,
    val masksAdded: Int,
    val locationsAdded: Int,
    val syncRecordsAdded: Int,
    /** Online maps were on in the backup and are off here: they are left off, as the app asks before it goes online. */
    val onlineMapsLeftOff: Boolean,
)

/**
 * Brings a backup back onto the phone, merged with what is there: see docs/BACKUP_FORMAT.md. The whole file is read
 * and checked before anything changes; the database is changed in one transaction, so that a restore that fails or
 * is cancelled leaves it as it was, and a restore of the same file again changes nothing more.
 */
class Restorer(
    private val database: AppDatabase,
    private val settings: SettingsRepository,
    libraryRoot: File,
    /** Whether the library's folder and the phone's photos can be reached, which finding the photos needs. */
    private val hasAccess: () -> Boolean,
    /** Takes in the files the library does not know yet: its own folder's, and those in the phone [folders] given. */
    private val noticeFiles: suspend (folders: Set<String>) -> Unit,
    /** Whether a JPEG of the library keeps its original aside, as an edited one does. */
    private val hasOriginal: (MediaItemEntity) -> Boolean,
    phoneRelativePath: (String) -> String = PhoneLibrary::relativePathOf,
    /** Held from taking the files in to the last setting: what else lists the phone's photos waits for it. */
    private val exclusive: Mutex = Mutex(),
) {
    private val paths = MediaPaths(libraryRoot, phoneRelativePath)

    /** Reads and checks [zip], takes in the photos on the phone, and says what restoring it would do. Changes nothing of the backup's. */
    @Throws(IOException::class)
    suspend fun inspect(zip: File, progress: BackupProgress = BackupProgress.NONE): RestorePlan = withContext(Dispatchers.IO) {
        BackupArchive.open(zip).use { archive ->
            val contents = check(archive, progress)
            exclusive.withLock { planFor(archive, contents, progress) }
        }
    }

    private suspend fun planFor(archive: BackupArchive, contents: Contents, progress: BackupProgress): RestorePlan {
        prepare(contents, progress)
        val matcher = MediaMatcher(database.backupDao().mediaInLibrary(), paths)
        val found = HashSet<Long>()
        val missing = MissingList()
        var replaced = 0
        matchAll(archive, matcher, contents.media, progress) { record, target ->
            if (target == null) {
                missing += record.name
            } else {
                found += record.id
                if (replacesOwn(target, restoredOnto(target, record, target.editInFile && hasOriginal(target)))) replaced++
            }
        }
        val albumsHere = HashSet<Long>()
        archive.eachRecord(BackupFormat.ALBUM_ITEMS, AlbumItemRecord::fromJson) { if (it.media in found) albumsHere += it.album }
        // By name, as a restore makes them: two of one name in the backup come to one here.
        val albumNames = HashSet<String>()
        archive.eachRecord(BackupFormat.ALBUMS, AlbumRecord::fromJson) { if (it.id in albumsHere) albumNames += it.name }
        val albums = albumNames.size
        var versions = 0
        archive.eachRecord(BackupFormat.VERSIONS, VersionRecord::fromJson) { if (it.media in found) versions++ }
        return RestorePlan(
            manifest = archive.manifest,
            photos = contents.media,
            found = found.size,
            missing = missing.result(),
            replaced = replaced,
            albums = albums,
            versions = versions,
            filters = contents.filters,
            luts = contents.luts.size,
            masks = contents.masks.size,
            locations = contents.locations,
            syncRecords = contents.syncRecords,
            hasSettings = contents.settings != null,
            onlineMapsInBackup = contents.settings?.onlineMaps == true,
        )
    }

    /** Restores [zip]. Throws, having changed nothing of the backup's, when it cannot be read or is cancelled. */
    @Throws(IOException::class)
    suspend fun restore(zip: File, progress: BackupProgress = BackupProgress.NONE): RestoreResult = withContext(Dispatchers.IO) {
        BackupArchive.open(zip).use { archive ->
            val contents = check(archive, progress)
            exclusive.withLock { restoreFrom(archive, contents, progress) }
        }
    }

    private suspend fun restoreFrom(archive: BackupArchive, contents: Contents, progress: BackupProgress): RestoreResult {
        prepare(contents, progress)
        val before = settings.settings.first()
        val addedLuts = ArrayList<String>()
        val addedMasks = ArrayList<String>()
        val listedLuts = HashSet<String>()
        val result = try {
            // The files go first, so that edits never name one that is not there; they are taken away again if the rest fails.
            for ((index, id) in contents.luts.withIndex()) {
                if (LutStore.restore(id, archive.bytes(BackupFormat.lutEntry(id), LUT_LIMIT))) addedLuts += id
                progress.report(BackupStep.LUTS, index + 1, contents.luts.size)
            }
            for ((index, id) in contents.masks.withIndex()) {
                if (MaskStore.restore(id, archive.bytes(BackupFormat.maskEntry(id), MASK_LIMIT))) addedMasks += id
                progress.report(BackupStep.MASKS, index + 1, contents.masks.size)
            }
            database.withTransaction { restoreRecords(archive, contents, progress, listedLuts) }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                addedLuts.forEach(LutStore::delete)
                addedMasks.forEach(MaskStore::delete)
            }
            throw e
        }
        progress.report(BackupStep.SETTINGS, 0, 0)
        contents.settings?.let { restored -> settings.update(restored::applyTo) }
        return result.copy(
            lutsAdded = (listedLuts + addedLuts).size,
            masksAdded = addedMasks.size,
            onlineMapsLeftOff = contents.settings?.onlineMaps == true && !before.onlineMaps,
        )
    }

    /** What a backup holds, found by reading every entry through, which also proves it whole. */
    private class Contents(
        val media: Int,
        val phoneFolders: Set<String>,
        val filters: Int,
        val locations: Int,
        val syncRecords: Int,
        val luts: List<String>,
        val masks: List<String>,
        val settings: BackupSettings?,
    )

    private suspend fun check(archive: BackupArchive, progress: BackupProgress): Contents {
        val total = archive.manifest.counts.let { it.media + it.albumItems + it.versions + it.syncRecords + it.locations }
        var read = 0
        fun counted() {
            if (++read % REPORT_EVERY == 0) progress.report(BackupStep.CHECKING, read, total)
        }
        progress.report(BackupStep.CHECKING, 0, total)
        val folders = HashSet<String>()
        val media = archive.eachRecord(BackupFormat.MEDIA, MediaRecord::fromJson) { record ->
            if (record.origin == MediaOrigin.PHONE && record.path != null) folders += paths.phoneFolderOf(record.path)
            counted()
        }
        archive.eachRecord(BackupFormat.ALBUMS, AlbumRecord::fromJson) {}
        archive.eachRecord(BackupFormat.ALBUM_ITEMS, AlbumItemRecord::fromJson) { counted() }
        archive.eachRecord(BackupFormat.VERSIONS, VersionRecord::fromJson) { counted() }
        val filters = archive.eachRecord(BackupFormat.FILTERS, FilterRecord::fromJson) {}
        archive.eachRecord(BackupFormat.LUTS, LutRecord::fromJson) {}
        val syncRecords = archive.eachRecord(BackupFormat.SYNC_RECORDS, SyncRecordRecord::fromJson) { counted() }
        archive.eachRecord(BackupFormat.SYNCED_FOLDERS, SyncedFolderRecord::fromJson) {}
        val locations = archive.eachRecord(BackupFormat.LOCATIONS, LocationRecord::fromJson) { counted() }
        val luts = archive.names.mapNotNull { BackupFormat.LUT_FILE.matchEntire(it)?.groupValues?.get(1) }.distinct()
        for (id in luts) if (!LutStore.isTable(archive.bytes(BackupFormat.lutEntry(id), LUT_LIMIT))) throw DamagedBackupException(BackupFormat.lutEntry(id))
        val masks = archive.names.mapNotNull { BackupFormat.MASK_FILE.matchEntire(it)?.groupValues?.get(1) }.distinct()
        for (id in masks) if (!MaskStore.isOutline(archive.bytes(BackupFormat.maskEntry(id), MASK_LIMIT))) throw DamagedBackupException(BackupFormat.maskEntry(id))
        val restored = archive.json(BackupFormat.SETTINGS)?.let { json ->
            try {
                BackupSettings.fromJson(json)
            } catch (e: RuntimeException) {
                throw DamagedBackupException(BackupFormat.SETTINGS, cause = e)
            }
        }
        return Contents(media, folders, filters, locations, syncRecords, luts, masks, restored)
    }

    /** Lets the library take in the files on the phone, so that the backup's photos can be found among them. */
    private suspend fun prepare(contents: Contents, progress: BackupProgress) {
        if (!hasAccess()) throw NoLibraryAccessException()
        // Reading the places out of a phone folder's photos the first time takes a while.
        progress.report(BackupStep.FINDING, 0, 0)
        val chosen = settings.settings.first().phoneFolders
        noticeFiles(chosen + contents.settings?.phoneFolders.orEmpty() + contents.phoneFolders)
    }

    /**
     * Calls [each] with every photo of the backup and the one on this phone it is, or null. Places are tried for every
     * record before names are, so that a photo found by name never takes the one that another record is at the place of.
     */
    private suspend fun matchAll(
        archive: BackupArchive,
        matcher: MediaMatcher,
        total: Int,
        progress: BackupProgress,
        each: suspend (MediaRecord, MediaItemEntity?) -> Unit,
    ) {
        val atPath = HashMap<Long, MediaItemEntity>()
        archive.eachRecord(BackupFormat.MEDIA, MediaRecord::fromJson) { record -> matcher.atPath(record)?.let { atPath[record.id] = it } }
        var done = 0
        archive.eachRecord(BackupFormat.MEDIA, MediaRecord::fromJson) { record ->
            each(record, atPath[record.id] ?: matcher.elsewhere(record))
            if (++done % REPORT_EVERY == 0) progress.report(BackupStep.PHOTOS, done, total)
        }
    }

    /** Restores what the database keeps; the ids of the LUTs put on the list go into [luts]. */
    private suspend fun restoreRecords(archive: BackupArchive, contents: Contents, progress: BackupProgress, luts: MutableSet<String>): RestoreResult {
        val dao = database.backupDao()
        val matcher = MediaMatcher(dao.mediaInLibrary(), paths)
        // The backup's id of each photo found, to the id it has here.
        val ids = HashMap<Long, Long>()
        val missing = MissingList()
        matchAll(archive, matcher, contents.media, progress) { record, target ->
            if (target == null) {
                missing += record.name
                return@matchAll
            }
            val restored = restoredOnto(target, record, target.editInFile && hasOriginal(target))
            if (restored != target) dao.updateMedia(restored)
            ids[record.id] = target.id
        }

        progress.report(BackupStep.ALBUMS, 0, 0)
        // An album is only made for photos that are here: one with none of them would be an empty shell.
        val members = HashMap<Long, MutableList<AlbumItemEntity>>()
        archive.eachRecord(BackupFormat.ALBUM_ITEMS, AlbumItemRecord::fromJson) { item ->
            val media = ids[item.media] ?: return@eachRecord
            members.getOrPut(item.album) { ArrayList() } += AlbumItemEntity(albumId = 0, mediaId = media, addedAt = item.addedAt)
        }
        val albumsByName = HashMap<String, Long>()
        dao.albums().forEach { albumsByName.putIfAbsent(it.name, it.id) }
        val albumsTouched = HashSet<Long>()
        archive.eachRecord(BackupFormat.ALBUMS, AlbumRecord::fromJson) { album ->
            val items = members[album.id] ?: return@eachRecord
            // The album of that name here is the one: restoring twice must not make two.
            val id = albumsByName.getOrPut(album.name) { database.albumDao().insert(AlbumEntity(name = album.name, createdAt = album.createdAt)) }
            database.albumDao().addItems(items.map { it.copy(albumId = id) })
            albumsTouched += id
        }
        val albums = albumsTouched.size

        var versions = 0
        archive.eachRecord(BackupFormat.VERSIONS, VersionRecord::fromJson) { version ->
            val media = ids[version.media] ?: return@eachRecord
            if (dao.versionsOf(media).any { it.name == version.name && it.edit == version.edit }) return@eachRecord
            database.editVersionDao().insert(EditVersionEntity(mediaId = media, name = version.name, edit = version.edit, createdAt = version.createdAt))
            versions++
            progress.report(BackupStep.VERSIONS, versions, 0)
        }

        val looks = dao.filters().mapTo(HashSet()) { it.name to it.look }
        var filters = 0
        archive.eachRecord(BackupFormat.FILTERS, FilterRecord::fromJson) { filter ->
            if (!looks.add(filter.name to filter.look)) return@eachRecord
            database.filterDao().insert(filter)
            filters++
        }
        progress.report(BackupStep.FILTERS, filters, contents.filters)

        archive.eachRecord(BackupFormat.LUTS, LutRecord::fromJson) { lut ->
            if (database.lutDao().insert(lut) != -1L) luts += lut.hash
        }

        var syncRecords = 0
        val records = ArrayList<SyncRecordEntity>(BATCH)
        suspend fun flushRecords() {
            syncRecords += dao.addNewSyncRecords(records)
            records.clear()
        }
        archive.eachRecord(BackupFormat.SYNC_RECORDS, SyncRecordRecord::fromJson) { record ->
            records += record
            if (records.size == BATCH) {
                flushRecords()
                progress.report(BackupStep.CAMERA, syncRecords, contents.syncRecords)
            }
        }
        flushRecords()
        val folders = ArrayList<SyncedFolderEntity>()
        archive.eachRecord(BackupFormat.SYNCED_FOLDERS, SyncedFolderRecord::fromJson) { folders += it }
        folders.chunked(BATCH).forEach { dao.insertSyncedFolders(it) }

        // The unique index on time and source keeps a point that is here already from coming twice.
        var locations = 0
        var read = 0
        val points = ArrayList<LocationPointEntity>(LOCATION_BATCH)
        suspend fun flushPoints() {
            locations += database.locationDao().addNew(points)
            read += points.size
            points.clear()
            progress.report(BackupStep.TIMELINE, read, contents.locations)
        }
        archive.eachRecord(BackupFormat.LOCATIONS, LocationRecord::fromJson) { point ->
            points += point
            if (points.size == LOCATION_BATCH) flushPoints()
        }
        flushPoints()

        return RestoreResult(
            photos = ids.size,
            missing = missing.result(),
            albums = albums,
            versionsAdded = versions,
            filtersAdded = filters,
            lutsAdded = 0,
            masksAdded = 0,
            locationsAdded = locations,
            syncRecordsAdded = syncRecords,
            onlineMapsLeftOff = false,
        )
    }

    /** The names of the photos not found, as many as a message can show. */
    private class MissingList {
        private var count = 0
        private val names = ArrayList<String>()

        operator fun plusAssign(name: String) {
            count++
            if (names.size < MISSING_NAMES) names += name
        }

        fun result() = MissingPhotos(count, names.toList())
    }

    private companion object {
        const val BATCH = 500
        const val LOCATION_BATCH = 2_000
        const val REPORT_EVERY = 1_000
        const val MISSING_NAMES = 10

        /** The largest LUT LutStore keeps, 65 points a side, is about 3.3 MB. */
        const val LUT_LIMIT = 4 * 1024 * 1024

        /** An outline of up to 8192 by 8192 cells, packed. */
        const val MASK_LIMIT = 64 * 1024 * 1024
    }
}
