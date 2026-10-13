package com.byeonggon.cameramanager.camera

import com.byeonggon.cameramanager.data.db.MediaKind
import java.io.Closeable
import java.io.File

data class CameraInfo(val manufacturer: String, val model: String, val serial: String)

data class RemoteFile(
    val handle: Int,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val kind: MediaKind,
) {
    /** Identifies this file across sync runs. Camera file names wrap around, so size and date are included. */
    fun sourceKey(cameraId: String) = "$cameraId|$name|$sizeBytes|$modifiedAt"

    companion object {
        const val UNKNOWN_SIZE = 0L
    }
}

/**
 * A place to pull media from. MTP is the only implementation today; a Mass Storage reader can be added
 * behind the same interface if a camera hides files in MTP mode.
 *
 * All calls block and must run off the main thread.
 */
interface CameraSource : Closeable {
    fun open(): CameraInfo

    /** Lists every media file on the camera. [onFound] receives the running count and may throw to abort. */
    fun listFiles(onFound: (Int) -> Unit): List<RemoteFile>

    fun download(file: RemoteFile, destination: File): Boolean
}
