package com.byeonggon.cameramanager.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
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
                "arw", "dng" -> RAW
                "mp4", "mov", "mts", "m2ts" -> VIDEO
                else -> null
            }
    }
}

enum class GeoSource { NONE, CAMERA, TRACK }

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

/** A look the user saved under a name, to give to other photos: a filter of their own. */
@Entity(tableName = "filters")
data class FilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** The look, as written by PhotoEdit.toJson. */
    val look: String,
    val createdAt: Long,
)
