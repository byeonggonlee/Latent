package com.byeonggon.latent.camera

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import java.io.File
import java.io.IOException

/** What the id of every folder source starts with: see [isFolderCamera]. */
private const val FOLDER_CAMERA = "folder:"

/**
 * Whether [cameraId] is that of a card in a card reader or of a camera read as a drive, which cannot say
 * which camera wrote its files.
 */
internal fun isFolderCamera(cameraId: String) = cameraId.startsWith(FOLDER_CAMERA)

/**
 * A camera's card in a card reader, or a camera connected as a drive (Mass Storage), read through the folder
 * the person picked once in the system's folder dialog: [tree], whose access the app keeps. Its files are
 * read as a camera shows them over MTP: the folders where a camera keeps thumbnails and smaller copies of
 * its videos are left out, and so is what a computer or the card's file system keeps there.
 *
 * All calls block and must run off the main thread.
 */
class FolderCameraSource(private val context: Context, private val tree: Uri) : CameraSource {
    private val resolver get() = context.contentResolver

    /** The documents of the last listing, by the handle given out for each. */
    private val documents = ArrayList<String>()
    private val objects = ArrayList<RemoteObject>()

    override fun open(): CameraInfo {
        val root = describe(rootId()) ?: throw IOException("Cannot read the folder; is the card still in?")
        Log.i(SYNC_LOG_TAG, "Opened the folder ${rootId()} (${root.name})")
        return CameraInfo(manufacturer = "", model = root.name, serial = FOLDER_CAMERA + rootId())
    }

    override fun listFiles(request: ListingRequest, onFound: (Int) -> Unit): Listing {
        documents.clear()
        objects.clear()
        val root = handleOf(rootId(), RemoteObject(rootId(), isFolder = true, sizeBytes = 0, modifiedAt = 0))
        val folder = object : RemoteTree {
            override fun childrenOf(parent: Int): IntArray? =
                children(documents[parent])?.let { found -> IntArray(found.size) { handleOf(found[it].first, found[it].second) } }

            override fun objectOf(handle: Int): RemoteObject? = objects.getOrNull(handle)
        }
        val storage = listStorage(folder, root, STORAGE, request, onFound)
        Log.i(
            SYNC_LOG_TAG,
            "Folder: ${storage.files.size} media files, ${storage.foldersRead} folders read, " +
                "${storage.foldersUnchanged} unchanged, ${storage.foldersOutsideDates} outside the dates; " +
                "skipped by extension: ${storage.skippedExtensions}",
        )
        return Listing(storage.files, storage.fingerprints)
    }

    override fun download(file: RemoteFile, destination: File): Boolean {
        val id = documents.getOrNull(file.handle) ?: return false
        val input = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id)) ?: return false
        input.use { source -> destination.outputStream().use { source.copyTo(it) } }
        return true
    }

    override fun close() {
        documents.clear()
        objects.clear()
    }

    private fun rootId(): String = DocumentsContract.getTreeDocumentId(tree)

    private fun handleOf(id: String, item: RemoteObject): Int {
        documents += id
        objects += item
        return documents.size - 1
    }

    private fun describe(id: String): RemoteObject? = try {
        resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id), COLUMNS, null, null)
            ?.use { if (it.moveToFirst()) it.toRemoteObject() else null }
    } catch (e: Exception) {
        // A card taken out shows up as an exception, of a kind that depends on the provider.
        Log.w(SYNC_LOG_TAG, "Cannot read $id", e)
        null
    }

    /** The documents in the folder [id] and what each is, less what a camera does not show; null when it cannot be listed. */
    private fun children(id: String): List<Pair<String, RemoteObject>>? = try {
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), COLUMNS, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val item = cursor.toRemoteObject()
                    // Names starting with a dot are what a computer leaves behind, such as a Mac's "._DSC00001.JPG".
                    if (item.name.startsWith(".") || item.isFolder && item.name.uppercase() in LEFT_OUT) continue
                    add(cursor.getString(0) to item)
                }
            }
        }
    } catch (e: Exception) {
        Log.w(SYNC_LOG_TAG, "Cannot list $id", e)
        null
    }

    private fun Cursor.toRemoteObject() = RemoteObject(
        name = getString(1).orEmpty(),
        isFolder = getString(2) == Document.MIME_TYPE_DIR,
        sizeBytes = if (isNull(3)) RemoteFile.UNKNOWN_SIZE else getLong(3),
        modifiedAt = if (isNull(4)) 0 else getLong(4),
    )

    private companion object {
        /** How the files' folders are named, as a camera's storage is: "card:/DCIM/100MSDCF/". */
        const val STORAGE = "card"

        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        /**
         * Folders a camera keeps for itself, which it does not show over MTP: Sony's thumbnails (THMBNL) and
         * smaller copies (SUB) of its videos, AVCHD's index, print orders (MISC); and what the card's file
         * system or a phone keeps there.
         */
        val LEFT_OUT = setOf("THMBNL", "SUB", "CACHE", "AVF_INFO", "GENERAL", "MISC", "LOST.DIR", "SYSTEM VOLUME INFORMATION", "ANDROID")
    }
}
