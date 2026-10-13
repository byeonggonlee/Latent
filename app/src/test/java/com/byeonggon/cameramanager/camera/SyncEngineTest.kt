package com.byeonggon.cameramanager.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
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
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncEngineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val capturedAt = OffsetDateTime.parse("2026-10-01T12:00:00+09:00").toInstant().toEpochMilli()
    private val minute = 60_000L

    private lateinit var database: AppDatabase
    private lateinit var storage: LibraryStorage
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        storage = LibraryStorage(context)
        val geotagger = Geotagger(database.locationDao(), database.mediaDao(), storage)
        engine = SyncEngine(storage, database, SettingsRepository(context), geotagger)
    }

    @After
    fun tearDown() {
        database.close()
        storage.root.deleteRecursively()
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

    private fun remote(name: String, kind: MediaKind, bytes: ByteArray) =
        RemoteFile(handle = name.hashCode(), name = name, sizeBytes = bytes.size.toLong(), modifiedAt = capturedAt, kind = kind)

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

    /** Serves files from memory; a null payload simulates a transfer that fails. */
    private class FakeCameraSource(private val files: Map<RemoteFile, ByteArray?>) : CameraSource {
        var closed = false

        override fun open() = CameraInfo("Sony", "ZV-E10", "SERIAL")

        override fun listFiles(onFound: (Int) -> Unit): List<RemoteFile> =
            files.keys.toList().also { onFound(it.size) }

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
        // Not real media: the engine must fall back to the camera's file date when metadata is unreadable.
        val RAW_BYTES = ByteArray(2048) { it.toByte() }
        val VIDEO_BYTES = ByteArray(4096) { (it * 7).toByte() }
    }
}
