package com.byeonggon.latent.camera

import com.byeonggon.latent.data.db.MediaKind
import java.io.Closeable
import java.io.File

/** One logcat tag for everything a sync does: `adb logcat -s CameraSync`. */
internal const val SYNC_LOG_TAG = "CameraSync"

data class CameraInfo(val manufacturer: String, val model: String, val serial: String)

data class RemoteFile(
    val handle: Int,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val kind: MediaKind,
    /** Path of the camera folder holding the file, matching the keys of [Listing.folders]. */
    val folder: String,
) {
    /** What stays the same about a file wherever the phone is. Not unique: camera file names wrap around. */
    fun identity(cameraId: String) = "$cameraId|$name|$sizeBytes"

    /** Recorded once the file is imported; [ImportedFiles] recognises it again on later syncs. */
    fun sourceKey(cameraId: String) = "${identity(cameraId)}|$modifiedAt"

    companion object {
        const val UNKNOWN_SIZE = 0L
    }
}

/** Narrows a listing down: cameras describe one object per round trip, so a full card takes minutes to read. */
class ListingRequest(
    /** Folders named after a date outside this range are not opened at all. */
    val dates: SyncDates? = null,
    /** Fingerprints, by path, of folders whose files were all imported; those still matching are skipped. */
    val importedFolders: Map<String, String> = emptyMap(),
)

class Listing(
    val files: List<RemoteFile>,
    /** Fingerprint of every folder that was read in full, by path. */
    val folders: Map<String, String>,
)

/**
 * A place to pull media from: a camera over MTP ([MtpCameraSource]), or its card in a card reader or the camera
 * connected as a drive, through a folder picked for it ([FolderCameraSource]).
 *
 * All calls block and must run off the main thread.
 */
interface CameraSource : Closeable {
    fun open(): CameraInfo

    /** Lists the media files on the camera. [onFound] receives the running count and may throw to abort. */
    fun listFiles(request: ListingRequest, onFound: (Int) -> Unit): Listing

    fun download(file: RemoteFile, destination: File): Boolean

    /** The last [length] bytes of [file], or all of it if it is shorter; null when they cannot be read on their own. */
    fun readTail(file: RemoteFile, length: Int): ByteArray? = null
}
