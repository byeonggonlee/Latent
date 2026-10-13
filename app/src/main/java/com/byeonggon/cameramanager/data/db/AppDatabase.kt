package com.byeonggon.cameramanager.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items ORDER BY capturedAt DESC, id DESC")
    fun observeAll(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE id = :id")
    fun observeById(id: Long): Flow<MediaItemEntity?>

    @Query("SELECT * FROM media_items")
    suspend fun all(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE geoSource = 'NONE'")
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

    @Query("DELETE FROM media_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Dao
interface SyncRecordDao {
    @Query("SELECT sourceKey FROM sync_records")
    suspend fun allKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SyncRecordEntity)
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
    entities = [MediaItemEntity::class, SyncRecordEntity::class, LocationPointEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun syncRecordDao(): SyncRecordDao
    abstract fun locationDao(): LocationDao
}
