package com.byeonggon.latent.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.PhotoFiles
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** The photo library on shared storage: DCIM/Latent/<year>/<date>/<file>. */
class LibraryStorage(private val context: Context) : PhotoFiles {
    val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Latent")
    // Folders whose names start with a dot are kept out of gallery apps.
    private val incoming = File(root, ".incoming")
    private val originals = File(root, ".originals")

    /** Reaches what shared storage does not let the app reach by path. */
    val folder = GrantedFolder(context, root)

    /**
     * Whether the app may read the photos and videos on shared storage, and was handed the library folder.
     * Reading covers photos another installation of the app made; it is also what lets photos keep their
     * locations when they are read.
     */
    fun hasAccess(): Boolean = canReadMedia(context) && folder.isGranted()

    /** Whether library files can be reached right now: access is granted and shared storage is mounted. */
    fun isAvailable(): Boolean = hasAccess() && Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    /**
     * Creates the hidden staging folder and drops partial downloads left behind by an interrupted sync:
     * everything in it, as a finished download is moved out at once.
     */
    fun prepareIncoming() {
        incoming.mkdirs()
        incoming.listFiles()?.forEach { it.delete() }
    }

    // It keeps its own name: shared storage only takes photos and videos in DCIM, known by their extensions.
    fun incomingFile(fileName: String) = File(incoming, fileName)

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

    /**
     * A file to hand a copy of a photo to another app with, named [fileName]. Copies made for earlier
     * shares are cleared away first: they only have to last until the other app has read them.
     */
    fun shareFile(fileName: String): File {
        clearShared()
        return sharedFile(fileName)
    }

    /** Clears away the copies made for earlier shares. */
    fun clearShared() {
        File(context.cacheDir, "share").deleteRecursively()
    }

    /** A file among the copies of one share, which may hold several. */
    fun sharedFile(fileName: String): File = File(context.cacheDir, "share").apply { mkdirs() }.let { uniqueFile(it, fileName) }

    /** The folder that keeps a file in the trash, with what belongs to it: one for each library entry. */
    fun trashFolder(id: Long): File = File(root, ".trash/$id").apply { mkdirs() }

    /** [file] if it is free, or else a free name next to it, such as DSC0001_1.JPG. */
    fun freeFileLike(file: File): File = uniqueFile(file.parentFile ?: root, file.name)

    override fun exists(file: File): Boolean = file.exists() || folder.exists(file)

    @Throws(IOException::class)
    override fun replace(from: File, to: File) {
        if (from.renameTo(to)) return
        if (exists(to) && !delete(to)) throw IOException("Cannot replace ${to.name}")
        move(from, to)
    }

    /** Moves [from] to [to], which must not exist yet. */
    @Throws(IOException::class)
    fun move(from: File, to: File) {
        if (from.renameTo(to)) return
        if (isPhoto(to) && takeOver(from, to)) return
        if (!folder.move(from, to)) throw IOException("Cannot move ${from.name}")
    }

    /**
     * Moves a photo the app did not make, copied in from another phone say, by copying it to a file of
     * the app's own and deleting it: moved as it is, it would stay another's, and in the folders whose
     * names start with a dot, where kept originals and the trash are, the app could not read it again.
     * Returns whether it moved.
     */
    private fun takeOver(from: File, to: File): Boolean {
        val modifiedAt = from.lastModified()
        return try {
            to.parentFile?.mkdirs()
            val input = if (from.canRead()) from.inputStream() else folder.openInput(from)
            input.use { source -> to.outputStream().use { source.copyTo(it) } }
            if (modifiedAt > 0) to.setLastModified(modifiedAt)
            folder.delete(from).also { deleted -> if (!deleted) to.delete() }
        } catch (e: IOException) {
            to.delete()
            false
        }
    }

    private fun isPhoto(file: File) = MediaKind.fromFileName(file.name).let { it == MediaKind.PHOTO || it == MediaKind.RAW }

    /** Deletes a file, or a folder with all it holds. Returns whether it is gone. */
    fun delete(file: File): Boolean {
        file.deleteRecursively()
        return !exists(file) || folder.delete(file)
    }

    /** Writes [text] to [file], which must not exist yet. */
    @Throws(IOException::class)
    fun writeNew(file: File, text: String) {
        try {
            file.writeText(text)
        } catch (e: IOException) {
            // Shared storage only lets apps write photos and videos into DCIM by path.
            folder.openOutput(file).use { it.write(text.toByteArray()) }
        }
    }

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

    companion object {
        /** The permissions [hasAccess] needs besides the folder. */
        val MEDIA_PERMISSIONS = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )

        fun canReadMedia(context: Context): Boolean =
            MEDIA_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
