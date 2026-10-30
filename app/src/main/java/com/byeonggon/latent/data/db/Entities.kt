package com.byeonggon.latent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaKind {
    PHOTO,
    RAW,
    VIDEO;

    companion object {
        /** Returns null for files the library does not manage (camera sidecars, thumbnails, ...). */
        fun fromFileName(fileName: String): MediaKind? =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg", "heic", "heif", "hif" -> PHOTO
                "arw", "dng", "cr2" -> RAW
                "mp4", "mov", "mts", "m2ts" -> VIDEO
                else -> null
            }
    }
}

/** The extensions of HEIF pictures. */
val HEIF_EXTENSIONS = setOf("heic", "heif", "hif")

/** Whether a file with this name is a HEIF picture. */
fun isHeifName(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in HEIF_EXTENSIONS

const val FLAG_PICKED = 1
const val FLAG_REJECTED = -1

enum class GeoSource { NONE, CAMERA, TRACK }

/**
 * Where a file lives. [LIBRARY] files are in the library's own folder, made or taken in by the app,
 * which may change them. [PHONE] files are photos and videos that other apps made, listed where they
 * are: the app never changes them, and keeps what is done to them as settings, as for a raw file.
 */
enum class MediaOrigin { LIBRARY, PHONE }

enum class LocationSource { APP, TIMELINE }

@Entity(
    tableName = "media_items",
    indices = [Index("capturedAt"), Index(value = ["path"], unique = true)],
)
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val fileName: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val capturedAt: Long,
    val importedAt: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val geoSource: GeoSource = GeoSource.NONE,
    /** Key of the [SyncRecordEntity] this file was imported under; null for files made on the phone. */
    val sourceKey: String? = null,
    /** The corrections chosen for this file, as written by PhotoEdit.toJson; null while it is as the camera made it. */
    val edit: String? = null,
    /** Stars from 0 to 5, for picking out the photos worth keeping. */
    @ColumnInfo(defaultValue = "0") val rating: Int = 0,
    /** When the file was put in the trash; null while it is in the library. */
    val trashedAt: Long? = null,
    /** Where the file was in the library before it went into the trash, to put it back there. */
    val trashedFrom: String? = null,
    @ColumnInfo(defaultValue = "'LIBRARY'") val origin: MediaOrigin = MediaOrigin.LIBRARY,
    /** The flag of a cull: [FLAG_PICKED], [FLAG_REJECTED], or 0 for neither. */
    @ColumnInfo(defaultValue = "0") val flag: Int = 0,
    /** How the photo was taken, read from its Exif once ([exifRead]) so that the library can be searched by it; null where the file says nothing. */
    val camera: String? = null,
    val lens: String? = null,
    val iso: Int? = null,
    /** In 35 mm terms where the file says, the lens's own otherwise. */
    val focalLength: Double? = null,
    @ColumnInfo(defaultValue = "0") val exifRead: Boolean = false,
) {
    /**
     * Whether what is done to this file is kept as settings, and applied whenever it is shown or
     * exported, instead of being written into the file: raw files, and files that are not the app's.
     */
    val editsAsSettings: Boolean get() = kind == MediaKind.RAW || origin == MediaOrigin.PHONE || isHeif

    /** Whether the file is a HEIF picture (HEIC, HIF), which the app reads and draws but does not write. */
    val isHeif: Boolean get() = isHeifName(fileName)

    /**
     * A photo that stays as it is when it is edited: another app's, or a HEIF one. Its edit is kept as
     * settings, and saving it makes a JPEG copy.
     */
    val photoKeptAsIs: Boolean get() = kind == MediaKind.PHOTO && (origin == MediaOrigin.PHONE || isHeif)
}

/** A named group of photos and videos the person put together, such as a trip. */
@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** A photo or video in an album. It leaves the album when either is deleted. */
@Entity(
    tableName = "album_items",
    primaryKeys = ["albumId", "mediaId"],
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("mediaId")],
)
data class AlbumItemEntity(
    val albumId: Long,
    val mediaId: Long,
    val addedAt: Long,
)

/** A look of one photo kept under a name, to come back to: a version of its edit. It goes when the photo does. */
@Entity(
    tableName = "edit_versions",
    foreignKeys = [ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mediaId")],
)
data class EditVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val name: String,
    /** The edit, as written by PhotoEdit.toJson. */
    val edit: String,
    val createdAt: Long,
)

/** Remembers every camera file ever imported, so removing it from the library does not re-import it. */
@Entity(tableName = "sync_records")
data class SyncRecordEntity(
    @PrimaryKey val sourceKey: String,
    val importedAt: Long,
)

/**
 * A camera folder whose files have all been imported. While its fingerprint still matches, a sync
 * skips the folder instead of asking the camera about every file in it again.
 */
@Entity(tableName = "synced_folders", primaryKeys = ["cameraId", "path"])
data class SyncedFolderEntity(
    val cameraId: String,
    val path: String,
    val fingerprint: String,
    /** Comma-separated [MediaKind] names that were being imported when the folder was completed. */
    val kinds: String,
)

@Entity(
    tableName = "location_points",
    indices = [Index(value = ["timestamp", "source"], unique = true)],
)
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val source: LocationSource,
)

/**
 * A colour lookup table (a .cube file) the person imported, to give photos its look. Its table is kept
 * in a file of its own, under [hash], which is also what edits refer to it by.
 */
@Entity(tableName = "luts", indices = [Index(value = ["hash"], unique = true)])
data class LutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Points along each side of the cube. */
    val size: Int,
    /** The id of the table in LutStore: a digest of the imported file. */
    val hash: String,
    val createdAt: Long,
)

/** A look the user saved under a name, to give to other photos: a filter of their own. */
@Entity(tableName = "filters")
data class FilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** The look, as written by PhotoEdit.toJson. */
    val look: String,
    val createdAt: Long,
)
