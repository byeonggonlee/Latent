package com.byeonggon.cameramanager.camera

import androidx.room.withTransaction
import com.byeonggon.cameramanager.data.LibraryStorage
import com.byeonggon.cameramanager.data.SettingsRepository
import com.byeonggon.cameramanager.data.db.AppDatabase
import com.byeonggon.cameramanager.data.db.GeoSource
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.data.db.SyncRecordEntity
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

    suspend fun run(source: CameraSource) = withContext(Dispatchers.IO) {
        cancelRequested = false
        try {
            source.use { sync(it) }
        } catch (e: SyncCancelledException) {
            _state.value = SyncState.Finished(imported = 0, geotagged = 0, failed = emptyList(), cancelled = true)
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            throw e
        } catch (e: IOException) {
            _state.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            _state.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun sync(source: CameraSource) {
        _state.value = SyncState.Scanning(0)
        val camera = source.open()
        val options = settings.settings.first()
        val cameraId = camera.serial.ifBlank { camera.model }
        val known = database.syncRecordDao().allKeys().toHashSet()
        val pending = source
            .listFiles { found ->
                if (cancelRequested) throw SyncCancelledException()
                _state.value = SyncState.Scanning(found)
            }
            .filter { options.accepts(it.kind) && it.sourceKey(cameraId) !in known }
            .sortedBy { it.modifiedAt }

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
            try {
                if (!downloaded) throw IOException("Transfer of ${file.name} was incomplete")
                if (store(part, file, cameraId, maxGapMs)) geotagged++
                imported++
            } catch (e: IOException) {
                part.delete()
                failed += file.name
            }
            doneBytes += file.sizeBytes
        }
        _state.value = SyncState.Finished(imported, geotagged, failed, cancelled = cancelRequested)
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
        try {
            source.download(file, part) &&
                (file.sizeBytes == RemoteFile.UNKNOWN_SIZE || part.length() == file.sizeBytes)
        } catch (e: IOException) {
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
        val now = System.currentTimeMillis()
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
                    geoSource = when {
                        metadata.location != null -> GeoSource.CAMERA
                        matched != null -> GeoSource.TRACK
                        else -> GeoSource.NONE
                    },
                ),
            )
            database.syncRecordDao().insert(SyncRecordEntity(file.sourceKey(cameraId), now))
        }
        return matched != null
    }

    private class SyncCancelledException : RuntimeException()

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
