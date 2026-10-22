package com.byeonggon.latent.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The library folder as the person handed it to the app in the system's folder picker. Shared storage
 * lets an app work on the photos and videos it made itself through their paths, but nothing else in
 * DCIM: not the location sidecars beside raw files, and not photos that another installation of the
 * app made. Those are reached through here, by the same paths.
 */
class GrantedFolder(private val context: Context, private val root: File) {
    private val resolver get() = context.contentResolver

    /** Where the folder picker should open, so that the person only has to confirm. */
    val pickerStart: Uri? get() = documentIdOf(root)?.let { DocumentsContract.buildDocumentUri(AUTHORITY, it) }

    fun isGranted(): Boolean = grantedTree() != null

    /** Keeps the access given to [tree] if the library is inside it. Returns whether it is. */
    fun accept(tree: Uri): Boolean {
        if (!covers(tree)) return false
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return true
    }

    fun exists(file: File): Boolean = try {
        val uri = uriOf(file) ?: return false
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        // A missing document is reported as an exception, of a kind that depends on the provider.
        false
    }

    /** When [file] was last changed, in milliseconds since 1970, or 0 when that cannot be told. */
    fun lastModified(file: File): Long = try {
        val uri = uriOf(file) ?: return 0
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0 } ?: 0
    } catch (e: Exception) {
        0
    }

    /** Deletes a file, or a folder with all it holds. Returns whether it was deleted. */
    fun delete(file: File): Boolean = try {
        uriOf(file)?.let { DocumentsContract.deleteDocument(resolver, it) } ?: false
    } catch (e: Exception) {
        false
    }

    /**
     * Moves [from] to [to], which must not exist yet; its folder is made if needed. Returns whether it moved. One that
     * does not get there keeps its own name where it is, so that what looks for it by that name still finds it.
     */
    fun move(from: File, to: File): Boolean {
        val fromFolder = from.parentFile
        val toFolder = to.parentFile
        if (fromFolder == null || toFolder == null) return false
        toFolder.mkdirs()
        val document = uriOf(from) ?: return false
        if (from.name == to.name) return fromFolder == toFolder || moveDocument(document, fromFolder, toFolder)
        // Renamed where it is first: the target folder may already hold a file with the old name.
        val renamed = try {
            DocumentsContract.renameDocument(resolver, document, to.name)
        } catch (e: Exception) {
            null
        } ?: return false
        // A provider gives a file a name of its own choosing when the one asked for is taken; that is no move to [to].
        val moved = DocumentsContract.getDocumentId(renamed).endsWith("/${to.name}") &&
            (fromFolder == toFolder || moveDocument(renamed, fromFolder, toFolder))
        if (!moved) {
            try {
                DocumentsContract.renameDocument(resolver, renamed, from.name)
            } catch (e: Exception) {
                // Left under the other name; nothing more can be done about it here.
            }
        }
        return moved
    }

    private fun moveDocument(document: Uri, fromFolder: File, toFolder: File): Boolean = try {
        val source = uriOf(fromFolder)
        val target = uriOf(toFolder)
        source != null && target != null && DocumentsContract.moveDocument(resolver, document, source, target) != null
    } catch (e: Exception) {
        false
    }

    /** Opens [file] for writing over what it holds, making it first if it does not exist. */
    @Throws(IOException::class)
    fun openOutput(file: File): OutputStream {
        val uri = if (exists(file)) {
            uriOf(file)
        } else {
            val folder = file.parentFile?.also { it.mkdirs() }?.let(::uriOf) ?: throw IOException("Cannot reach ${file.name}")
            // Any other type would have the provider add an extension of its own choosing to the name.
            DocumentsContract.createDocument(resolver, folder, "application/octet-stream", file.name)
        }
        return uri?.let { resolver.openOutputStream(it, "wt") } ?: throw IOException("Cannot write ${file.name}")
    }

    /** Opens [file] to be read. */
    @Throws(IOException::class)
    fun openInput(file: File): InputStream =
        uriOf(file)?.let { resolver.openInputStream(it) } ?: throw IOException("Cannot read ${file.name}")

    /** Opens [file] to be read and changed in place. */
    @Throws(IOException::class)
    fun openReadWrite(file: File): ParcelFileDescriptor =
        uriOf(file)?.let { resolver.openFileDescriptor(it, "rw") } ?: throw IOException("Cannot open ${file.name}")

    private fun grantedTree(): Uri? = resolver.persistedUriPermissions
        .filter { it.isReadPermission && it.isWritePermission }
        .map { it.uri }
        .firstOrNull(::covers)

    private fun covers(tree: Uri): Boolean {
        if (tree.authority != AUTHORITY) return false
        val treeId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val rootId = documentIdOf(root) ?: return false
        return rootId == treeId || rootId.startsWith(if (treeId.endsWith(":")) treeId else "$treeId/")
    }

    private fun uriOf(file: File): Uri? {
        val tree = grantedTree() ?: return null
        val id = documentIdOf(file) ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    /** The system's name for a file on the phone's own storage: "primary:" and its path from there. */
    private fun documentIdOf(file: File): String? {
        // The storage that holds DCIM, i.e. /storage/emulated/0 for the phone's owner.
        val volume = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).parentFile ?: return null
        val path = file.absoluteFile.normalize().path
        if (path != volume.path && !path.startsWith(volume.path + File.separator)) return null
        return "primary:" + path.removePrefix(volume.path).removePrefix(File.separator)
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
    }
}
