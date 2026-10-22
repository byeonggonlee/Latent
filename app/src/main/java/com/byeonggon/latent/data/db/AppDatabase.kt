package com.byeonggon.latent.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** A name found in the library, such as a camera, and how many photos have it. */
data class NameCount(val name: String, val count: Int)

@Dao
interface MediaDao {
    /** Everything in the library, newest first; what is in the trash is not. */
    @Query("SELECT * FROM media_items WHERE trashedAt IS NULL ORDER BY capturedAt DESC, id DESC")
    fun observeAll(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC, id DESC")
    fun observeTrash(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE trashedAt IS NOT NULL AND trashedAt < :before")
    suspend fun trashedBefore(before: Long): List<MediaItemEntity>

    @Query("UPDATE media_items SET path = :path, trashedAt = :trashedAt, trashedFrom = :trashedFrom WHERE id = :id")
    suspend fun setPlace(id: Long, path: String, trashedAt: Long?, trashedFrom: String?)

    @Query("UPDATE media_items SET capturedAt = :capturedAt WHERE id = :id")
    suspend fun setCapturedAt(id: Long, capturedAt: Long)

    /** Items whose Exif has not been read yet, from [after] on in the order of their ids. */
    @Query("SELECT * FROM media_items WHERE exifRead = 0 AND id > :after ORDER BY id LIMIT :limit")
    suspend fun withoutExif(after: Long, limit: Int): List<MediaItemEntity>

    @Query("UPDATE media_items SET camera = :camera, lens = :lens, iso = :iso, focalLength = :focalLength, exifRead = 1 WHERE id = :id")
    suspend fun setExif(id: Long, camera: String?, lens: String?, iso: Int?, focalLength: Double?)

    @Query("SELECT camera AS name, COUNT(*) AS count FROM media_items WHERE camera IS NOT NULL AND trashedAt IS NULL GROUP BY camera ORDER BY count DESC, camera")
    fun observeCameras(): Flow<List<NameCount>>

    @Query("SELECT lens AS name, COUNT(*) AS count FROM media_items WHERE lens IS NOT NULL AND trashedAt IS NULL GROUP BY lens ORDER BY count DESC, lens")
    fun observeLenses(): Flow<List<NameCount>>

    @Query("SELECT * FROM media_items WHERE id = :id")
    fun observeById(id: Long): Flow<MediaItemEntity?>

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun byId(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE id IN (:ids) ORDER BY capturedAt DESC, id DESC")
    suspend fun byIds(ids: List<Long>): List<MediaItemEntity>

    @Query("SELECT * FROM media_items")
    suspend fun all(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE origin = 'PHONE'")
    suspend fun fromPhone(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE geoSource = 'NONE' AND trashedAt IS NULL")
    suspend fun withoutLocation(): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE geoSource = 'NONE'")
    fun observeWithoutLocationCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MediaItemEntity): Long

    @Query(
        "UPDATE media_items SET latitude = :latitude, longitude = :longitude, altitude = :altitude, " +
            "geoSource = :source WHERE id = :id",
    )
    suspend fun setLocation(id: Long, latitude: Double, longitude: Double, altitude: Double?, source: GeoSource)

    @Query("UPDATE media_items SET sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun setSize(id: Long, sizeBytes: Long)

    @Query("UPDATE media_items SET edit = :edit WHERE id = :id")
    suspend fun setEdit(id: Long, edit: String?)

    @Query("UPDATE media_items SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: Long, rating: Int)

    @Query("UPDATE media_items SET flag = :flag WHERE id IN (:ids)")
    suspend fun setFlag(ids: List<Long>, flag: Int)

    @Query("SELECT id FROM media_items WHERE flag = $FLAG_REJECTED AND trashedAt IS NULL")
    suspend fun rejectedIds(): List<Long>

    /** Adds [item] unless the library has its file already. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnlessKnown(item: MediaItemEntity): Long

    @Query("UPDATE media_items SET sourceKey = :sourceKey WHERE id = :id")
    suspend fun setSourceKey(id: Long, sourceKey: String)

    @Query("SELECT * FROM media_items WHERE sourceKey IN (:sourceKeys)")
    suspend fun bySourceKeys(sourceKeys: List<String>): List<MediaItemEntity>

    @Query("DELETE FROM media_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** How many edits, the trash's included, have text that matches the SQL [pattern]. */
    @Query("SELECT COUNT(*) FROM media_items WHERE edit LIKE :pattern")
    suspend fun countEditsLike(pattern: String): Int
}

/** An album with how many photos and videos it holds. */
data class AlbumSummary(
    @androidx.room.Embedded val album: AlbumEntity,
    val count: Int,
)

@Dao
interface AlbumDao {
    @Query(
        "SELECT albums.*, COUNT(album_items.mediaId) AS count FROM albums " +
            "LEFT JOIN album_items ON album_items.albumId = albums.id GROUP BY albums.id ORDER BY albums.name COLLATE NOCASE, albums.id",
    )
    fun observeAll(): Flow<List<AlbumSummary>>

    @Query("SELECT mediaId FROM album_items WHERE albumId = :albumId")
    fun observeMembers(albumId: Long): Flow<List<Long>>

    @Insert
    suspend fun insert(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItems(items: List<AlbumItemEntity>)

    @Query("DELETE FROM album_items WHERE albumId = :albumId AND mediaId IN (:mediaIds)")
    suspend fun removeItems(albumId: Long, mediaIds: List<Long>)

    @Query("UPDATE albums SET name = :name WHERE id = :albumId")
    suspend fun rename(albumId: Long, name: String)

    @Query("DELETE FROM albums WHERE id = :albumId")
    suspend fun delete(albumId: Long)
}

@Dao
interface EditVersionDao {
    @Query("SELECT * FROM edit_versions WHERE mediaId = :mediaId ORDER BY createdAt, id")
    fun observeFor(mediaId: Long): Flow<List<EditVersionEntity>>

    @Insert
    suspend fun insert(version: EditVersionEntity): Long

    @Query("DELETE FROM edit_versions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM edit_versions WHERE edit LIKE :pattern")
    suspend fun countLike(pattern: String): Int
}

@Dao
interface SyncRecordDao {
    @Query("SELECT sourceKey FROM sync_records")
    suspend fun allKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SyncRecordEntity)

    @Query("SELECT COUNT(*) FROM sync_records WHERE $NOT_IN_LIBRARY")
    fun observeCountWithoutLibraryFile(): Flow<Int>

    @Query("DELETE FROM sync_records WHERE $NOT_IN_LIBRARY")
    suspend fun deleteWithoutLibraryFile(): Int

    private companion object {
        const val NOT_IN_LIBRARY =
            "sourceKey NOT IN (SELECT sourceKey FROM media_items WHERE sourceKey IS NOT NULL)"
    }
}

@Dao
interface SyncedFolderDao {
    @Query("SELECT * FROM synced_folders WHERE cameraId = :cameraId")
    suspend fun forCamera(cameraId: String): List<SyncedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: SyncedFolderEntity)

    @Query("DELETE FROM synced_folders WHERE cameraId = :cameraId AND path = :path")
    suspend fun delete(cameraId: String, path: String)

    @Query("DELETE FROM synced_folders")
    suspend fun deleteAll()
}

@Dao
interface FilterDao {
    @Query("SELECT * FROM filters ORDER BY createdAt, id")
    fun observeAll(): Flow<List<FilterEntity>>

    @Insert
    suspend fun insert(filter: FilterEntity): Long

    @Query("DELETE FROM filters WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM filters WHERE look LIKE :pattern")
    suspend fun countLike(pattern: String): Int
}

@Dao
interface LutDao {
    @Query("SELECT * FROM luts ORDER BY createdAt, id")
    fun observeAll(): Flow<List<LutEntity>>

    /** Adds [lut] unless one with its hash is there already. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(lut: LutEntity): Long

    @Query("SELECT * FROM luts WHERE hash = :hash")
    suspend fun byHash(hash: String): LutEntity?

    @Query("DELETE FROM luts WHERE hash = :hash")
    suspend fun delete(hash: String)
}

@Dao
interface LocationDao {
    /** Returns one row id per point; -1 marks a point that already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<LocationPointEntity>): List<Long>

    /** Keeps the points that are here already; see [addNew]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(points: List<LocationPointEntity>)

    @Query("SELECT COUNT(*) FROM location_points")
    suspend fun count(): Int

    /**
     * Adds the points that are not here yet and returns how many that was. Asking [insertAll] for the row ids costs two
     * queries a point (Room reads each one back with `changes()` and `last_insert_rowid()`), which made thousands of
     * cursors for years of places; counting the table before and after costs two queries in all.
     */
    @Transaction
    suspend fun addNew(points: List<LocationPointEntity>): Int {
        val before = count()
        insertNew(points)
        return count() - before
    }

    @Query("SELECT * FROM location_points WHERE timestamp <= :timestamp ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastAtOrBefore(timestamp: Long): LocationPointEntity?

    @Query("SELECT * FROM location_points WHERE timestamp >= :timestamp ORDER BY timestamp ASC LIMIT 1")
    suspend fun firstAtOrAfter(timestamp: Long): LocationPointEntity?

    @Query("SELECT COUNT(*) FROM location_points")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM location_points WHERE source = 'APP' ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestRecorded(): Flow<LocationPointEntity?>
}

/**
 * What a backup reads page by page, so that a library of any size goes through in little memory, and
 * what a restore writes without disturbing what the phone has.
 */
@Dao
interface BackupDao {
    @Query("SELECT COUNT(*) FROM media_items WHERE trashedAt IS NULL")
    suspend fun countMedia(): Int

    @Query("SELECT * FROM media_items WHERE trashedAt IS NULL AND id > :after ORDER BY id LIMIT :limit")
    suspend fun mediaAfter(after: Long, limit: Int): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE trashedAt IS NULL")
    suspend fun mediaInLibrary(): List<MediaItemEntity>

    /** Changes the row in place; inserting it again would take its albums and versions away. */
    @Update
    suspend fun updateMedia(item: MediaItemEntity)

    @Query("SELECT DISTINCT mediaId FROM album_items")
    suspend fun mediaInAlbums(): List<Long>

    @Query("SELECT DISTINCT mediaId FROM edit_versions")
    suspend fun mediaWithVersions(): List<Long>

    @Query("SELECT * FROM albums ORDER BY id")
    suspend fun albums(): List<AlbumEntity>

    @Query(
        "SELECT * FROM album_items WHERE albumId > :afterAlbum OR (albumId = :afterAlbum AND mediaId > :afterMedia) " +
            "ORDER BY albumId, mediaId LIMIT :limit",
    )
    suspend fun albumItemsAfter(afterAlbum: Long, afterMedia: Long, limit: Int): List<AlbumItemEntity>

    @Query("SELECT * FROM edit_versions WHERE id > :after ORDER BY id LIMIT :limit")
    suspend fun versionsAfter(after: Long, limit: Int): List<EditVersionEntity>

    @Query("SELECT * FROM edit_versions WHERE mediaId = :mediaId")
    suspend fun versionsOf(mediaId: Long): List<EditVersionEntity>

    @Query("SELECT * FROM filters ORDER BY id")
    suspend fun filters(): List<FilterEntity>

    @Query("SELECT * FROM luts ORDER BY id")
    suspend fun luts(): List<LutEntity>

    @Query("SELECT COUNT(*) FROM sync_records")
    suspend fun countSyncRecords(): Int

    @Query("SELECT * FROM sync_records WHERE sourceKey > :after ORDER BY sourceKey LIMIT :limit")
    suspend fun syncRecordsAfter(after: String, limit: Int): List<SyncRecordEntity>

    /** Keeps the records the phone has already. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncRecords(records: List<SyncRecordEntity>)

    /** Adds the records the phone does not have yet and returns how many that was, as [LocationDao.addNew] does. */
    @Transaction
    suspend fun addNewSyncRecords(records: List<SyncRecordEntity>): Int {
        val before = countSyncRecords()
        insertSyncRecords(records)
        return countSyncRecords() - before
    }

    @Query("SELECT * FROM synced_folders ORDER BY cameraId, path")
    suspend fun syncedFolders(): List<SyncedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncedFolders(folders: List<SyncedFolderEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM location_points")
    suspend fun countLocations(): Int

    @Query("SELECT * FROM location_points WHERE id > :after ORDER BY id LIMIT :limit")
    suspend fun locationsAfter(after: Long, limit: Int): List<LocationPointEntity>
}

@Database(
    entities = [
        MediaItemEntity::class,
        SyncRecordEntity::class,
        SyncedFolderEntity::class,
        LocationPointEntity::class,
        FilterEntity::class,
        AlbumEntity::class,
        AlbumItemEntity::class,
        EditVersionEntity::class,
        LutEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun syncRecordDao(): SyncRecordDao
    abstract fun syncedFolderDao(): SyncedFolderDao
    abstract fun locationDao(): LocationDao
    abstract fun filterDao(): FilterDao
    abstract fun albumDao(): AlbumDao
    abstract fun editVersionDao(): EditVersionDao
    abstract fun lutDao(): LutDao
    abstract fun backupDao(): BackupDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `synced_folders` (`cameraId` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, `kinds` TEXT NOT NULL, PRIMARY KEY(`cameraId`, `path`))",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `sourceKey` TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `edit` TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `filters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `look` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `rating` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `trashedAt` INTEGER")
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `trashedFrom` TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'LIBRARY'")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `albums` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_items` (`albumId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`albumId`, `mediaId`), " +
                        "FOREIGN KEY(`albumId`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`mediaId`) REFERENCES `media_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_items_mediaId` ON `album_items` (`mediaId`)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `edit_versions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`mediaId` INTEGER NOT NULL, `name` TEXT NOT NULL, `edit` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`mediaId`) REFERENCES `media_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_edit_versions_mediaId` ON `edit_versions` (`mediaId`)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `flag` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `camera` TEXT")
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `lens` TEXT")
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `iso` INTEGER")
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `focalLength` REAL")
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `exifRead` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `luts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, `hash` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_luts_hash` ON `luts` (`hash`)")
            }
        }

        /** Every schema change since version 1. The library on the phone must survive app updates. */
        val MIGRATIONS = arrayOf<Migration>(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
            MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
        )
    }
}
