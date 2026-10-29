package com.byeonggon.latent.backup

import com.byeonggon.latent.camera.ZONE_OFFSET_STEP_MS
import com.byeonggon.latent.data.PhoneLibrary
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.PhotoEdit
import java.io.File
import kotlin.math.abs

/**
 * Where a photo is, in terms that hold on any phone: absolute paths differ between phones, users and storage
 * volumes. A library file is placed from the library's folder ("2026/2026-10-08/DSC00001.JPG"), a file of
 * another app from its storage volume, as MediaStore puts it ("DCIM/Camera/20261008_1.jpg").
 */
internal class MediaPaths(libraryRoot: File, private val phoneRelative: (String) -> String = PhoneLibrary::relativePathOf) {
    private val libraryPrefix = libraryRoot.path.trimEnd('/') + "/"

    /** Null for a file outside where its origin keeps files, which can then only be found by its name. */
    fun of(item: MediaItemEntity): String? = when (item.origin) {
        MediaOrigin.LIBRARY -> item.path.takeIf { it.startsWith(libraryPrefix) }?.removePrefix(libraryPrefix)
        MediaOrigin.PHONE -> phoneRelative(item.path).takeUnless { it.startsWith("/") }
    }?.takeIf { it.isNotEmpty() }

    /** The folder, as the library offers it to choose, that a file of another app at [relative] lies in. */
    fun phoneFolderOf(relative: String): String {
        val folder = relative.substringBeforeLast('/', "")
        return PhoneLibrary.groupOf(if (folder.isEmpty()) "" else "$folder/")
    }
}

/**
 * Finds the photos of a backup among those on this phone, each at most once. The rule, and where it fails, is in
 * docs/BACKUP_FORMAT.md ("Finding the photos"): first the same place, then the same name and size.
 */
internal class MediaMatcher(targets: Collection<MediaItemEntity>, paths: MediaPaths) {
    private val byPath = HashMap<String, MediaItemEntity>()
    private val byFile = HashMap<String, MutableList<MediaItemEntity>>()
    private val claimed = HashSet<Long>()

    init {
        for (target in targets.sortedBy { it.path }) {
            paths.of(target)?.let { byPath.putIfAbsent(pathKey(target.origin, it), target) }
            byFile.getOrPut(fileKey(target.origin, target.kind, target.fileName, target.sizeBytes)) { ArrayList(1) } += target
        }
    }

    /** The photo at the place [record] was at, of its origin, unless another record took it. Its size may differ: an edit changes it. */
    fun atPath(record: MediaRecord): MediaItemEntity? {
        val path = record.path ?: return null
        val target = byPath[pathKey(record.origin, path)]?.takeIf { it.kind == record.kind } ?: return null
        return target.takeIf { claimed.add(it.id) }
    }

    /**
     * For a record not found at its place: a photo of its origin and kind with the same name and size, taken at the same
     * moment as far as clocks in different time zones can tell; of several, the nearest in time. One alone is not taken on
     * its name and size: camera file names come round again, and an uncompressed raw file's size is the camera's.
     */
    fun elsewhere(record: MediaRecord): MediaItemEntity? {
        val candidates = byFile[fileKey(record.origin, record.kind, record.name, record.size)]?.filter { it.id !in claimed }.orEmpty()
        val chosen = candidates.filter { sameMoment(it.capturedAt, record.capturedAt) }.minByOrNull { abs(it.capturedAt - record.capturedAt) }
            ?: return null
        claimed += chosen.id
        return chosen
    }

    private companion object {
        /** UTC-12 to UTC+14: the furthest apart two readings of one wall-clock time can be. */
        const val ZONE_SPAN_MS = 26 * 60 * 60_000L

        fun pathKey(origin: MediaOrigin, path: String) = "${origin.name}|$path"

        fun fileKey(origin: MediaOrigin, kind: MediaKind, name: String, size: Long) = "${origin.name}|${kind.name}|${name.lowercase()}|$size"

        /** A photo's time read in another zone is off by whole quarter hours. */
        fun sameMoment(a: Long, b: Long): Boolean {
            val shift = abs(a - b)
            return shift <= ZONE_SPAN_MS && shift % ZONE_OFFSET_STEP_MS == 0L
        }
    }
}

/** What an edited library JPEG adopted after a reinstall says of its edit: that there is one, not what it was. */
internal val UNKNOWN_EDIT: String = PhotoEdit().toJson()

/** A JPEG of the library carries its edit in the file, with its original kept aside; the stored edit only tells how it was made. */
internal val MediaItemEntity.editInFile: Boolean get() = !editsAsSettings && kind == MediaKind.PHOTO

/**
 * [target], the photo on this phone that [record] is, as a restore leaves it (see docs/BACKUP_FORMAT.md, "What a
 * restore changes"). [hasOriginal] says whether [target] keeps its original aside, which only matters for [editInFile].
 */
internal fun restoredOnto(target: MediaItemEntity, record: MediaRecord, hasOriginal: Boolean): MediaItemEntity {
    val edit = when {
        !target.editInFile -> record.edit
        // The file shows its own edit; what made it is only taken where the phone does not know.
        hasOriginal && record.edit != null && (target.edit == null || target.edit == UNKNOWN_EDIT) -> record.edit
        else -> target.edit
    }
    val located = record.geoSource != GeoSource.NONE && record.latitude != null && record.longitude != null
    return target.copy(
        rating = record.rating,
        flag = record.flag,
        edit = edit,
        latitude = if (located) record.latitude else target.latitude,
        longitude = if (located) record.longitude else target.longitude,
        altitude = if (located) record.altitude else target.altitude,
        geoSource = if (located) record.geoSource else target.geoSource,
        sourceKey = record.sourceKey ?: target.sourceKey,
        importedAt = record.importedAt.takeIf { it > 0 } ?: target.importedAt,
    )
}

/** Whether [restored] takes away something the person gave [target] on this phone: a rating, a flag, an edit or a place. */
internal fun replacesOwn(target: MediaItemEntity, restored: MediaItemEntity): Boolean =
    (target.rating != 0 && restored.rating != target.rating) ||
        (target.flag != 0 && restored.flag != target.flag) ||
        (target.edit != null && target.edit != UNKNOWN_EDIT && !sameEdit(restored.edit, target.edit)) ||
        (target.geoSource != GeoSource.NONE && (apart(restored.latitude, target.latitude) || apart(restored.longitude, target.longitude)))

// The same edit can be written apart: 0.1.0 wrote it without the render version that 0.2.0 adds when it saves one again.
private fun sameEdit(a: String?, b: String?): Boolean =
    a == b || (a != null && b != null && PhotoEdit.fromJson(a)?.let { it == PhotoEdit.fromJson(b) } == true)

// A place written into a file's Exif and read back is rounded; a metre or so is the same place.
private fun apart(a: Double?, b: Double?): Boolean = a == null || b == null || abs(a - b) > 1e-5
