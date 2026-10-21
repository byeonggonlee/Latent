package com.byeonggon.latent.camera

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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

    /** Whether [file] was imported from this camera, or this card, before. */
    fun contains(file: RemoteFile, cameraId: String): Boolean = matches(file).any { it.cameraId == cameraId }

    /**
     * The source keys under which a file like [file] came in from elsewhere, when this source or that one is a card
     * read as a folder (see [isFolderCamera]), which cannot say which camera wrote it. Only candidates: two cameras'
     * DSC00001.ARW can share a name, a size and a time, so what they hold has still to be compared.
     */
    fun importedElsewhere(file: RemoteFile, cameraId: String): List<String> = matches(file)
        .filter { it.cameraId != cameraId && (isFolderCamera(it.cameraId) || isFolderCamera(cameraId)) }
        .map { "${it.cameraId}|${file.name}|${file.sizeBytes}|${it.time}" }

    private fun matches(file: RemoteFile): List<Import> =
        imports["${file.name}|${file.sizeBytes}"].orEmpty().filter { import ->
            val shift = abs(import.time - file.modifiedAt)
            shift <= ZONE_OFFSET_SPAN_MS && shift % ZONE_OFFSET_STEP_MS == 0L
        }
}

/** The last [length] bytes of what [channel] holds, or all of it if it is shorter. */
internal fun tailOf(channel: FileChannel, length: Int): ByteArray {
    val size = channel.size()
    val tail = ByteBuffer.allocate(minOf(length.toLong(), size).toInt())
    channel.position(size - tail.capacity())
    while (tail.hasRemaining() && channel.read(tail) >= 0) Unit
    return tail.array()
}
