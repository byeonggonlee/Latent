package com.byeonggon.latent.camera

import kotlin.math.abs

/** Every time zone offset is a whole number of quarter hours. */
internal const val ZONE_OFFSET_STEP_MS = 15 * 60_000L

/** UTC-12 to UTC+14: the furthest apart two readings of the same wall-clock time can be. */
private const val ZONE_OFFSET_SPAN_MS = 26 * 60 * 60_000L

/**
 * The camera files that were already imported, looked up in a way that survives the phone changing
 * time zone.
 *
 * A camera reports file times as wall-clock time and Android reads them in whichever zone the phone is
 * in at that moment, so the same file shows up with a shifted timestamp after travelling. A file with
 * the same name and size whose time differs by a whole number of quarter hours is therefore the same file.
 *
 * A card read in a card reader, or a camera read as a drive, cannot say which camera wrote it (see
 * [isFolderCamera]): there the file of any camera counts, and the other way round, so that a card does
 * not bring back what came over MTP, nor MTP what came off the card.
 */
internal class ImportedFiles(sourceKeys: Collection<String>) {
    /** By name and size, the camera and the time of each import. */
    private val imports = HashMap<String, MutableList<Import>>()

    private class Import(val cameraId: String, val time: Long)

    init {
        sourceKeys.forEach(::add)
    }

    fun add(sourceKey: String) {
        // camera|name|size|time, read from the end: a camera's id is whatever the camera said.
        val timeAt = sourceKey.lastIndexOf('|')
        if (timeAt < 0) return
        val time = sourceKey.substring(timeAt + 1).toLongOrNull() ?: return
        val sizeAt = sourceKey.lastIndexOf('|', timeAt - 1)
        val nameAt = if (sizeAt > 0) sourceKey.lastIndexOf('|', sizeAt - 1) else -1
        if (nameAt < 0) return
        imports.getOrPut(sourceKey.substring(nameAt + 1, timeAt)) { ArrayList() } += Import(sourceKey.substring(0, nameAt), time)
    }

    fun contains(file: RemoteFile, cameraId: String): Boolean =
        imports["${file.name}|${file.sizeBytes}"].orEmpty().any { import ->
            val shift = abs(import.time - file.modifiedAt)
            (import.cameraId == cameraId || isFolderCamera(import.cameraId) || isFolderCamera(cameraId)) &&
                shift <= ZONE_OFFSET_SPAN_MS && shift % ZONE_OFFSET_STEP_MS == 0L
        }
}
