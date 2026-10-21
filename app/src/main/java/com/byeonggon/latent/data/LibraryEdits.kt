package com.byeonggon.latent.data

import com.byeonggon.latent.data.db.EditVersionDao
import com.byeonggon.latent.data.db.FilterDao
import com.byeonggon.latent.data.db.FilterEntity
import com.byeonggon.latent.data.db.LutDao
import com.byeonggon.latent.data.db.LutEntity
import com.byeonggon.latent.data.db.MediaDao
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.CubeLut
import com.byeonggon.latent.enhance.Filter
import com.byeonggon.latent.enhance.Filters
import com.byeonggon.latent.enhance.ImportedPreset
import com.byeonggon.latent.enhance.LightroomPreset
import com.byeonggon.latent.enhance.LutStore
import com.byeonggon.latent.enhance.MaskStore
import com.byeonggon.latent.enhance.UnusableLutException
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.enhance.PhotoEnhancer
import com.byeonggon.latent.location.GeoPoint
import com.byeonggon.latent.location.GpsTags
import com.byeonggon.latent.location.Geotagger
import com.byeonggon.latent.raw.RawCache
import com.byeonggon.latent.raw.RawRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import java.io.InputStream

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
    private val lutDao: LutDao,
    private val versionDao: EditVersionDao,
    /** What the copies made here are like, asked for each time so that the settings are followed. */
    private val exportOptions: suspend () -> ExportOptions = { ExportOptions() },
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

    /**
     * Keeps the look of the Lightroom preset read from [input] as a filter of the user's own, under the
     * preset's name or else [fallbackName]. Null when [input] is no preset, or has nothing of a look to take.
     */
    suspend fun importPreset(input: InputStream, fallbackName: String): ImportedPreset? {
        val preset = LightroomPreset.parse(input) ?: return null
        if (!preset.edit.changesColors) return null
        saveFilter((preset.name?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName).take(MOST_NAME), preset.edit)
        return preset
    }

    /** Deletes a filter the user saved; the ones that come with the app stay. */
    suspend fun deleteFilter(filter: Filter) {
        if (filter.custom) filter.id.removePrefix(CUSTOM_PREFIX).toLongOrNull()?.let { filterDao.delete(it) }
    }

    /** The LUTs the person imported, in the order they came. */
    val luts: Flow<List<LutEntity>> = lutDao.observeAll()

    /**
     * Keeps the 3D LUT read from [input], to give photos its look, under the title it gives itself or else
     * [fallbackName]; the same file imported again is the same LUT. Throws [UnusableLutException], which
     * says why, when it is no LUT that can be used.
     */
    @Throws(UnusableLutException::class, IOException::class)
    suspend fun importLut(input: InputStream, fallbackName: String): LutEntity {
        val bytes = CubeLut.bytesOf(input)
        val cube = CubeLut.parse(bytes)
        val id = LutStore.idOf(bytes)
        LutStore.save(id, cube.lut)
        val name = (cube.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName).take(MOST_NAME)
        lutDao.insert(LutEntity(name = name, size = cube.lut.size, hash = id, createdAt = System.currentTimeMillis()))
        return lutDao.byHash(id) ?: throw IOException("The LUT $id was not kept")
    }

    /**
     * Takes [lut] off the list. Its table stays for as long as a photo's edit, a kept version or a filter
     * uses it, so that photos keep their look; what nothing uses any more is deleted.
     */
    suspend fun deleteLut(lut: LutEntity) {
        lutDao.delete(lut.hash)
        for (id in LutStore.ids()) {
            if (lutDao.byHash(id) == null && !isLutInUse(id)) LutStore.delete(id)
        }
    }

    /**
     * Deletes the outlines of detected masks that no photo's edit and no kept version uses any more. One is
     * left alone for a day after it was made: until then an editor may still be able to undo back to it,
     * or its edit may be waiting to be saved. Filters never hold masks.
     */
    suspend fun forgetUnusedMasks(now: Long = System.currentTimeMillis()) {
        for ((id, made) in MaskStore.kept()) {
            if (now - made < UNUSED_MASK_KEPT) continue
            val pattern = "%\"$id\"%"
            if (mediaDao.countEditsLike(pattern) == 0 && versionDao.countLike(pattern) == 0) MaskStore.delete(id)
        }
    }

    private suspend fun isLutInUse(id: String): Boolean {
        // Edits are kept as JSON text, where a LUT is named by its id in quotes.
        val pattern = "%\"$id\"%"
        return mediaDao.countEditsLike(pattern) > 0 || versionDao.countLike(pattern) > 0 || filterDao.countLike(pattern) > 0
    }

    /**
     * The picture an edit of [item] starts from: the original, also when the file itself is an edited version.
     * Throws when the original is there but cannot be read.
     */
    @Throws(IOException::class)
    fun originalOf(item: MediaItemEntity): File {
        val file = File(item.path)
        if (item.origin == MediaOrigin.PHONE) return file
        // One that only the granted folder reaches, kept by another installation, is made the app's own to be read.
        val original = storage.originalOf(file)
        if (storage.makeOwn(original)) return original
        // Starting from the edited picture instead would show one thing and save another.
        if (storage.exists(original)) throw IOException("Cannot read the original of ${file.name}")
        return file
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
        if (item.origin == MediaOrigin.PHONE || item.isHeif) {
            // The photo is another app's, or one that cannot be written, and stays as it is; an edit that changes nothing is no edit.
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
        // The edit is made from the kept original, read by its path: one another installation kept becomes the app's.
        val original = storage.originalOf(photo)
        if (!storage.makeOwn(original) && storage.exists(original)) throw IOException("Cannot read the original of ${photo.name}")
        PhotoEnhancer.saveEdited(photo, original, edit, storage)
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
        val source = File(item.path)
        // Next to the raw file if it is the library's; a raw file of another app is not written beside.
        val target = if (item.origin == MediaOrigin.PHONE) {
            storage.targetFor(source.nameWithoutExtension + "_developed.jpg", item.capturedAt)
        } else {
            storage.siblingFor(source, suffix = "_developed", extension = "jpg")
        }
        val edit = editOf(item) ?: PhotoEdit()
        val quality = exportOptions().quality
        // The export decodes the file itself, in the cache's turn: the cache lets go of its copy, and no other decode
        // runs beside it.
        rawCache.decodingAlone { RawRenderer.export(source, target, edit, quality) }
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
                origin = MediaOrigin.LIBRARY,
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
        PhotoEnhancer.exportEdited(source, target, editOf(item) ?: PhotoEdit(), exportOptions().quality)
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
     * pixels. A raw file is developed with its edit; with no [longSide], a JPEG file is shared as it is,
     * unless [forceCopy] asks for a copy of it (for an app that is to edit it, which keeps its place) or the
     * settings say the place is to be left out, which a copy is made for.
     */
    @Throws(IOException::class)
    suspend fun shareCopy(item: MediaItemEntity, longSide: Int?, alongsideOthers: Boolean = false, forceCopy: Boolean = false): File {
        val source = File(item.path)
        val options = exportOptions()
        val leaveOutPlace = options.removeLocation && !forceCopy
        val phoneEdit = if (item.origin == MediaOrigin.PHONE || item.isHeif) editOf(item) else null
        if (item.kind != MediaKind.RAW && longSide == null && phoneEdit == null) {
            if (!forceCopy && !leaveOutPlace) return source
            // A copy of the file as it is, for an app that may do what it likes with it.
            return (if (alongsideOthers) storage.sharedFile(source.name) else storage.shareFile(source.name)).also {
                source.copyTo(it, overwrite = true)
                if (leaveOutPlace) GpsTags.remove(it)
            }
        }
        val name = source.nameWithoutExtension + ".jpg"
        // One share at a time: copies made for an earlier one are cleared away, unless this is one of several.
        val target = if (alongsideOthers) storage.sharedFile(name) else storage.shareFile(name)
        if (item.kind == MediaKind.RAW) {
            val edit = editOf(item) ?: PhotoEdit()
            if (longSide == null) {
                rawCache.decodingAlone { RawRenderer.export(source, target, edit, options.quality) }
            } else {
                // Half the sensor's resolution is as large as a reduced copy gets; it is turned upright on the way.
                val bitmap = RawRenderer.preview(rawCache.read(source), edit)
                PhotoEnhancer.writeSmaller(bitmap, target, longSide, options.quality) { PhotoEnhancer.copyExif(source, target, upright = true) }
            }
        } else if (phoneEdit != null) {
            // The photo is edited from its own file into the copy; a smaller one is made from that.
            if (longSide == null) {
                PhotoEnhancer.exportEdited(source, target, phoneEdit, options.quality)
            } else {
                val full = storage.sharedFile(".full-" + name)
                try {
                    PhotoEnhancer.exportEdited(source, full, phoneEdit, options.quality)
                    PhotoEnhancer.writeSmaller(full, target, longSide, options.quality)
                } finally {
                    full.delete()
                }
            }
        } else {
            PhotoEnhancer.writeSmaller(source, target, longSide!!, options.quality)
        }
        val latitude = item.latitude
        val longitude = item.longitude
        if (leaveOutPlace) {
            GpsTags.remove(target)
        } else if (latitude != null && longitude != null) {
            geotagger.writeExif(target, GeoPoint(latitude, longitude, item.altitude))
        }
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
        const val MOST_NAME = 60
        const val UNUSED_MASK_KEPT = 24 * 60 * 60 * 1000L
    }
}
