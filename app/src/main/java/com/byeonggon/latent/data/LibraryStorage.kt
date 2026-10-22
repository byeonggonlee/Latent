package com.byeonggon.latent.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import com.byeonggon.latent.BuildConfig
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.PhotoFiles
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex

/** The photo library on shared storage: DCIM/Latent/<year>/<date>/<file> (DCIM/Latent Debug/ for the debug build). */
class LibraryStorage(private val context: Context) : PhotoFiles {
    val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), BuildConfig.LIBRARY_FOLDER)
    // Folders whose names start with a dot are kept out of gallery apps.
    private val incoming = File(root, ".incoming")
    private val originals = File(root, ".originals")

    /** Reaches what shared storage does not let the app reach by path. */
    val folder = GrantedFolder(context, root)

    /**
     * Held while library files are moved, put in place or written over, and while the library is checked against
     * its folder: a file on its way from one place to another must not look gone, nor two edits of one photo meet.
     */
    val moving = Mutex()

    /**
     * Whether the app may read the photos and videos on shared storage, and was handed the library folder.
     * Reading covers photos another installation of the app made; it is also what lets photos keep their
     * locations when they are read.
     */
    fun hasAccess(): Boolean = canReadMedia(context) && folder.isGranted()

    /** Whether library files can be reached right now: access is granted and shared storage is mounted. */
    fun isAvailable(): Boolean = hasAccess() && Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    /** How many bytes shared storage has left for the library. */
    @Suppress("UsableSpace") // Shared storage is not what StorageManager frees by clearing caches.
    fun freeBytes(): Long = generateSequence(root) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L

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
        // What is in [to]'s place is only given up for a file that is there to take it.
        if (!exists(from)) throw IOException("No ${from.name} to put in place of ${to.name}")
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
     * It is copied beside [to] and takes that name only once whole: a move that breaks off never leaves part
     * of a picture where a kept original is looked for. Returns whether it moved.
     */
    private fun takeOver(from: File, to: File): Boolean = synchronized(takingOver) {
        val modifiedAt = from.lastModified()
        val copying = copyingOf(to)
        try {
            to.parentFile?.mkdirs()
            val input = if (from.canRead()) from.inputStream() else folder.openInput(from)
            input.use { source ->
                FileOutputStream(copying).use { target ->
                    source.copyTo(target)
                    target.fd.sync()
                }
            }
            if (modifiedAt > 0) copying.setLastModified(modifiedAt)
            if (!copying.renameTo(to)) throw IOException("Cannot put ${to.name} in place")
            // The copy is only given up while the file it was made from is still there.
            if (folder.delete(from) || !exists(from)) {
                true
            } else {
                to.delete()
                false
            }
        } catch (e: IOException) {
            copying.delete()
            false
        }
    }

    /** Guards [take]: two copies of one file made at once would write over each other. */
    private val takingOver = Any()

    /** Where an original stands once [take] has done what it can. */
    private enum class Kept {
        /** The app's own, read by its path. */
        OWN,

        /** There, but only the granted folder reaches it. */
        SHUT,

        /** Not there. */
        NONE,
    }

    /**
     * Makes [file] the app's own where it is, when only the granted folder reaches it: an original kept by
     * another installation of the app (one under the package name before, say) or copied in from another
     * phone. In a folder whose name starts with a dot, shared storage shuts such a file to the app's paths,
     * which is how the editor reads it. Returns whether the app can read it by its path now.
     */
    fun makeOwn(file: File): Boolean = take(file) == Kept.OWN

    /**
     * Whether [photo] keeps its original aside, made the app's own where it can be, as [makeOwn] does. A move
     * to the app's own that broke off is finished first: its whole copy is the original, waiting beside it.
     */
    fun keepsOriginal(photo: File): Boolean = take(originalOf(photo)) != Kept.NONE

    /**
     * The original [photo] keeps aside, in its place, for it to be moved or put back from there: made the app's
     * own where it can be, as [makeOwn] does, or else one the granted folder reaches. Null when none is kept.
     * Throws when one is kept but not in its place, a move to the app's own that could not be finished: what
     * would be moved along with it is then left where it is.
     */
    @Throws(IOException::class)
    fun keptOriginal(photo: File): File? {
        val original = originalOf(photo)
        return when (take(original)) {
            Kept.OWN -> original
            Kept.SHUT -> original.takeIf(::exists) ?: throw IOException("The original of ${photo.name} is not in its place")
            Kept.NONE -> null
        }
    }

    /**
     * Whether [photo] keeps its original aside, as far as the app's paths show: an original only the granted
     * folder reaches is not seen, a whole copy waiting to take its place is. For lists, where asking the
     * granted folder about every photo would take too long.
     */
    fun keepsOriginalByPath(photo: File): Boolean = originalOf(photo).let { it.exists() || copiedOf(it).exists() }

    // Still named as photos: shared storage only takes photos and videos in DCIM.
    private fun copyingOf(file: File) = File(file.parentFile, ".${file.nameWithoutExtension}-copying.${file.extension}")

    private fun copiedOf(file: File) = File(file.parentFile, ".${file.nameWithoutExtension}-copied.${file.extension}")

    /**
     * Takes [file] over as [makeOwn] says. It is copied next to itself, and the copy takes its place only once
     * whole: a move that breaks off never leaves part of a picture under its name, and the next call starts it
     * again or finishes it.
     */
    private fun take(file: File): Kept {
        if (file.canRead()) return Kept.OWN
        synchronized(takingOver) {
            if (file.canRead()) return Kept.OWN
            val copying = copyingOf(file)
            val copied = copiedOf(file)
            // A copy cut short is dropped; a whole one, made before the move broke off, finishes it.
            copying.delete()
            if (copied.exists()) {
                return if ((!folder.exists(file) || folder.delete(file)) && putInPlace(copied, file)) Kept.OWN else Kept.SHUT
            }
            if (!folder.exists(file)) return Kept.NONE
            return try {
                val modifiedAt = folder.lastModified(file)
                folder.openInput(file).use { source ->
                    FileOutputStream(copying).use { target ->
                        source.copyTo(target)
                        target.fd.sync()
                    }
                }
                if (modifiedAt > 0) copying.setLastModified(modifiedAt)
                if (copying.renameTo(copied) && folder.delete(file) && putInPlace(copied, file)) Kept.OWN else Kept.SHUT
            } catch (e: Exception) {
                copying.delete()
                Kept.SHUT
            }
        }
    }

    private fun putInPlace(copy: File, file: File) = copy.renameTo(file) || folder.move(copy, file)

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
