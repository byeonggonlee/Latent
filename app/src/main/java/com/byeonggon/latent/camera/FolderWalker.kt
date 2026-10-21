package com.byeonggon.latent.camera

import android.util.Log
import com.byeonggon.latent.data.db.MediaKind
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** What the camera reports about one object, reduced to what a sync needs. */
internal data class RemoteObject(val name: String, val isFolder: Boolean, val sizeBytes: Long, val modifiedAt: Long)

/** Read access to one storage of a camera. Every call is a round trip taking tens of milliseconds. */
internal interface RemoteTree {
    /** Handles of the objects directly inside [parent], or null when the camera cannot list it. */
    fun childrenOf(parent: Int): IntArray?

    fun objectOf(handle: Int): RemoteObject?
}

internal class StorageListing(
    val files: List<RemoteFile>,
    val fingerprints: Map<String, String>,
    val skippedExtensions: Map<String, Int>,
    val foldersRead: Int,
    val foldersUnchanged: Int,
    val foldersOutsideDates: Int,
)

/**
 * Collects the media files of one storage while asking the camera about as few objects as possible.
 *
 * Two kinds of folders are passed over: those named after a date outside [ListingRequest.dates], and
 * those that still look exactly like they did when all their files were imported.
 */
internal fun listStorage(
    tree: RemoteTree,
    rootHandle: Int,
    storageName: String,
    request: ListingRequest,
    onFound: (Int) -> Unit,
): StorageListing {
    val files = ArrayList<RemoteFile>()
    val fingerprints = HashMap<String, String>()
    val skippedExtensions = sortedMapOf<String, Int>()
    var foldersRead = 0
    var foldersUnchanged = 0
    var foldersOutsideDates = 0

    val pending = ArrayDeque<Folder>().apply { add(Folder(rootHandle, "$storageName:/", date = null)) }
    // A camera that lists a folder inside itself would have the walk go round for ever.
    val opened = HashSet<Int>()
    while (pending.isNotEmpty()) {
        val folder = pending.removeLast()
        if (!opened.add(folder.handle)) {
            Log.w(SYNC_LOG_TAG, "${folder.path} was listed before; not opened again")
            continue
        }
        if (request.dates != null && folder.date != null && folder.date !in request.dates) {
            foldersOutsideDates++
            continue
        }
        val handles = tree.childrenOf(folder.handle)
        if (handles == null) {
            Log.w(SYNC_LOG_TAG, "Cannot list ${folder.path}")
            continue
        }
        if (handles.isEmpty()) continue

        // Looked up at most once each, so checking the fingerprint first costs nothing if the folder is then read.
        val fetched = HashMap<Int, RemoteObject?>()
        fun objectOf(handle: Int): RemoteObject? {
            if (handle !in fetched) fetched[handle] = tree.objectOf(handle)
            return fetched[handle]
        }

        val fingerprint = fingerprintOf(handles.size, objectOf(handles.first()), objectOf(handles.last()))
        if (fingerprint != null && fingerprint == request.importedFolders[folder.path]) {
            Log.d(SYNC_LOG_TAG, "${folder.path}: ${handles.size} objects, unchanged since they were imported")
            foldersUnchanged++
            continue
        }

        Log.d(SYNC_LOG_TAG, "${folder.path}: ${handles.size} objects")
        foldersRead++
        var onlyReadableFiles = true
        for (handle in handles) {
            val item = objectOf(handle)
            if (item == null) {
                skippedExtensions.merge(UNREADABLE, 1, Int::plus)
                onlyReadableFiles = false
                continue
            }
            // The name becomes a file name on the phone: nothing that leads out of the folder it is put in.
            if (!isSafeName(item.name)) {
                skippedExtensions.merge(UNSAFE_NAME, 1, Int::plus)
                onlyReadableFiles = false
                continue
            }
            if (item.isFolder) {
                pending.add(Folder(handle, "${folder.path}${item.name}/", dateOf(item.name)))
                onlyReadableFiles = false
                continue
            }
            val kind = MediaKind.fromFileName(item.name)
            if (kind == null) {
                val extension = item.name.substringAfterLast('.', "").lowercase().ifEmpty { NO_EXTENSION }
                skippedExtensions.merge(extension, 1, Int::plus)
                continue
            }
            files += RemoteFile(handle, item.name, item.sizeBytes, item.modifiedAt, kind, folder.path)
            onFound(files.size)
        }
        // A fingerprint only vouches for a plain folder of files; anything else is read again every time.
        if (onlyReadableFiles && fingerprint != null) fingerprints[folder.path] = fingerprint
    }
    return StorageListing(files, fingerprints, skippedExtensions, foldersRead, foldersUnchanged, foldersOutsideDates)
}

private class Folder(val handle: Int, val path: String, val date: LocalDate?)

/**
 * Describes a folder by its size and its first and last object. Cameras list files in shooting order, so
 * a new shot shows up as a different count or a different last object without reading everything between.
 *
 * Times only count by their position within a quarter hour: the rest shifts with the phone's time zone
 * (see [ImportedFiles]), and a folder must not look changed just because the phone travelled.
 */
private fun fingerprintOf(count: Int, first: RemoteObject?, last: RemoteObject?): String? {
    if (first == null || last == null || first.isFolder || last.isFolder) return null
    return listOf(
        count,
        first.name, first.sizeBytes, first.modifiedAt.mod(ZONE_OFFSET_STEP_MS),
        last.name, last.sizeBytes, last.modifiedAt.mod(ZONE_OFFSET_STEP_MS),
    ).joinToString("|")
}

/** Sony cameras expose one folder per shooting day, named like "2024-12-08". */
private fun dateOf(folderName: String): LocalDate? = try {
    LocalDate.parse(folderName)
} catch (e: DateTimeParseException) {
    null
}

/** Whether [name] can only name a file inside the folder it is put in. */
internal fun isSafeName(name: String): Boolean =
    name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }

private const val UNREADABLE = "(unreadable)"
private const val NO_EXTENSION = "(none)"
private const val UNSAFE_NAME = "(unsafe name)"
