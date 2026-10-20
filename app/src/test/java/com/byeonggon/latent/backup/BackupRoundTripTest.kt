package com.byeonggon.latent.backup

import android.content.Context
import com.byeonggon.latent.camera.ImportedFiles
import com.byeonggon.latent.camera.RemoteFile
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.GridSize
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.TabPosition
import com.byeonggon.latent.data.ThemeMode
import com.byeonggon.latent.data.db.AlbumEntity
import com.byeonggon.latent.data.db.AlbumItemEntity
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.EditVersionEntity
import com.byeonggon.latent.data.db.FLAG_PICKED
import com.byeonggon.latent.data.db.FLAG_REJECTED
import com.byeonggon.latent.data.db.FilterEntity
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
import com.byeonggon.latent.data.db.LutEntity
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.data.db.SyncRecordEntity
import com.byeonggon.latent.data.db.SyncedFolderEntity
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.ColorLut
import com.byeonggon.latent.enhance.DetectedKind
import com.byeonggon.latent.enhance.Detection
import com.byeonggon.latent.enhance.LocalEdit
import com.byeonggon.latent.enhance.LutLook
import com.byeonggon.latent.enhance.LutStore
import com.byeonggon.latent.enhance.Mask
import com.byeonggon.latent.enhance.MaskRaster
import com.byeonggon.latent.enhance.MaskShape
import com.byeonggon.latent.enhance.MaskStore
import com.byeonggon.latent.enhance.PhotoEdit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * A backup made on one phone and restored on another: a library folder and phone photos at other absolute
 * places, an empty database, no LUTs and no masks, the settings as they come.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRoundTripTest {
    @get:Rule
    val folders = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val settings = SettingsRepository(context)
    private lateinit var source: TestPhone
    private lateinit var target: TestPhone

    @Before
    fun setUp() = runBlocking {
        settings.update { AppSettings() }
        source = TestPhone(context, folders.newFolder("source"), user = 0, settings)
        target = TestPhone(context, folders.newFolder("target"), user = 10, settings)
    }

    @After
    fun tearDown() = runBlocking {
        source.close()
        target.close()
        settings.update { AppSettings() }
    }

    private val lutId = "0123456789abcdef0123456789abcdef"
    private val maskId = "fedcba9876543210fedcba9876543210"

    private val maskedEdit = PhotoEdit(
        adjustments = Adjustments(exposure = 0.5f),
        lut = LutLook(lutId, 0.75f),
        locals = listOf(
            LocalEdit(
                Mask(MaskShape.DETECTED, 0f, 0f, 1f, 1f, detection = Detection(DetectedKind.SUBJECT, maskId, "u2netp", 1)),
                Adjustments(exposure = 1f),
            ),
        ),
    ).toJson()
    private val rawEdit = PhotoEdit(adjustments = Adjustments(exposure = -0.3f), lensCorrection = false).toJson()
    private val phoneEdit = PhotoEdit(monochrome = true).toJson()
    private val warmVersion = PhotoEdit(adjustments = Adjustments(exposure = 0.2f)).toJson()

    private fun item(path: String, name: String, kind: MediaKind = MediaKind.PHOTO, origin: MediaOrigin = MediaOrigin.LIBRARY, size: Long = 1000, capturedAt: Long = 1_760_000_000_000) =
        MediaItemEntity(path = path, fileName = name, kind = kind, sizeBytes = size, capturedAt = capturedAt, importedAt = 5, origin = origin)

    private val jpeg = "2026/2026-10-08/DSC00001.JPG"
    private val raw = "2026/2026-10-08/DSC00001.ARW"
    private val camera = "DCIM/Camera/20261008_120000.jpg"
    private val video = "DCIM/Camera/20261008_130000.mp4"
    private val plain = "2026/2026-10-08/DSC00002.JPG"

    /** Ids of the source's photos, by their relative path. */
    private val ids = HashMap<String, Long>()

    /** The source phone's library, with something of every kind the backup carries. */
    private suspend fun fillSource() {
        source.use()
        val dao = source.database.mediaDao()
        ids[jpeg] = dao.insert(
            item(source.libraryFile(jpeg).path, "DSC00001.JPG", size = 2_000).copy(
                rating = 4, flag = FLAG_PICKED, edit = maskedEdit, sourceKey = "ZV-E10|DSC00001.JPG|1800|1759990000000",
                latitude = 33.45, longitude = 126.57, altitude = 12.5, geoSource = GeoSource.TRACK, importedAt = 1_760_000_100_000,
            ),
        )
        ids[raw] = dao.insert(
            item(source.libraryFile(raw).path, "DSC00001.ARW", MediaKind.RAW, size = 25_000_000).copy(
                edit = rawEdit, sourceKey = "ZV-E10|DSC00001.ARW|25000000|1759990000000", latitude = 33.45, longitude = 126.57, geoSource = GeoSource.TRACK,
            ),
        )
        ids[camera] = dao.insert(
            item(source.phonePath(camera), "20261008_120000.jpg", origin = MediaOrigin.PHONE, size = 3_000).copy(
                rating = 2, edit = phoneEdit, latitude = 37.5, longitude = 127.0, geoSource = GeoSource.CAMERA,
            ),
        )
        ids[video] = dao.insert(item(source.phonePath(video), "20261008_130000.mp4", MediaKind.VIDEO, MediaOrigin.PHONE, size = 9_000).copy(flag = FLAG_REJECTED))
        // Nothing to give back: the library finds all of it in the file again.
        ids[plain] = dao.insert(item(source.libraryFile(plain).path, "DSC00002.JPG", size = 1_500).copy(latitude = 1.0, longitude = 2.0, geoSource = GeoSource.CAMERA))
        // The trash is not carried.
        dao.insert(item(source.libraryFile(".trash/9/DSC00009.JPG").path, "DSC00009.JPG").copy(rating = 5, trashedAt = 7, trashedFrom = source.libraryFile("2026/x/DSC00009.JPG").path))

        val albums = source.database.albumDao()
        val jeju = albums.insert(AlbumEntity(name = "Jeju", createdAt = 100))
        albums.insert(AlbumEntity(name = "Nothing here", createdAt = 101))
        albums.addItems(listOf(AlbumItemEntity(jeju, ids.getValue(jpeg), 200), AlbumItemEntity(jeju, ids.getValue(camera), 201)))
        source.database.editVersionDao().insert(EditVersionEntity(mediaId = ids.getValue(camera), name = "Warm", edit = warmVersion, createdAt = 300))
        source.database.editVersionDao().insert(EditVersionEntity(mediaId = ids.getValue(raw), name = "Masked", edit = maskedEdit, createdAt = 301))
        source.database.filterDao().insert(FilterEntity(name = "Mine", look = PhotoEdit(lut = LutLook(lutId)).toJson(), createdAt = 400))
        LutStore.save(lutId, ColorLut(2, FloatArray(24) { it / 24f }))
        source.database.lutDao().insert(LutEntity(name = "Negative", size = 2, hash = lutId, createdAt = 500))
        MaskStore.save(maskId, MaskRaster(4, 3, ByteArray(12) { (it * 20).toByte() }))
        source.database.locationDao().insertAll(
            listOf(
                LocationPointEntity(timestamp = 1_000, latitude = 33.1, longitude = 126.1, altitude = 3.0, accuracy = 4.5f, source = LocationSource.APP),
                LocationPointEntity(timestamp = 2_000, latitude = 33.2, longitude = 126.2, source = LocationSource.APP),
                LocationPointEntity(timestamp = 2_000, latitude = 33.25, longitude = 126.25, accuracy = 30f, source = LocationSource.TIMELINE),
            ),
        )
        listOf("ZV-E10|DSC00001.JPG|1800|1759990000000", "ZV-E10|DSC00001.ARW|25000000|1759990000000", "ZV-E10|DSC00003.JPG|1700|1759990100000")
            .forEach { source.database.syncRecordDao().insert(SyncRecordEntity(it, 600)) }
        source.database.syncedFolderDao().upsert(SyncedFolderEntity("ZV-E10", "card:/DCIM/100MSDCF", "fp1", "PHOTO,RAW,VIDEO"))
        settings.update {
            it.copy(
                theme = ThemeMode.DARK, gridSize = GridSize.LARGE, trashKeptDays = 7, tabPosition = TabPosition.RIGHT,
                cameraZoneId = "Asia/Tokyo", cameraClockOffsetMinutes = 3, jpegQuality = 90, removeLocationWhenSharing = true,
                allExif = true, phoneFolders = setOf("DCIM/Camera/"), onlineMaps = true, syncVideos = false, geotagMaxGapMinutes = 20,
            )
        }
    }

    private suspend fun backUp(): File {
        val file = folders.newFile("backup.zip")
        file.outputStream().use { source.writer().write(it) }
        return file
    }

    /** The other phone after a fresh install: the files are there, the database knows none of them yet. */
    private fun putFilesOnTarget(vararg except: String) {
        target.use()
        fun add(entity: MediaItemEntity) = target.onDisk.add(entity)
        if (jpeg !in except) {
            add(item(target.libraryFile(jpeg).path, "DSC00001.JPG", size = 2_000).copy(edit = UNKNOWN_EDIT, latitude = 33.450001, longitude = 126.570001, geoSource = GeoSource.CAMERA))
            File(target.library, ".originals/$jpeg").apply { parentFile!!.mkdirs() }.writeText("original")
        }
        if (raw !in except) add(item(target.libraryFile(raw).path, "DSC00001.ARW", MediaKind.RAW, size = 25_000_000))
        if (camera !in except) add(item(target.phonePath(camera), "20261008_120000.jpg", origin = MediaOrigin.PHONE, size = 3_000).copy(latitude = 37.5, longitude = 127.0, geoSource = GeoSource.CAMERA))
        if (video !in except) add(item(target.phonePath(video), "20261008_130000.mp4", MediaKind.VIDEO, MediaOrigin.PHONE, size = 9_000))
        if (plain !in except) add(item(target.libraryFile(plain).path, "DSC00002.JPG", size = 1_500))
    }

    private suspend fun targetItem(relative: String): MediaItemEntity {
        val path = if (relative.startsWith("DCIM/")) target.phonePath(relative) else target.libraryFile(relative).path
        return target.database.mediaDao().all().single { it.path == path }
    }

    @Test
    fun bringsEverythingBackOnAnotherPhoneWithOtherPlaces() = runBlocking {
        fillSource()
        val backup = backUp()
        putFilesOnTarget()
        settings.update { AppSettings() }

        val result = target.restorer().restore(backup)

        val restoredJpeg = targetItem(jpeg)
        assertEquals(4, restoredJpeg.rating)
        assertEquals(FLAG_PICKED, restoredJpeg.flag)
        // The adopted JPEG knew it was edited, not how; now it does.
        assertEquals(maskedEdit, restoredJpeg.edit)
        assertEquals(GeoSource.TRACK, restoredJpeg.geoSource)
        assertEquals(33.45, restoredJpeg.latitude!!, 0.0)
        assertEquals(12.5, restoredJpeg.altitude!!, 0.0)
        assertEquals("ZV-E10|DSC00001.JPG|1800|1759990000000", restoredJpeg.sourceKey)
        assertEquals(1_760_000_100_000, restoredJpeg.importedAt)
        val restoredRaw = targetItem(raw)
        assertEquals(rawEdit, restoredRaw.edit)
        assertEquals(GeoSource.TRACK, restoredRaw.geoSource)
        val restoredPhone = targetItem(camera)
        assertEquals(2, restoredPhone.rating)
        assertEquals(phoneEdit, restoredPhone.edit)
        assertEquals(FLAG_REJECTED, targetItem(video).flag)
        assertNull(targetItem(plain).edit)

        val albums = target.database.backupDao().albums()
        assertEquals(listOf("Jeju"), albums.map { it.name })
        assertEquals(100, albums.single().createdAt)
        assertEquals(setOf(restoredJpeg.id, restoredPhone.id), target.database.albumDao().observeMembers(albums.single().id).first().toSet())
        assertEquals(listOf("Warm" to warmVersion), target.database.backupDao().versionsOf(restoredPhone.id).map { it.name to it.edit })
        assertEquals(listOf("Masked" to maskedEdit), target.database.backupDao().versionsOf(restoredRaw.id).map { it.name to it.edit })
        assertEquals(listOf("Mine"), target.database.backupDao().filters().map { it.name })
        assertEquals(listOf(lutId to "Negative"), target.database.backupDao().luts().map { it.hash to it.name })

        // The files edits refer to, under the same ids, as they were.
        assertArrayEquals(File(source.luts, "$lutId.lut").readBytes(), File(target.luts, "$lutId.lut").readBytes())
        assertArrayEquals(File(source.masks, "$maskId.mask").readBytes(), File(target.masks, "$maskId.mask").readBytes())
        assertEquals(24, LutStore.load(lutId)!!.table.size)
        assertEquals(12, MaskStore.load(maskId)!!.levels.size)

        fun points(database: AppDatabase) = runBlocking { database.backupDao().locationsAfter(0, 100).map { it.copy(id = 0) }.toSet() }
        assertEquals(points(source.database), points(target.database))
        assertEquals(source.database.syncRecordDao().allKeys().toSet(), target.database.syncRecordDao().allKeys().toSet())
        assertEquals(source.database.backupDao().syncedFolders(), target.database.backupDao().syncedFolders())

        val restoredSettings = settings.settings.first()
        assertEquals(ThemeMode.DARK, restoredSettings.theme)
        assertEquals(GridSize.LARGE, restoredSettings.gridSize)
        assertEquals(7, restoredSettings.trashKeptDays)
        assertEquals(TabPosition.RIGHT, restoredSettings.tabPosition)
        assertEquals("Asia/Tokyo", restoredSettings.cameraZoneId)
        assertEquals(3, restoredSettings.cameraClockOffsetMinutes)
        assertEquals(90, restoredSettings.jpegQuality)
        assertTrue(restoredSettings.removeLocationWhenSharing)
        assertTrue(restoredSettings.allExif)
        assertEquals(setOf("DCIM/Camera/"), restoredSettings.phoneFolders)
        assertFalse(restoredSettings.syncVideos)
        assertEquals(20, restoredSettings.geotagMaxGapMinutes)
        // Never turned on without asking.
        assertFalse(restoredSettings.onlineMaps)
        assertTrue(result.onlineMapsLeftOff)

        assertEquals(4, result.photos)
        assertEquals(0, result.missing.count)
        assertEquals(1, result.albums)
        assertEquals(2, result.versionsAdded)
        assertEquals(1, result.filtersAdded)
        assertEquals(1, result.lutsAdded)
        assertEquals(1, result.masksAdded)
        assertEquals(3, result.locationsAdded)
        assertEquals(3, result.syncRecordsAdded)
        // The phone's own folders were looked through with the backup's.
        assertTrue("DCIM/Camera/" in target.noticed!!)
    }

    @Test
    fun saysWhatARestoreWouldDoBeforeChangingAnything() = runBlocking {
        fillSource()
        val backup = backUp()
        putFilesOnTarget(raw)
        settings.update { AppSettings() }
        // A rating given on this phone, which the backup's replaces.
        target.onDisk.replaceAll { if (it.fileName == "20261008_120000.jpg") it.copy(rating = 5) else it }

        val plan = target.restorer().inspect(backup)

        assertEquals(4, plan.photos)
        assertEquals(3, plan.found)
        assertEquals(MissingPhotos(1, listOf("DSC00001.ARW")), plan.missing)
        assertEquals(1, plan.replaced)
        assertEquals(1, plan.albums)
        // The raw file's version has no photo to go with.
        assertEquals(1, plan.versions)
        assertEquals(1, plan.filters)
        assertEquals(1, plan.luts)
        assertEquals(1, plan.masks)
        assertEquals(3, plan.locations)
        assertEquals(3, plan.syncRecords)
        assertTrue(plan.onlineMapsInBackup)
        assertEquals(Instant.ofEpochMilli(NOW), plan.manifest.createdAt)
        assertEquals("0.1.0-test", plan.manifest.app.versionName)
        // Only what the library itself would have taken in.
        assertEquals(5, targetItem(camera).rating)
        assertTrue(target.database.backupDao().albums().isEmpty())
        assertEquals(0, target.database.backupDao().countLocations())
        assertFalse(File(target.luts, "$lutId.lut").exists())
        assertEquals(AppSettings(), settings.settings.first())
    }

    @Test
    fun countsAndNamesThePhotosThatAreNotOnThePhone() = runBlocking {
        fillSource()
        val backup = backUp()
        putFilesOnTarget(camera, video)

        val result = target.restorer().restore(backup)

        assertEquals(2, result.photos)
        assertEquals(2, result.missing.count)
        assertEquals(listOf("20261008_120000.jpg", "20261008_130000.mp4"), result.missing.names.sorted())
        // The album keeps the photo that is here; the phone photo's version has nothing to go with.
        val album = target.database.backupDao().albums().single()
        assertEquals(listOf(targetItem(jpeg).id), target.database.albumDao().observeMembers(album.id).first())
        assertEquals(1, result.versionsAdded)
        assertEquals(3, result.locationsAdded)
    }

    @Test
    fun restoringTwiceChangesNothingMore() = runBlocking {
        fillSource()
        val backup = backUp()
        putFilesOnTarget()
        val restorer = target.restorer()
        restorer.restore(backup)
        fun snapshot() = runBlocking {
            val dao = target.database.backupDao()
            listOf(
                target.database.mediaDao().all().toSet(), dao.albums(), dao.versionsAfter(0, 100), dao.filters(), dao.luts(),
                dao.syncRecordsAfter("", 100), dao.syncedFolders(), dao.locationsAfter(0, 100), target.luts.list()!!.toSet(), target.masks.list()!!.toSet(),
                dao.albumItemsAfter(Long.MIN_VALUE, Long.MIN_VALUE, 100),
            )
        }
        val first = snapshot()

        val again = restorer.restore(backup)

        assertEquals(first, snapshot())
        assertEquals(4, again.photos)
        assertEquals(1, again.albums)
        assertEquals(0, again.versionsAdded)
        assertEquals(0, again.filtersAdded)
        assertEquals(0, again.lutsAdded)
        assertEquals(0, again.masksAdded)
        assertEquals(0, again.locationsAdded)
        assertEquals(0, again.syncRecordsAdded)
    }

    @Test
    fun aRestoredPhoneDoesNotImportTheCameraFilesAgainInAnotherTimeZone() = runBlocking {
        fillSource()
        val backup = backUp()
        putFilesOnTarget()

        target.restorer().restore(backup)

        val imported = ImportedFiles(target.database.syncRecordDao().allKeys())
        val hour = 3_600_000L
        // The camera's wall-clock time read by a phone nine hours away, and a file that was deleted from the phone since.
        assertTrue(imported.contains(RemoteFile(1, "DSC00001.JPG", 1800, 1_759_990_000_000 - 9 * hour, MediaKind.PHOTO, "card:/"), "ZV-E10"))
        assertTrue(imported.contains(RemoteFile(2, "DSC00003.JPG", 1700, 1_759_990_100_000 + 5 * hour, MediaKind.PHOTO, "card:/"), "ZV-E10"))
        assertFalse(imported.contains(RemoteFile(3, "DSC00004.JPG", 1700, 1_759_990_100_000, MediaKind.PHOTO, "card:/"), "ZV-E10"))
    }

    @Test
    fun leavesOnlineMapsOnWhereTheyWereOnAndNeverTurnsThemOn() = runBlocking {
        fillSource()
        val backup = backUp()
        putFilesOnTarget()
        settings.update { AppSettings(onlineMaps = true) }

        val result = target.restorer().restore(backup)

        assertTrue(settings.settings.first().onlineMaps)
        assertFalse(result.onlineMapsLeftOff)

        // A backup made with maps off does not turn them off either: it says nothing of the internet.
        settings.update { it.copy(onlineMaps = false) }
        source.use()
        val off = folders.newFile("off.zip").also { file -> file.outputStream().use { source.writer().write(it) } }
        target.use()
        settings.update { it.copy(onlineMaps = true) }
        target.restorer().restore(off)
        assertTrue(settings.settings.first().onlineMaps)
    }

    @Test
    fun refusesWithoutAccessToTheLibrary() = runBlocking {
        fillSource()
        val backup = backUp()
        putFilesOnTarget()
        target.access = false

        assertThrows<NoLibraryAccessException> { target.restorer().restore(backup) }
        assertTrue(target.database.mediaDao().all().isEmpty())
        assertEquals(null, target.noticed)
    }

    private companion object {
        const val NOW = 1_760_950_000_000L
    }
}
