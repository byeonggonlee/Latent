package com.byeonggon.latent.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

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

    @Query("DELETE FROM media_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
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
}

@Dao
interface LocationDao {
    /** Returns one row id per point; -1 marks a point that already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<LocationPointEntity>): List<Long>

    @Query("SELECT * FROM location_points WHERE timestamp <= :timestamp ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastAtOrBefore(timestamp: Long): LocationPointEntity?

    @Query("SELECT * FROM location_points WHERE timestamp >= :timestamp ORDER BY timestamp ASC LIMIT 1")
    suspend fun firstAtOrAfter(timestamp: Long): LocationPointEntity?

    @Query("SELECT COUNT(*) FROM location_points")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM location_points WHERE source = 'APP' ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestRecorded(): Flow<LocationPointEntity?>
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
    ],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun syncRecordDao(): SyncRecordDao
    abstract fun syncedFolderDao(): SyncedFolderDao
    abstract fun locationDao(): LocationDao
    abstract fun filterDao(): FilterDao
    abstract fun albumDao(): AlbumDao
    abstract fun editVersionDao(): EditVersionDao

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

        /** Every schema change since version 1. The library on the phone must survive app updates. */
        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
    }
}
