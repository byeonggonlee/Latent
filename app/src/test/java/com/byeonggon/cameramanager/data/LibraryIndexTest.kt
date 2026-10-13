package com.byeonggon.cameramanager.data

import android.content.Context
import android.os.Environment
import androidx.room.Room
import com.byeonggon.cameramanager.data.db.AppDatabase
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryIndexTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: AppDatabase
    private lateinit var storage: LibraryStorage
    private lateinit var index: LibraryIndex

    @Before
    fun setUp() {
        ShadowEnvironment.addExternalDir("shared")
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        storage = LibraryStorage(context)
        index = LibraryIndex(storage, database)
    }

    @After
    fun tearDown() {
        database.close()
        storage.root.deleteRecursively()
    }

    private suspend fun addToLibrary(name: String, onDisk: Boolean, edited: Boolean): File {
        val file = File(storage.root, "2026/2026-10-08/$name")
        file.parentFile!!.mkdirs()
        if (onDisk) file.writeText("edited picture")
        if (edited) storage.originalOf(file).apply { parentFile!!.mkdirs() }.writeText("original picture")
        database.mediaDao().insert(
            MediaItemEntity(path = file.path, fileName = name, kind = MediaKind.PHOTO, sizeBytes = 14, capturedAt = 1, importedAt = 2),
        )
        return file
    }

    @Test
    fun keepsOriginalsOutOfSightUnderTheSamePath() {
        val original = storage.originalOf(File(storage.root, "2026/2026-10-08/DSC00001.JPG"))

        assertEquals(File(storage.root, ".originals/2026/2026-10-08/DSC00001.JPG"), original)
    }

    @Test
    fun forgetsPhotosDeletedElsewhereTogetherWithTheirHiddenOriginals() = runBlocking {
        val deleted = addToLibrary("DSC00001.JPG", onDisk = false, edited = true)
        val kept = addToLibrary("DSC00002.JPG", onDisk = true, edited = true)

        index.forgetMissingFiles()

        assertEquals(listOf("DSC00002.JPG"), database.mediaDao().all().map { it.fileName })
        assertFalse(storage.originalOf(deleted).exists())
        assertTrue(storage.originalOf(kept).exists())
    }

    @Test
    fun touchesNothingWhileSharedStorageIsUnavailable() = runBlocking {
        val unreachable = addToLibrary("DSC00003.JPG", onDisk = false, edited = true)
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_UNMOUNTED)

        index.forgetMissingFiles()

        assertEquals(1, database.mediaDao().all().size)
        assertTrue(storage.originalOf(unreachable).exists())
    }
}
