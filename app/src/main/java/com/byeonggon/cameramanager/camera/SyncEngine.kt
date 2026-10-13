package com.byeonggon.cameramanager.camera

import android.util.Log
import androidx.room.withTransaction
import com.byeonggon.cameramanager.data.LibraryStorage
import com.byeonggon.cameramanager.data.SettingsRepository
import com.byeonggon.cameramanager.data.db.AppDatabase
import com.byeonggon.cameramanager.data.db.GeoSource
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.data.db.SyncRecordEntity
import com.byeonggon.cameramanager.data.db.SyncedFolderEntity
import com.byeonggon.cameramanager.location.Geotagger
import com.byeonggon.cameramanager.location.MediaMetadataReader
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
        val pending = listing.files
            .filter { options.accepts(it.kind) && !alreadyImported.contains(it, cameraId) }
            .filter { dates == null || Instant.ofEpochMilli(it.modifiedAt).atZone(zone).toLocalDate() in dates }
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
        val failed = ArrayList<String>()

        for ((index, file) in pending.withIndex()) {
            if (cancelRequested) break
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
                if (store(part, file, cameraId, maxGapMs)) geotagged++
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
        rememberImportedFolders(cameraId, acceptedKinds, listing, alreadyImported)
        Log.i(
            SYNC_LOG_TAG,
            "Finished: imported=$imported, geotagged=$geotagged, failed=$failed, cancelled=$cancelRequested",
        )
        _state.value = SyncState.Finished(imported, geotagged, failed, cancelled = cancelRequested)
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
    ) {
        val filesByFolder = listing.files.groupBy { it.folder }
        val dao = database.syncedFolderDao()
        for ((path, fingerprint) in listing.folders) {
            val nothingLeft = filesByFolder[path].orEmpty().all {
                it.kind !in acceptedKinds || alreadyImported.contains(it, cameraId)
            }
            if (nothingLeft) {
                dao.upsert(SyncedFolderEntity(cameraId, path, fingerprint, acceptedKinds.joinToString(",") { it.name }))
            } else {
                dao.delete(cameraId, path)
            }
        }
    }

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
    private suspend fun store(part: File, file: RemoteFile, cameraId: String, maxGapMs: Long): Boolean {
        val metadata = MediaMetadataReader.read(part, file.kind)
        val capturedAt = metadata.capturedAt ?: file.modifiedAt
        val matched = if (metadata.location == null) geotagger.locate(capturedAt, maxGapMs) else null

        // EXIF is written before the file becomes visible, so gallery and backup apps never see an untagged copy.
        if (matched != null && file.kind == MediaKind.PHOTO) geotagger.writeExif(part, matched)
        val target = storage.targetFor(file.name, capturedAt)
        if (!part.renameTo(target)) throw IOException("Cannot move ${file.name} into the library")
        if (matched != null && file.kind == MediaKind.RAW) geotagger.writeSidecar(target, matched)
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

    private class SyncCancelledException : RuntimeException()

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
