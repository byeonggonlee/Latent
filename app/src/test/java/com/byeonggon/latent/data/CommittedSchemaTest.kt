package com.byeonggon.latent.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A database made from the newest committed schema (`app/schemas/.../13.json`), as a phone has it today, opens as it
 * is. The next migration is tested here the same way: make the database at 13, put in what must come through, then
 * `helper.runMigrationsAndValidate(NAME, 14, true, MIGRATION_13_14)`, which checks the result against `14.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommittedSchemaTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun opensADatabaseMadeFromTheNewestCommittedSchema() = runBlocking {
        helper.createDatabase(NAME, 13).use { db ->
            db.execSQL(
                "INSERT INTO media_items (path, fileName, kind, sizeBytes, capturedAt, importedAt, geoSource, rating, origin, flag, exifRead) " +
                    "VALUES ('/sdcard/DCIM/Latent/2026/2026-10-08/DSC00001.JPG', 'DSC00001.JPG', 'PHOTO', 10, 1, 2, 'NONE', 4, 'LIBRARY', 0, 0)",
            )
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val item = database.mediaDao().all().single()
            assertEquals("DSC00001.JPG", item.fileName)
            assertEquals(MediaKind.PHOTO, item.kind)
            assertEquals(4, item.rating)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val NAME = "committed-schema.db"
    }
}
