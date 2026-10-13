package com.byeonggon.cameramanager.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import com.byeonggon.cameramanager.data.AppSettings
import com.byeonggon.cameramanager.data.LibraryStorage
import com.byeonggon.cameramanager.data.SettingsRepository
import com.byeonggon.cameramanager.data.db.AppDatabase
import com.byeonggon.cameramanager.data.db.GeoSource
import com.byeonggon.cameramanager.data.db.LocationPointEntity
import com.byeonggon.cameramanager.data.db.LocationSource
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.location.Geotagger
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncEngineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val capturedAt = OffsetDateTime.parse("2026-10-01T12:00:00+09:00").toInstant().toEpochMilli()
    private val minute = 60_000L

    private val settings = SettingsRepository(context)
    private var nextHandle = 1_000

    private lateinit var database: AppDatabase
    private lateinit var storage: LibraryStorage
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        storage = LibraryStorage(context)
        val geotagger = Geotagger(database.locationDao(), database.mediaDao(), storage)
        engine = SyncEngine(storage, database, settings, geotagger)
    }

    @After
    fun tearDown() {
        database.close()
        storage.root.deleteRecursively()
        // Settings live in one store for the whole test run.
        runBlocking { settings.update { AppSettings() } }
    }

    @Test
    fun importsNewFilesGeotagsThemAndSkipsThemNextTime() = runBlocking {
        recordTrackAroundCapture()
        val photo = jpegTakenAt("2026:10:01 12:00:00", "+09:00")
        val files = mapOf(
            remote("DSC00001.JPG", MediaKind.PHOTO, photo) to photo,
            remote("DSC00001.ARW", MediaKind.RAW, RAW_BYTES) to RAW_BYTES,
            remote("C0001.MP4", MediaKind.VIDEO, VIDEO_BYTES) to VIDEO_BYTES,
        )

        val firstSource = FakeCameraSource(files)
        engine.run(firstSource)

        assertEquals(SyncState.Finished(imported = 3, geotagged = 3, failed = emptyList(), cancelled = false), engine.state.value)
        assertTrue(firstSource.closed)

        val items = database.mediaDao().all().associateBy { it.fileName }
        assertEquals(setOf("DSC00001.JPG", "DSC00001.ARW", "C0001.MP4"), items.keys)
        items.values.forEach { item ->
            assertTrue("${item.path} is inside the library", File(item.path).startsWith(storage.root))
            assertTrue(File(item.path).isFile)
            assertEquals(capturedAt, item.capturedAt)
            assertEquals(GeoSource.TRACK, item.geoSource)
            assertEquals(37.1, item.latitude!!, 1e-6)
            assertEquals(127.1, item.longitude!!, 1e-6)
        }

        // The JPEG carries the position itself, the RAW file gets a sidecar, and nothing is left in staging.
        val savedPhoto = File(items.getValue("DSC00001.JPG").path)
        val (latitude, longitude) = ExifInterface(savedPhoto).latLong!!
        assertEquals(37.1, latitude, 1e-4)
        assertEquals(127.1, longitude, 1e-4)
        val sidecar = File(savedPhoto.parentFile, "DSC00001.xmp")
        assertTrue("exif:GPSLatitude=\"37,6.000000N\"" in sidecar.readText())
        assertEquals(emptyList<String>(), storage.root.walk().filter { it.name.endsWith(".part") }.map { it.name }.toList())

        engine.run(FakeCameraSource(files))

        assertEquals(SyncState.Finished(imported = 0, geotagged = 0, failed = emptyList(), cancelled = false), engine.state.value)
        assertEquals(3, database.mediaDao().all().size)
    }

    @Test
    fun leavesFilesUntaggedWhenNoTrackCoversTheCaptureTime() = runBlocking {
        val photo = jpegTakenAt("2026:10:01 12:00:00", "+09:00")

        engine.run(FakeCameraSource(mapOf(remote("DSC00002.JPG", MediaKind.PHOTO, photo) to photo)))

        val item = database.mediaDao().all().single()
        assertEquals(GeoSource.NONE, item.geoSource)
        assertEquals(null, ExifInterface(File(item.path)).latLong)
    }

    @Test
    fun importsOnlyFilesShotWithinTheSelectedDates() = runBlocking {
        val day = 24 * 60 * minute
        val files = mapOf(
            remote("DSC00010.ARW", MediaKind.RAW, RAW_BYTES, modifiedAt = capturedAt - 3 * day) to RAW_BYTES,
            remote("DSC00011.ARW", MediaKind.RAW, RAW_BYTES) to RAW_BYTES,
        )
        val captureDate = Instant.ofEpochMilli(capturedAt).atZone(ZoneId.systemDefault()).toLocalDate()

        engine.run(FakeCameraSource(files), SyncDates(captureDate, captureDate))

        assertEquals(listOf("DSC00011.ARW"), database.mediaDao().all().map { it.fileName })

        // Files outside the range are not remembered as synced, so a later sync without dates picks them up.
        engine.run(FakeCameraSource(files))

        assertEquals(setOf("DSC00010.ARW", "DSC00011.ARW"), database.mediaDao().all().map { it.fileName }.toSet())
    }

    @Test
    fun opensOnlyTheDateFoldersWithinTheSelectedDates() = runBlocking {
        val day = 24 * 60 * minute
        val captureDate = Instant.ofEpochMilli(capturedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val earlierDate = captureDate.minusDays(3)
        val files = mapOf(
            remote("DSC00030.ARW", MediaKind.RAW, RAW_BYTES, capturedAt - 3 * day, folder = "$earlierDate") to RAW_BYTES,
            remote("DSC00031.ARW", MediaKind.RAW, RAW_BYTES, folder = "$captureDate") to RAW_BYTES,
        )

        val source = FakeCameraSource(files)
        engine.run(source, SyncDates(captureDate, captureDate))

        assertEquals(listOf("$captureDate"), source.tree.listedFolders)
        assertEquals(listOf("DSC00031.ARW"), database.mediaDao().all().map { it.fileName })
    }

    @Test
    fun skipsFoldersThatWereFullyImportedUntilTheyChange() = runBlocking {
        val files = (20..22).associate { remote("DSC000$it.ARW", MediaKind.RAW, RAW_BYTES) to RAW_BYTES }
        engine.run(FakeCameraSource(files))

        val unchanged = FakeCameraSource(files)
        engine.run(unchanged)

        assertEquals(0, (engine.state.value as SyncState.Finished).imported)
        // The folder itself, then only its first and last file to confirm nothing was added.
        assertEquals(3, unchanged.tree.lookups)

        val grown = FakeCameraSource(files + (remote("DSC00023.ARW", MediaKind.RAW, RAW_BYTES) to RAW_BYTES))
        engine.run(grown)

        assertEquals(1, (engine.state.value as SyncState.Finished).imported)
        assertEquals(4, database.mediaDao().all().size)
    }

    @Test
    fun keepsReadingAFolderWhileSomeOfItsFilesAreNotImported() = runBlocking {
        val good = remote("DSC00050.ARW", MediaKind.RAW, RAW_BYTES)
        val broken = remote("DSC00051.ARW", MediaKind.RAW, RAW_BYTES)
        engine.run(FakeCameraSource(mapOf(good to RAW_BYTES, broken to null)))

        engine.run(FakeCameraSource(mapOf(good to RAW_BYTES, broken to RAW_BYTES)))

        assertEquals(setOf("DSC00050.ARW", "DSC00051.ARW"), database.mediaDao().all().map { it.fileName }.toSet())
    }

    @Test
    fun readsAnImportedFolderAgainOnceMoreKindsAreEnabled() = runBlocking {
        val files = mapOf(
            remote("DSC00040.ARW", MediaKind.RAW, RAW_BYTES) to RAW_BYTES,
            remote("C0040.MP4", MediaKind.VIDEO, VIDEO_BYTES) to VIDEO_BYTES,
        )
        settings.update { it.copy(syncVideos = false) }
        engine.run(FakeCameraSource(files))
        assertEquals(listOf("DSC00040.ARW"), database.mediaDao().all().map { it.fileName })

        settings.update { it.copy(syncVideos = true) }
        engine.run(FakeCameraSource(files))

        assertEquals(setOf("DSC00040.ARW", "C0040.MP4"), database.mediaDao().all().map { it.fileName }.toSet())
    }

    @Test
    fun reportsFilesThatFailToTransferAndRetriesThemLater() = runBlocking {
        val broken = remote("DSC00003.JPG", MediaKind.PHOTO, ByteArray(10))

        engine.run(FakeCameraSource(mapOf(broken to null)))

        val finished = engine.state.value as SyncState.Finished
        assertEquals(0, finished.imported)
        assertEquals(listOf("DSC00003.JPG"), finished.failed)
        assertEquals(emptyList<String>(), database.syncRecordDao().allKeys())
        assertFalse(storage.root.walk().any { it.isFile && it.name != ".nomedia" })
    }

    private suspend fun recordTrackAroundCapture() {
        database.locationDao().insertAll(
            listOf(
                LocationPointEntity(
                    timestamp = capturedAt - 5 * minute,
                    latitude = 37.0,
                    longitude = 127.0,
                    source = LocationSource.APP,
                ),
                LocationPointEntity(
                    timestamp = capturedAt + 5 * minute,
                    latitude = 37.2,
                    longitude = 127.2,
                    source = LocationSource.APP,
                ),
            ),
        )
    }

    private fun remote(
        name: String,
        kind: MediaKind,
        bytes: ByteArray,
        modifiedAt: Long = capturedAt,
        folder: String = "100MSDCF",
    ) = RemoteFile(nextHandle++, name, bytes.size.toLong(), modifiedAt, kind, folder = "$STORAGE:/$folder/")

    private fun jpegTakenAt(dateTime: String, offset: String): ByteArray {
        val file = File.createTempFile("source", ".jpg", context.cacheDir)
        file.outputStream().use {
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }

    /** A camera whose single storage holds [files] in their folders; a null payload simulates a failing transfer. */
    private class FakeCameraSource(private val files: Map<RemoteFile, ByteArray?>) : CameraSource {
        val tree = FakeTree()
        var closed = false

        init {
            val folders = HashMap<String, Int>()
            for (file in files.keys) {
                val name = file.folder.removePrefix("$STORAGE:/").removeSuffix("/")
                val folder = folders.getOrPut(name) { tree.folder(FakeTree.ROOT, name) }
                tree.file(folder, file.name, file.sizeBytes, file.modifiedAt, file.handle)
            }
        }

        override fun open() = CameraInfo("Sony", "ZV-E10", "SERIAL")

        override fun listFiles(request: ListingRequest, onFound: (Int) -> Unit): Listing {
            val storage = listStorage(tree, FakeTree.ROOT, STORAGE, request, onFound)
            return Listing(storage.files, storage.fingerprints)
        }

        override fun download(file: RemoteFile, destination: File): Boolean {
            val bytes = files[file] ?: return false
            destination.writeBytes(bytes)
            return true
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val STORAGE = "card"

        // Not real media: the engine must fall back to the camera's file date when metadata is unreadable.
        val RAW_BYTES = ByteArray(2048) { it.toByte() }
        val VIDEO_BYTES = ByteArray(4096) { (it * 7).toByte() }
    }
}
