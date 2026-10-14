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
 */
internal class ImportedFiles(sourceKeys: Collection<String>) {
    private val timesByIdentity = HashMap<String, MutableList<Long>>()

    init {
        sourceKeys.forEach(::add)
    }

    fun add(sourceKey: String) {
        val separator = sourceKey.lastIndexOf('|')
        if (separator < 0) return
        val time = sourceKey.substring(separator + 1).toLongOrNull() ?: return
        timesByIdentity.getOrPut(sourceKey.substring(0, separator)) { ArrayList() } += time
    }

    fun contains(file: RemoteFile, cameraId: String): Boolean =
        timesByIdentity[file.identity(cameraId)].orEmpty().any { time ->
            val shift = abs(time - file.modifiedAt)
            shift <= ZONE_OFFSET_SPAN_MS && shift % ZONE_OFFSET_STEP_MS == 0L
        }
}
