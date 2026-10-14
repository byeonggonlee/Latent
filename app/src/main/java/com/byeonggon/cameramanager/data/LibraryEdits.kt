package com.byeonggon.cameramanager.data

import com.byeonggon.cameramanager.data.db.FilterDao
import com.byeonggon.cameramanager.data.db.FilterEntity
import com.byeonggon.cameramanager.data.db.MediaDao
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.enhance.Filter
import com.byeonggon.cameramanager.enhance.Filters
import com.byeonggon.cameramanager.enhance.PhotoEdit
import com.byeonggon.cameramanager.enhance.PhotoEnhancer
import com.byeonggon.cameramanager.location.GeoPoint
import com.byeonggon.cameramanager.location.Geotagger
import com.byeonggon.cameramanager.raw.RawCache
import com.byeonggon.cameramanager.raw.RawRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/**
 * Puts edits into effect on library files and takes them away again.
 *
 * A JPEG is replaced by its edited version while its original is kept aside, so that other apps see
 * the edit too. A raw file only has its settings stored; they are applied whenever it is shown or
 * exported. Everything here works on files and must run off the main thread.
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
        return storage.originalOf(file).takeIf { it.exists() } ?: file
    }

    fun isEdited(item: MediaItemEntity): Boolean = when (item.kind) {
        MediaKind.PHOTO -> storage.originalOf(File(item.path)).exists()
        MediaKind.RAW -> item.edit != null
        MediaKind.VIDEO -> false
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
        if (item.kind == MediaKind.RAW) {
            mediaDao.setEdit(item.id, edit.toJson())
            return
        }
        if (!edit.changesPicture) {
            remove(item)
            return
        }
        val photo = File(item.path)
        PhotoEnhancer.saveEdited(photo, storage.originalOf(photo), edit)
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
     * A copy of [item] to send to another app: a JPEG at its full size, or no larger than [longSide]
     * pixels. A raw file is developed with its edit; with no [longSide], a JPEG file is shared as it is.
     */
    @Throws(IOException::class)
    suspend fun shareCopy(item: MediaItemEntity, longSide: Int?): File {
        val source = File(item.path)
        if (item.kind == MediaKind.PHOTO && longSide == null) return source
        val target = storage.shareFile(source.nameWithoutExtension + ".jpg")
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
        if (item.kind == MediaKind.PHOTO) {
            val photo = File(item.path)
            val backup = storage.originalOf(photo)
            if (backup.exists()) {
                PhotoEnhancer.restoreOriginal(photo, backup)
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
