package com.byeonggon.latent.camera

import android.util.Log
import androidx.room.withTransaction
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.LibraryStorage
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.isHeifName
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.data.db.SyncRecordEntity
import com.byeonggon.latent.data.db.SyncedFolderEntity
import com.byeonggon.latent.location.Geotagger
import com.byeonggon.latent.location.MediaMetadataReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface SyncState {
    data object Idle : SyncState

    data class Scanning(val found: Int) : SyncState

    data class Transferring(
        val index: Int,
        val total: Int,
        val fileName: String,
        val transferredBytes: Long,
        val totalBytes: Long,
        val cancelling: Boolean,
    ) : SyncState

    data class Finished(
        val imported: Int,
        val geotagged: Int,
        val failed: List<String>,
        val cancelled: Boolean,
        /** Stopped because the phone was nearly full; the files not yet imported are still on the camera. */
        val outOfSpace: Boolean = false,
    ) : SyncState

    data class Failed(val message: String) : SyncState

    val isRunning: Boolean get() = this is Scanning || this is Transferring
}

/** Limits a sync to files shot between [start] and [end], both inclusive. */
data class SyncDates(val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = date >= start && date <= end
}

/** Copies new files from a camera into the library, geotagging them on the way in. */
class SyncEngine(
    private val storage: LibraryStorage,
    private val database: AppDatabase,
    private val settings: SettingsRepository,
    private val geotagger: Geotagger,
    private val freeBytes: () -> Long = { storage.freeBytes() },
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    // MTP transfers block and cannot be interrupted, so cancelling is cooperative: it takes effect between files.
    @Volatile
    private var cancelRequested = false

    fun requestCancel() {
        cancelRequested = true
    }

    /** Imports everything new on [source], or only what was shot within [dates] when given. */
    suspend fun run(source: CameraSource, dates: SyncDates? = null) = withContext(Dispatchers.IO) {
        cancelRequested = false
        try {
            source.use { sync(it, dates) }
        } catch (e: SyncCancelledException) {
            _state.value = SyncState.Finished(imported = 0, geotagged = 0, failed = emptyList(), cancelled = true)
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            throw e
        } catch (e: IOException) {
            Log.e(SYNC_LOG_TAG, "Sync failed", e)
            _state.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            Log.e(SYNC_LOG_TAG, "Sync failed", e)
            _state.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun sync(source: CameraSource, dates: SyncDates?) {
        _state.value = SyncState.Scanning(0)
        val camera = source.open()
        val options = settings.settings.first()
        val cameraId = camera.serial.ifBlank { camera.model }
        val acceptedKinds = MediaKind.entries.filter(options::accepts)
        val alreadyImported = ImportedFiles(database.syncRecordDao().allKeys())

        val listingStartedAt = System.nanoTime()
        val request = ListingRequest(dates, importedFolders(cameraId, acceptedKinds))
        val listing = source.listFiles(request) { count ->
            if (cancelRequested) throw SyncCancelledException()
            _state.value = SyncState.Scanning(count)
        }
        // Dates are checked before anything is downloaded, so they go by the camera's file date, not EXIF.
        val zone = ZoneId.systemDefault()
        val listed = listing.files
            .filter { options.accepts(it.kind) && !alreadyImported.contains(it, cameraId) }
            .filter { dates == null || Instant.ofEpochMilli(it.modifiedAt).atZone(zone).toLocalDate() in dates }
        val seenElsewhere = seenElsewhere(source, listed, cameraId, alreadyImported)
        val pending = withoutCopiesInLibrary(listed.filterNot { it in seenElsewhere }, cameraId, alreadyImported)
            .sortedBy { it.modifiedAt }
        Log.i(
            SYNC_LOG_TAG,
            "${camera.model}: listed ${listing.files.size} media files in " +
                "${(System.nanoTime() - listingStartedAt) / 1_000_000} ms, ${pending.size} to import" +
                (dates?.let { " (limited to ${it.start}..${it.end})" } ?: ""),
        )

        storage.prepareIncoming()
        val totalBytes = pending.sumOf { it.sizeBytes }
        val maxGapMs = options.geotagMaxGapMinutes * 60_000L
        var doneBytes = 0L
        var imported = 0
        var geotagged = 0
        var outOfSpace = false
        val failed = ArrayList<String>()

        for ((index, file) in pending.withIndex()) {
            if (cancelRequested) break
            // The oldest files come first; what does not fit is left on the camera for the next time.
            if (freeBytes() - spaceNeededFor(file) < LEAVE_FREE_BYTES) {
                outOfSpace = true
                break
            }
            val part = storage.incomingFile(file.name)
            val downloaded = download(source, file, part) { bytes ->
                _state.value = SyncState.Transferring(
                    index = index + 1,
                    total = pending.size,
                    fileName = file.name,
                    transferredBytes = doneBytes + bytes,
                    totalBytes = totalBytes,
                    cancelling = cancelRequested,
                )
            }
            val stored = downloaded && try {
                if (store(part, file, cameraId, maxGapMs, options)) geotagged++
                true
            } catch (e: IOException) {
                Log.w(SYNC_LOG_TAG, "Cannot store ${file.name} in the library", e)
                false
            }
            if (stored) {
                imported++
                alreadyImported.add(file.sourceKey(cameraId))
            } else {
                part.delete()
                failed += file.name
            }
            doneBytes += file.sizeBytes
        }
        rememberImportedFolders(cameraId, acceptedKinds, listing, alreadyImported, seenElsewhere)
        Log.i(
            SYNC_LOG_TAG,
            "Finished: imported=$imported, geotagged=$geotagged, failed=$failed, cancelled=$cancelRequested, outOfSpace=$outOfSpace",
        )
        _state.value = SyncState.Finished(imported, geotagged, failed, cancelled = cancelRequested, outOfSpace = outOfSpace)
    }

    /** Folders that can be skipped while unchanged: fully imported for at least the kinds wanted now. */
    private suspend fun importedFolders(cameraId: String, acceptedKinds: List<MediaKind>): Map<String, String> =
        database.syncedFolderDao().forCamera(cameraId)
            .filter { folder -> acceptedKinds.all { it.name in folder.kinds.split(',') } }
            .associate { it.path to it.fingerprint }

    /** Records which of the folders just read have nothing left to import, so the next sync can skip them. */
    private suspend fun rememberImportedFolders(
        cameraId: String,
        acceptedKinds: List<MediaKind>,
        listing: Listing,
        alreadyImported: ImportedFiles,
        seenElsewhere: Set<RemoteFile>,
    ) {
        val filesByFolder = listing.files.groupBy { it.folder }
        val dao = database.syncedFolderDao()
        for ((path, fingerprint) in listing.folders) {
            val nothingLeft = filesByFolder[path].orEmpty().all {
                it.kind !in acceptedKinds || alreadyImported.contains(it, cameraId) || it in seenElsewhere
            }
            if (nothingLeft) {
                dao.upsert(SyncedFolderEntity(cameraId, path, fingerprint, acceptedKinds.joinToString(",") { it.name }))
            } else {
                dao.delete(cameraId, path)
            }
        }
    }

    /** What copying [file] needs free: its size, or at least 4 GB where the camera could not say. */
    private fun spaceNeededFor(file: RemoteFile) =
        if (file.sizeBytes == RemoteFile.UNKNOWN_SIZE) AT_LEAST_BYTES_WHEN_SIZE_UNKNOWN else file.sizeBytes

    private suspend fun download(
        source: CameraSource,
        file: RemoteFile,
        part: File,
        onProgress: (Long) -> Unit,
    ): Boolean = coroutineScope {
        onProgress(0)
        // importFile reports nothing while it runs, so progress is read off the growing file.
        val progress = launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                onProgress(part.length())
            }
        }
        val startedAt = System.nanoTime()
        try {
            val accepted = source.download(file, part)
            val received = part.length()
            val complete = accepted && (file.sizeBytes == RemoteFile.UNKNOWN_SIZE || received == file.sizeBytes)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            if (complete) {
                Log.d(SYNC_LOG_TAG, "Transferred ${file.name}: $received bytes in $elapsedMs ms")
            } else {
                Log.w(
                    SYNC_LOG_TAG,
                    "Transfer of ${file.name} failed after $elapsedMs ms: camera reported " +
                        "${if (accepted) "success" else "failure"}, received $received of ${file.sizeBytes} bytes",
                )
            }
            complete
        } catch (e: IOException) {
            Log.w(SYNC_LOG_TAG, "Transfer of ${file.name} failed", e)
            false
        } finally {
            progress.cancel()
        }
    }

    /** Moves a downloaded file into the library. Returns true when a location from the track was applied. */
    private suspend fun store(part: File, file: RemoteFile, cameraId: String, maxGapMs: Long, options: AppSettings): Boolean {
        val metadata = MediaMetadataReader.read(part, file.kind, options.cameraZone())
        val capturedAt = metadata.capturedAt ?: file.modifiedAt
        // Matched by when it really was, though it is filed under the time the camera gave it.
        val matched = if (metadata.location == null) geotagger.locate(options.realTimeOf(capturedAt), maxGapMs) else null

        // EXIF is written before the file becomes visible, so gallery and backup apps never see an untagged copy.
        if (matched != null && file.kind == MediaKind.PHOTO && !isHeifName(file.name)) geotagger.writeExif(part, matched)
        val target = storage.targetFor(file.name, capturedAt)
        if (!part.renameTo(target)) throw IOException("Cannot move ${file.name} into the library")
        // A raw file and a HEIF picture are not written to; the place goes in a sidecar beside them.
        if (matched != null && (file.kind == MediaKind.RAW || isHeifName(file.name))) geotagger.writeSidecar(target, matched)
        storage.publish(target)

        val location = metadata.location ?: matched
        val geoSource = when {
            metadata.location != null -> GeoSource.CAMERA
            matched != null -> GeoSource.TRACK
            else -> GeoSource.NONE
        }
        Log.d(
            SYNC_LOG_TAG,
            "Stored ${target.name}: capture time from ${if (metadata.capturedAt != null) "metadata" else "camera file date"}, " +
                "location $geoSource",
        )
        val now = System.currentTimeMillis()
        val sourceKey = file.sourceKey(cameraId)
        database.withTransaction {
            database.mediaDao().insert(
                MediaItemEntity(
                    path = target.absolutePath,
                    fileName = target.name,
                    kind = file.kind,
                    sizeBytes = target.length(),
                    capturedAt = capturedAt,
                    importedAt = now,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    altitude = location?.altitude,
                    geoSource = geoSource,
                    sourceKey = sourceKey,
                ),
            )
            database.syncRecordDao().insert(SyncRecordEntity(sourceKey, now))
        }
        return matched != null
    }

    /**
     * Of [files], those that came in from another camera or card before. A card cannot say which camera wrote it, so
     * a file imported elsewhere under the same name, size and time ([ImportedFiles.importedElsewhere]) is taken for
     * the same only when its end, where its picture or video is, reads as the library's copy does; a JPEG's EXIF,
     * rewritten with its place on the way in, is at its start. Where either end cannot be read, or the library holds
     * no copy any more, the file is imported: a copy too many is better than a photo never brought in. Nothing is
     * recorded for them, so the photos deleted from the phone stay counted as they were; the folders they are in
     * are remembered as imported, so the next sync does not compare them again while they stay as they are.
     */
    private suspend fun seenElsewhere(
        source: CameraSource,
        files: List<RemoteFile>,
        cameraId: String,
        imported: ImportedFiles,
    ): Set<RemoteFile> {
        val candidates = files.associateWith { imported.importedElsewhere(it, cameraId) }.filterValues { it.isNotEmpty() }
        if (candidates.isEmpty()) return emptySet()
        val copies = candidates.values.flatten().distinct().chunked(500)
            .flatMap { database.mediaDao().bySourceKeys(it) }
            .groupBy { it.sourceKey }
        val same = HashSet<RemoteFile>()
        for ((file, keys) in candidates) {
            if (cancelRequested) throw SyncCancelledException()
            val inLibrary = keys.flatMap { copies[it].orEmpty() }
            if (inLibrary.isEmpty()) continue
            // At most its second half: a small JPEG's EXIF, at its start, is never among what is compared.
            val tail = source.readTail(file, minOf(COMPARED_BYTES.toLong(), file.sizeBytes / 2).toInt()) ?: continue
            if (tail.isEmpty()) continue
            if (inLibrary.any { tailAsImported(it, tail.size)?.contentEquals(tail) == true }) same += file
        }
        Log.i(SYNC_LOG_TAG, "${candidates.size} files look imported from elsewhere; ${same.size} are, the rest are new")
        return same
    }

    /** The end of [item]'s file as it came in: of the original it keeps, if it was edited since. */
    private fun tailAsImported(item: MediaItemEntity, length: Int): ByteArray? {
        val file = File(item.path)
        val asImported = storage.originalOf(file).takeIf { item.kind == MediaKind.PHOTO && it.canRead() } ?: file
        return try {
            RandomAccessFile(asImported, "r").use { tailOf(it.channel, length) }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * [files] less those the library holds already without knowing they came from this camera: copied
     * from another phone, say. A copy has the camera file's name and size, or its kept original has,
     * if it was edited. They are remembered as imported from now on, as if this sync had brought them.
     */
    private suspend fun withoutCopiesInLibrary(files: List<RemoteFile>, cameraId: String, imported: ImportedFiles): List<RemoteFile> {
        val unclaimed = database.mediaDao().all().filter { it.sourceKey == null && it.origin == MediaOrigin.LIBRARY }
        if (unclaimed.isEmpty()) return files
        val copies = HashMap<String, MediaItemEntity>()
        for (item in unclaimed) {
            val file = File(item.path)
            copies["${item.fileName}|${item.sizeBytes}"] = item
            if (item.kind == MediaKind.PHOTO) {
                storage.originalOf(file).takeIf { it.exists() }?.let { copies["${item.fileName}|${it.length()}"] = item }
            }
        }
        val (copied, fresh) = files.partition { it.sizeBytes != RemoteFile.UNKNOWN_SIZE && "${it.name}|${it.sizeBytes}" in copies }
        if (copied.isNotEmpty()) {
            val now = System.currentTimeMillis()
            database.withTransaction {
                for (file in copied) {
                    val key = file.sourceKey(cameraId)
                    database.mediaDao().setSourceKey(copies.getValue("${file.name}|${file.sizeBytes}").id, key)
                    database.syncRecordDao().insert(SyncRecordEntity(key, now))
                    imported.add(key)
                }
            }
            Log.i(SYNC_LOG_TAG, "${copied.size} files are in the library already, copied there")
        }
        return fresh
    }

    private class SyncCancelledException : RuntimeException()

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L

        /** How much of the end of a file is compared with the library's copy: its picture's last bytes. */
        const val COMPARED_BYTES = 64 * 1024

        /** Left free on the phone: filling it to the last byte would leave it unable to do anything else. */
        const val LEAVE_FREE_BYTES = 500L * 1024 * 1024

        /** A file whose size the camera could not give is one of 4 GB or more: the 32 bits of its object info overflowed. */
        const val AT_LEAST_BYTES_WHEN_SIZE_UNKNOWN = 4L * 1024 * 1024 * 1024
    }
}
