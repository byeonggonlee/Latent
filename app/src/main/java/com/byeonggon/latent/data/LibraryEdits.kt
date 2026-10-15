package com.byeonggon.latent.data

import com.byeonggon.latent.data.db.FilterDao
import com.byeonggon.latent.data.db.FilterEntity
import com.byeonggon.latent.data.db.MediaDao
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.Filter
import com.byeonggon.latent.enhance.Filters
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.enhance.PhotoEnhancer
import com.byeonggon.latent.location.GeoPoint
import com.byeonggon.latent.location.Geotagger
import com.byeonggon.latent.raw.RawCache
import com.byeonggon.latent.raw.RawRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/**
 * Puts edits into effect on library files and takes them away again.
 *
 * A JPEG is replaced by its edited version while its original is kept aside, so that other apps see
 * the edit too. A raw file, and any photo that another app made and the library merely lists, only has
 * its settings stored; they are applied whenever it is shown or exported. Everything here works on
 * files and must run off the main thread.
 */
class LibraryEdits(
    private val storage: LibraryStorage,
    private val mediaDao: MediaDao,
    private val filterDao: FilterDao,
    private val geotagger: Geotagger,
    private val rawCache: RawCache,
) {
    /** Tones and colours copied from one photo, to be given to others. */
    val copied = MutableStateFlow<PhotoEdit?>(null)

    /** The filters that come with the app, followed by those the user saved. */
    val filters: Flow<List<Filter>> = filterDao.observeAll().map { saved ->
        Filters.BUILT_IN + saved.mapNotNull { filter ->
            PhotoEdit.fromJson(filter.look)?.let { Filter(CUSTOM_PREFIX + filter.id, filter.name, it, custom = true) }
        }
    }

    /** Saves the look of [edit] as a filter of the user's own. */
    suspend fun saveFilter(name: String, edit: PhotoEdit) {
        filterDao.insert(FilterEntity(name = name, look = PhotoEdit().withLookOf(edit).toJson(), createdAt = System.currentTimeMillis()))
    }

    /** Deletes a filter the user saved; the ones that come with the app stay. */
    suspend fun deleteFilter(filter: Filter) {
        if (filter.custom) filter.id.removePrefix(CUSTOM_PREFIX).toLongOrNull()?.let { filterDao.delete(it) }
    }

    /** The picture an edit of [item] starts from: the original, also when the file itself is an edited version. */
    fun originalOf(item: MediaItemEntity): File {
        val file = File(item.path)
        if (item.origin == MediaOrigin.PHONE) return file
        return storage.originalOf(file).takeIf { it.exists() } ?: file
    }

    fun isEdited(item: MediaItemEntity): Boolean = when {
        item.kind == MediaKind.VIDEO -> false
        item.editsAsSettings -> item.edit != null
        else -> storage.originalOf(File(item.path)).exists()
    }

    /** The edit in effect on [item], or null if it is as the camera made it. */
    fun editOf(item: MediaItemEntity): PhotoEdit? {
        // Without the original that an edited JPEG keeps, the file is not edited, whatever is stored for it.
        if (!isEdited(item)) return null
        // A JPEG edited before edits were stored had auto enhance applied, which was all there was.
        return PhotoEdit.fromJson(item.edit) ?: PhotoEdit(autoEnhance = true)
    }

    /** Puts [edit] into effect on [item], in place of any earlier edit. */
    @Throws(IOException::class)
    suspend fun apply(item: MediaItemEntity, edit: PhotoEdit) {
        if (item.origin == MediaOrigin.PHONE) {
            // The photo is another app's and stays as it is; an edit that changes nothing is no edit.
            mediaDao.setEdit(item.id, edit.takeIf { it.changesPicture }?.toJson())
            return
        }
        if (item.kind == MediaKind.RAW) {
            mediaDao.setEdit(item.id, edit.toJson())
            return
        }
        if (!edit.changesPicture) {
            remove(item)
            return
        }
        val photo = File(item.path)
        PhotoEnhancer.saveEdited(photo, storage.originalOf(photo), edit, storage)
        storage.publish(photo)
        mediaDao.setSize(item.id, photo.length())
        mediaDao.setEdit(item.id, edit.toJson())
    }

    /**
     * Develops a raw file at full resolution, with the edit in effect on it, into a new JPEG next to
     * it, and adds that to the library. Returns the new file.
     */
    @Throws(IOException::class)
    suspend fun exportRaw(item: MediaItemEntity): File {
        // The export decodes the file itself; holding a second copy would only cost memory.
        rawCache.clear()
        val source = File(item.path)
        val target = storage.siblingFor(source, suffix = "_developed", extension = "jpg")
        RawRenderer.export(source, target, editOf(item) ?: PhotoEdit())
        val latitude = item.latitude
        val longitude = item.longitude
        if (latitude != null && longitude != null) geotagger.writeExif(target, GeoPoint(latitude, longitude, item.altitude))
        storage.publish(target)
        mediaDao.insert(
            item.copy(
                id = 0,
                path = target.absolutePath,
                fileName = target.name,
                kind = MediaKind.PHOTO,
                sizeBytes = target.length(),
                importedAt = System.currentTimeMillis(),
                sourceKey = null,
                edit = null,
            ),
        )
        return target
    }

    /**
     * Makes an edited copy, a new JPEG in the library, of a photo that is listed from the phone, which
     * itself stays untouched. Returns the new file.
     */
    @Throws(IOException::class)
    suspend fun saveCopy(item: MediaItemEntity): File {
        val source = File(item.path)
        val target = storage.targetFor(source.nameWithoutExtension + "_edited.jpg", item.capturedAt)
        PhotoEnhancer.exportEdited(source, target, editOf(item) ?: PhotoEdit())
        val latitude = item.latitude
        val longitude = item.longitude
        if (latitude != null && longitude != null) geotagger.writeExif(target, GeoPoint(latitude, longitude, item.altitude))
        storage.publish(target)
        mediaDao.insert(
            item.copy(
                id = 0,
                path = target.absolutePath,
                fileName = target.name,
                sizeBytes = target.length(),
                importedAt = System.currentTimeMillis(),
                sourceKey = null,
                edit = null,
                origin = MediaOrigin.LIBRARY,
            ),
        )
        return target
    }

    /**
     * A copy of [item] to send to another app: a JPEG at its full size, or no larger than [longSide]
     * pixels. A raw file is developed with its edit; with no [longSide], a JPEG file is shared as it is.
     */
    @Throws(IOException::class)
    suspend fun shareCopy(item: MediaItemEntity, longSide: Int?, alongsideOthers: Boolean = false): File {
        val source = File(item.path)
        val phoneEdit = if (item.origin == MediaOrigin.PHONE) editOf(item) else null
        if (item.kind != MediaKind.RAW && longSide == null && phoneEdit == null) return source
        val name = source.nameWithoutExtension + ".jpg"
        // One share at a time: copies made for an earlier one are cleared away, unless this is one of several.
        val target = if (alongsideOthers) storage.sharedFile(name) else storage.shareFile(name)
        if (item.kind == MediaKind.RAW) {
            val edit = editOf(item) ?: PhotoEdit()
            if (longSide == null) {
                rawCache.clear()
                RawRenderer.export(source, target, edit)
            } else {
                // Half the sensor's resolution is as large as a reduced copy gets; it is turned upright on the way.
                val bitmap = RawRenderer.preview(rawCache.read(source), edit)
                PhotoEnhancer.writeSmaller(bitmap, target, longSide) { PhotoEnhancer.copyExif(source, target, upright = true) }
            }
        } else if (phoneEdit != null) {
            // The photo is edited from its own file into the copy; a smaller one is made from that.
            if (longSide == null) {
                PhotoEnhancer.exportEdited(source, target, phoneEdit)
            } else {
                val full = storage.sharedFile(".full-" + name)
                try {
                    PhotoEnhancer.exportEdited(source, full, phoneEdit)
                    PhotoEnhancer.writeSmaller(full, target, longSide)
                } finally {
                    full.delete()
                }
            }
        } else {
            PhotoEnhancer.writeSmaller(source, target, longSide!!)
        }
        val latitude = item.latitude
        val longitude = item.longitude
        if (latitude != null && longitude != null) geotagger.writeExif(target, GeoPoint(latitude, longitude, item.altitude))
        return target
    }

    /** Takes the edit of [item] away: a JPEG gets its original back. */
    @Throws(IOException::class)
    suspend fun remove(item: MediaItemEntity) {
        if (item.kind == MediaKind.PHOTO && item.origin == MediaOrigin.LIBRARY) {
            val photo = File(item.path)
            val backup = storage.originalOf(photo)
            if (storage.exists(backup)) {
                PhotoEnhancer.restoreOriginal(photo, backup, storage)
                storage.publish(photo)
                mediaDao.setSize(item.id, photo.length())
            }
        }
        mediaDao.setEdit(item.id, null)
    }

    private companion object {
        const val CUSTOM_PREFIX = "custom-"
    }
}
