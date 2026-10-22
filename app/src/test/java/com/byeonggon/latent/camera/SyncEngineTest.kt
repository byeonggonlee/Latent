package com.byeonggon.latent.camera

import com.byeonggon.latent.grantLibraryAccess
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.LibraryIndex
import com.byeonggon.latent.data.LibraryStorage
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.location.Geotagger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import com.byeonggon.latent.data.db.MediaItemEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
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
    fun skipsOnTheCardWhatCameOverMtpAndTheOtherWayRound() = runBlocking {
        val photo = jpegTakenAt("2026:10:01 12:00:00", "+09:00")
        val overMtp = remote("DSC00001.JPG", MediaKind.PHOTO, photo)
        val onCard = remote("DSC00002.ARW", MediaKind.RAW, RAW_BYTES)
        val card = CameraInfo("", "Card", "folder:1234-ABCD:")
        engine.run(FakeCameraSource(mapOf(overMtp to photo)))
        // The card's reader gives the time of the same file in another zone.
        val zoneShift = 9 * 60 * minute
        val sameOnCard = overMtp.copy(handle = nextHandle++, modifiedAt = overMtp.modifiedAt + zoneShift)

        engine.run(FakeCameraSource(mapOf(sameOnCard to photo, onCard to RAW_BYTES), card))

        assertEquals(SyncState.Finished(imported = 1, geotagged = 0, failed = emptyList(), cancelled = false), engine.state.value)
        engine.run(FakeCameraSource(mapOf(overMtp to photo, onCard.copy(handle = nextHandle++) to RAW_BYTES)))

        assertEquals(SyncState.Finished(imported = 0, geotagged = 0, failed = emptyList(), cancelled = false), engine.state.value)
        assertEquals(setOf("DSC00001.JPG", "DSC00002.ARW"), database.mediaDao().all().map { it.fileName }.toSet())
    }

    @Test
    fun knowsOnTheCardAJpegWhosePlaceWasWrittenInOnTheWayIn() = runBlocking {
        recordTrackAroundCapture()
        val photo = jpegTakenAt("2026:10:01 12:00:00", "+09:00")
        val overMtp = remote("DSC00001.JPG", MediaKind.PHOTO, photo)
        engine.run(FakeCameraSource(mapOf(overMtp to photo)))
        val inLibrary = File(database.mediaDao().all().single().path)
        assertFalse("its EXIF was rewritten with the place", inLibrary.readBytes().contentEquals(photo))

        engine.run(FakeCameraSource(mapOf(overMtp.copy(handle = nextHandle++) to photo), CameraInfo("", "Card", "folder:1234-ABCD:")))

        assertEquals(SyncState.Finished(imported = 0, geotagged = 0, failed = emptyList(), cancelled = false), engine.state.value)
    }

    @Test
    fun importsWhatTwoCardsHoldUnderOneNameWhenItIsNotTheSame() = runBlocking {
        // Two cameras whose numbers started again: the same name and size, times a quarter hour apart, other pictures.
        val first = RAW_BYTES
        val second = ByteArray(RAW_BYTES.size) { (it * 3 + 1).toByte() }
        val onFirstCard = remote("DSC00001.ARW", MediaKind.RAW, first)
        val onSecondCard = remote("DSC00001.ARW", MediaKind.RAW, second, modifiedAt = capturedAt + 15 * minute)
        engine.run(FakeCameraSource(mapOf(onFirstCard to first), CameraInfo("", "Card", "folder:1111-AAAA:")))

        engine.run(FakeCameraSource(mapOf(onSecondCard to second), CameraInfo("", "Card", "folder:2222-BBBB:")))

        assertEquals(SyncState.Finished(imported = 1, geotagged = 0, failed = emptyList(), cancelled = false), engine.state.value)
        assertEquals(
            setOf(first.toList(), second.toList()),
            database.mediaDao().all().map { File(it.path).readBytes().toList() }.toSet(),
        )
    }

    @Test
    fun importsAFileWhoseEndIsTheSameButNotTheRest() = runBlocking {
        // Raw files whose top rows differ can end in the same rows, or the same padding.
        val end = ByteArray(128 * 1024) { (it % 251).toByte() }
        val first = byteArrayOf(1, 2, 3, 4) + end
        val second = byteArrayOf(9, 2, 3, 4) + end
        val onFirstCard = remote("DSC00001.DNG", MediaKind.RAW, first)
        val onSecondCard = remote("DSC00001.DNG", MediaKind.RAW, second, modifiedAt = capturedAt + 15 * minute)
        engine.run(FakeCameraSource(mapOf(onFirstCard to first), CameraInfo("", "Card", "folder:1111-AAAA:")))

        engine.run(FakeCameraSource(mapOf(onSecondCard to second), CameraInfo("", "Card", "folder:2222-BBBB:")))

        assertEquals(SyncState.Finished(imported = 1, geotagged = 0, failed = emptyList(), cancelled = false), engine.state.value)
        assertEquals(2, database.mediaDao().all().size)
    }

    @Test
    fun importsOffTheCardAFileThatOnlyLooksLikeOneThatCameOverMtp() = runBlocking {
        val overMtp = remote("DSC00001.ARW", MediaKind.RAW, RAW_BYTES)
        engine.run(FakeCameraSource(mapOf(overMtp to RAW_BYTES)))
        val other = ByteArray(RAW_BYTES.size) { (it * 5).toByte() }

        engine.run(FakeCameraSource(mapOf(overMtp.copy(handle = nextHandle++) to other), CameraInfo("", "Card", "folder:1234-ABCD:")))

        assertEquals(SyncState.Finished(imported = 1, geotagged = 0, failed = emptyList(), cancelled = false), engine.state.value)
        assertEquals(2, database.mediaDao().all().size)
    }

    @Test
    fun takesCopiesAlreadyInTheLibraryForImported() = runBlocking {
        // Copied over from another phone: the library holds it, without knowing which camera it came from.
        val copy = File(storage.root, "2026/2026-10-01/DSC00060.ARW").apply {
            parentFile!!.mkdirs()
            writeBytes(RAW_BYTES)
        }
        database.mediaDao().insert(
            MediaItemEntity(path = copy.absolutePath, fileName = copy.name, kind = MediaKind.RAW, sizeBytes = copy.length(), capturedAt = capturedAt, importedAt = 1),
        )
        val files = mapOf(
            remote("DSC00060.ARW", MediaKind.RAW, RAW_BYTES) to RAW_BYTES,
            remote("DSC00061.ARW", MediaKind.RAW, RAW_BYTES) to RAW_BYTES,
        )

        engine.run(FakeCameraSource(files))

        assertEquals(1, (engine.state.value as SyncState.Finished).imported)
        val library = database.mediaDao().all()
        assertEquals(listOf("DSC00060.ARW", "DSC00061.ARW"), library.map { it.fileName }.sorted())
        // From now on it counts as imported from this camera, and is not counted as deleted from the phone.
        assertTrue(library.all { it.sourceKey != null })
        assertEquals(0, database.syncRecordDao().observeCountWithoutLibraryFile().first())
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
    fun doesNotImportAFileAgainAfterThePhoneChangesTimeZone() = runBlocking {
        engine.run(FakeCameraSource(mapOf(remote("DSC00060.ARW", MediaKind.RAW, RAW_BYTES) to RAW_BYTES)))

        // The camera reports the same wall-clock time, which the phone now reads eight hours apart.
        val seenFromAnotherZone = remote("DSC00060.ARW", MediaKind.RAW, RAW_BYTES, capturedAt + 8 * 60 * minute)
        engine.run(FakeCameraSource(mapOf(seenFromAnotherZone to RAW_BYTES)))

        assertEquals(0, (engine.state.value as SyncState.Finished).imported)
        assertEquals(1, database.mediaDao().all().size)
    }

    @Test
    fun importsAFileDeletedFromThePhoneAgainOnlyOnceItsHistoryIsForgotten() = runBlocking {
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val library = LibraryIndex(storage, database)
        val files = mapOf(
            remote("DSC00070.ARW", MediaKind.RAW, RAW_BYTES) to RAW_BYTES,
            remote("DSC00071.ARW", MediaKind.RAW, RAW_BYTES) to RAW_BYTES,
        )
        engine.run(FakeCameraSource(files))
        File(database.mediaDao().all().first { it.fileName == "DSC00070.ARW" }.path).delete()

        // Deleting a photo on the phone does not bring it back at the next sync by itself.
        library.forgetMissingFiles()
        engine.run(FakeCameraSource(files))
        assertEquals(0, (engine.state.value as SyncState.Finished).imported)
        assertEquals(1, library.deletedFromPhone.first())

        assertEquals(1, library.allowReimportOfDeletedFiles())
        engine.run(FakeCameraSource(files))

        // Only the deleted file comes back; the one still in the library is not imported twice.
        assertEquals(1, (engine.state.value as SyncState.Finished).imported)
        assertEquals(listOf("DSC00070.ARW", "DSC00071.ARW"), database.mediaDao().all().map { it.fileName }.sorted())
        assertEquals(0, library.deletedFromPhone.first())
    }

    @Test
    fun stopsBeforeFillingThePhoneAndTakesTheRestNextTime() = runBlocking {
        val files = mapOf(
            remote("DSC00010.ARW", MediaKind.RAW, RAW_BYTES, modifiedAt = capturedAt) to RAW_BYTES,
            remote("DSC00011.ARW", MediaKind.RAW, RAW_BYTES, modifiedAt = capturedAt + minute) to RAW_BYTES,
            remote("DSC00012.ARW", MediaKind.RAW, RAW_BYTES, modifiedAt = capturedAt + 2 * minute) to RAW_BYTES,
        )
        // Room for the margin and two files, not for the third.
        var free = 500L * 1024 * 1024 + 2 * RAW_BYTES.size + 100
        val limited = SyncEngine(storage, database, settings, Geotagger(database.locationDao(), database.mediaDao(), storage), freeBytes = { free })

        limited.run(FakeCameraSource(files) .also { it.onDownload = { free -= RAW_BYTES.size } })

        val finished = limited.state.value as SyncState.Finished
        assertEquals(2, finished.imported)
        assertTrue(finished.outOfSpace)
        assertEquals(emptyList<String>(), finished.failed)
        assertEquals(listOf("DSC00010.ARW", "DSC00011.ARW"), database.mediaDao().all().map { it.fileName }.sorted())

        free = Long.MAX_VALUE
        limited.run(FakeCameraSource(files))
        assertEquals(1, (limited.state.value as SyncState.Finished).imported)
        assertFalse((limited.state.value as SyncState.Finished).outOfSpace)
    }

    @Test
    fun doesNotCopyAFileOfUnknownSizeWithoutRoomForFourGigabytes() = runBlocking {
        // The camera reports no size for a file of 4 GB or more; it is not a file of no size.
        val bytes = ByteArray(2_000)
        val big = RemoteFile(nextHandle++, "C0001.MP4", RemoteFile.UNKNOWN_SIZE, capturedAt, MediaKind.VIDEO, folder = "$STORAGE:/100MSDCF/")
        val files = mapOf(big to bytes)
        var free = 3L * 1024 * 1024 * 1024
        val limited = SyncEngine(storage, database, settings, Geotagger(database.locationDao(), database.mediaDao(), storage), freeBytes = { free })

        limited.run(FakeCameraSource(files))
        val stopped = limited.state.value as SyncState.Finished
        assertTrue(stopped.outOfSpace)
        assertEquals(0, stopped.imported)

        // With room for it and the margin, it is copied.
        free = 5L * 1024 * 1024 * 1024
        limited.run(FakeCameraSource(files))
        assertEquals(1, (limited.state.value as SyncState.Finished).imported)
    }

    @Test
    fun placesPhotosByTheRealTimeWhenTheCamerasClockIsOff() = runBlocking {
        recordTrackAroundCapture()
        // The camera's clock runs 40 minutes ahead: its photo of 12:00 was really taken at 11:20, when no position was recorded.
        val photo = jpegTakenAt("2026:10:01 12:40:00", "+09:00")
        val files = mapOf(remote("DSC00020.JPG", MediaKind.PHOTO, photo, modifiedAt = capturedAt + 40 * minute) to photo)

        engine.run(FakeCameraSource(files))
        assertEquals(GeoSource.NONE, database.mediaDao().all().single().geoSource)

        // With the offset known, the same kind of photo is matched to where the phone was at the real time.
        settings.update { it.copy(cameraClockOffsetMinutes = 40) }
        val other = jpegTakenAt("2026:10:01 12:40:00", "+09:00")
        engine.run(FakeCameraSource(mapOf(remote("DSC00021.JPG", MediaKind.PHOTO, other, modifiedAt = capturedAt + 41 * minute) to other)))

        val matched = database.mediaDao().all().single { it.fileName == "DSC00021.JPG" }
        assertEquals(GeoSource.TRACK, matched.geoSource)
        // It is still filed under the time the camera gave it.
        assertEquals(capturedAt + 40 * minute, matched.capturedAt)
        assertEquals(37.1, matched.latitude!!, 1e-6)
    }

    @Test
    fun readsATimeWithoutAZoneInTheZoneTheCameraIsSetTo() = runBlocking {
        recordTrackAroundCapture()
        // The photo says 12:00 and nothing else; the camera is set to Seoul, wherever the phone is.
        settings.update { it.copy(cameraZoneId = "Asia/Seoul") }
        val photo = jpegTakenAt("2026:10:01 12:00:00", null)
        val files = mapOf(remote("DSC00030.JPG", MediaKind.PHOTO, photo) to photo)

        engine.run(FakeCameraSource(files))

        val stored = database.mediaDao().all().single()
        assertEquals(capturedAt, stored.capturedAt)
        assertEquals(GeoSource.TRACK, stored.geoSource)
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

    private fun jpegTakenAt(dateTime: String, offset: String?): ByteArray {
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
    private class FakeCameraSource(
        private val files: Map<RemoteFile, ByteArray?>,
        private val info: CameraInfo = CameraInfo("Sony", "ZV-E10", "SERIAL"),
    ) : CameraSource {
        val tree = FakeTree()
        var closed = false
        var onDownload: () -> Unit = {}

        init {
            val folders = HashMap<String, Int>()
            for (file in files.keys) {
                val name = file.folder.removePrefix("$STORAGE:/").removeSuffix("/")
                val folder = folders.getOrPut(name) { tree.folder(FakeTree.ROOT, name) }
                tree.file(folder, file.name, file.sizeBytes, file.modifiedAt, file.handle)
            }
        }

        override fun open() = info

        override fun listFiles(request: ListingRequest, onFound: (Int) -> Unit): Listing {
            val storage = listStorage(tree, FakeTree.ROOT, STORAGE, request, onFound)
            return Listing(storage.files, storage.fingerprints)
        }

        override fun download(file: RemoteFile, destination: File): Boolean {
            val bytes = files[file] ?: return false
            destination.writeBytes(bytes)
            onDownload()
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
