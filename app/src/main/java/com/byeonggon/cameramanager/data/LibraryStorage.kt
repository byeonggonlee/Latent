package com.byeonggon.cameramanager.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** The photo library on shared storage: DCIM/CameraManager/<year>/<date>/<file>. */
class LibraryStorage(private val context: Context) {
    val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "CameraManager")
    private val incoming = File(root, ".incoming")
    private val originals = File(root, ".originals")

    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    /** Whether library files can be reached right now: access is granted and shared storage is mounted. */
    fun isAvailable(): Boolean = hasAccess() && Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    /** Creates the hidden staging folder and drops partial downloads left behind by an interrupted sync. */
    fun prepareIncoming() {
        incoming.mkdirs()
        File(incoming, ".nomedia").createNewFile()
        incoming.listFiles { file -> file.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
    }

    fun incomingFile(fileName: String) = File(incoming, fileName + PART_SUFFIX)

    fun targetFor(fileName: String, capturedAt: Long): File {
        val date = Instant.ofEpochMilli(capturedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val folder = File(root, "${date.year}/$date")
        folder.mkdirs()
        return uniqueFile(folder, fileName)
    }

    /** A free file name next to [source], e.g. DSC0001_developed.jpg. */
    fun siblingFor(source: File, suffix: String, extension: String): File =
        uniqueFile(source.parentFile ?: root, "${source.nameWithoutExtension}$suffix.$extension")

    /**
     * Where the untouched original of an edited library file is kept: the same path under a hidden
     * folder, so gallery apps only ever show the edited picture.
     */
    fun originalOf(file: File): File = File(originals, file.relativeTo(root).path)

    /** Makes the file show up in gallery apps. */
    fun publish(file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    }

    private fun uniqueFile(folder: File, fileName: String): File {
        val first = File(folder, fileName)
        if (!first.exists()) return first
        val base = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val name = if (extension.isEmpty()) "${base}_$index" else "${base}_$index.$extension"
            val candidate = File(folder, name)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
