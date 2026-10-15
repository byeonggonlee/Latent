package com.byeonggon.latent.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.byeonggon.latent.data.db.AlbumEntity
import com.byeonggon.latent.data.db.AlbumItemEntity
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.EditVersionEntity
import com.byeonggon.latent.data.db.FLAG_REJECTED
import com.byeonggon.latent.data.db.FilterEntity
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.data.db.SyncedFolderEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun upgradesTheFirstReleasedSchemaWithoutLosingTheLibrary() = runBlocking {
        val file = context.getDatabasePath(NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            VERSION_1_SCHEMA.forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO media_items (path, fileName, kind, sizeBytes, capturedAt, importedAt, geoSource) " +
                    "VALUES ('/sdcard/DCIM/Latent/DSC00001.JPG', 'DSC00001.JPG', 'PHOTO', 10, 1, 2, 'NONE')",
            )
            db.execSQL("INSERT INTO sync_records (sourceKey, importedAt) VALUES ('camera|DSC00001.JPG|10|1', 2)")
            db.execSQL(
                "INSERT INTO location_points (timestamp, latitude, longitude, source) VALUES (5, 37.5, 127.0, 'APP')",
            )
            db.version = 1
        }

        // Room checks the migrated tables against the entities when the database is first used.
        val database = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val item = database.mediaDao().all().single()
            assertEquals("DSC00001.JPG", item.fileName)
            assertEquals(null, item.sourceKey)
            assertEquals(null, item.edit)
            assertEquals(0, item.rating)
            assertNull(item.trashedAt)
            assertEquals(MediaOrigin.LIBRARY, item.origin)
            // Flags came after: nothing is flagged, and a photo can be.
            assertEquals(0, item.flag)
            database.mediaDao().setFlag(listOf(item.id), FLAG_REJECTED)
            assertEquals(listOf(item.id), database.mediaDao().rejectedIds())
            database.mediaDao().setFlag(listOf(item.id), 0)
            database.mediaDao().setRating(item.id, 4)
            assertEquals(4, database.mediaDao().byId(item.id)!!.rating)
            database.mediaDao().setEdit(item.id, "{}")
            assertEquals("{}", database.mediaDao().byId(item.id)!!.edit)
            val filterId = database.filterDao().insert(FilterEntity(name = "내 필터", look = "{}", createdAt = 7))
            assertEquals(listOf("내 필터"), database.filterDao().observeAll().first().map { it.name })
            database.filterDao().delete(filterId)
            assertEquals(emptyList<FilterEntity>(), database.filterDao().observeAll().first())
            assertEquals(listOf("camera|DSC00001.JPG|10|1"), database.syncRecordDao().allKeys())
            assertEquals(37.5, database.locationDao().lastAtOrBefore(5)!!.latitude, 0.0)

            // Albums came in the last version: the photo can be put in one and leaves it with the album.
            val albumId = database.albumDao().insert(AlbumEntity(name = "Trip", createdAt = 1))
            database.albumDao().addItems(listOf(AlbumItemEntity(albumId, item.id, 2)))
            assertEquals(listOf(item.id), database.albumDao().observeMembers(albumId).first())
            database.albumDao().delete(albumId)
            assertEquals(emptyList<Long>(), database.albumDao().observeMembers(albumId).first())

            // Versions of an edit came after that: kept per photo, and gone with it.
            database.editVersionDao().insert(EditVersionEntity(mediaId = item.id, name = "Warm", edit = "{}", createdAt = 3))
            assertEquals(listOf("Warm"), database.editVersionDao().observeFor(item.id).first().map { it.name })

            val folder = SyncedFolderEntity("camera", "10001:/2024-12-08/", "fingerprint", "PHOTO,RAW")
            database.syncedFolderDao().upsert(folder)
            assertEquals(listOf(folder), database.syncedFolderDao().forCamera("camera"))
        } finally {
            database.close()
        }
    }

    private companion object {
        const val NAME = "migration-test.db"

        /** The tables exactly as version 1 of the app created them. */
        val VERSION_1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `media_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`path` TEXT NOT NULL, `fileName` TEXT NOT NULL, `kind` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                "`capturedAt` INTEGER NOT NULL, `importedAt` INTEGER NOT NULL, `latitude` REAL, `longitude` REAL, " +
                "`altitude` REAL, `geoSource` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_media_items_capturedAt` ON `media_items` (`capturedAt`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_items_path` ON `media_items` (`path`)",
            "CREATE TABLE IF NOT EXISTS `sync_records` (`sourceKey` TEXT NOT NULL, `importedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`sourceKey`))",
            "CREATE TABLE IF NOT EXISTS `location_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `altitude` REAL, " +
                "`accuracy` REAL, `source` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_location_points_timestamp_source` ON `location_points` " +
                "(`timestamp`, `source`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'f211ab84e387958742508ed4d41adf8e')",
        )
    }
}
