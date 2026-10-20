package com.byeonggon.latent.backup

import android.content.Context
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.GridSize
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.TabPosition
import com.byeonggon.latent.data.ThemeMode
import com.byeonggon.latent.data.db.AlbumEntity
import com.byeonggon.latent.data.db.AlbumItemEntity
import com.byeonggon.latent.data.db.FLAG_PICKED
import com.byeonggon.latent.data.db.FLAG_REJECTED
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
import com.byeonggon.latent.data.db.LutEntity
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.ColorLut
import com.byeonggon.latent.enhance.LutLook
import com.byeonggon.latent.enhance.LutStore
import com.byeonggon.latent.enhance.MaskRaster
import com.byeonggon.latent.enhance.MaskStore
import com.byeonggon.latent.enhance.PhotoEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.ZoneId
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Files that are not, or no longer, backups this app can read, and the format of earlier versions, which it keeps reading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupFileTest {
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
        target = TestPhone(context, folders.newFolder("target"), user = 0, settings)
    }

    @After
    fun tearDown() = runBlocking {
        source.close()
        target.close()
        settings.update { AppSettings() }
    }

    private val lutId = "0123456789abcdef0123456789abcdef"
    private val maskId = "fedcba9876543210fedcba9876543210"

    /** A small library with a LUT, a mask and [points] places, backed up; the target phone has its photo. */
    private suspend fun backup(points: Int = 3): File {
        source.use()
        val photo = MediaItemEntity(
            path = source.libraryFile("2026/2026-10-08/DSC00001.ARW").path, fileName = "DSC00001.ARW", kind = MediaKind.RAW,
            sizeBytes = 100, capturedAt = 1, importedAt = 2, rating = 3, edit = PhotoEdit(lut = LutLook(lutId)).toJson(),
        )
        val id = source.database.mediaDao().insert(photo)
        val album = source.database.albumDao().insert(AlbumEntity(name = "Trip", createdAt = 3))
        source.database.albumDao().addItems(listOf(AlbumItemEntity(album, id, 4)))
        LutStore.save(lutId, ColorLut(2, FloatArray(24)))
        source.database.lutDao().insert(LutEntity(name = "Flat", size = 2, hash = lutId, createdAt = 5))
        MaskStore.save(maskId, MaskRaster(2, 2, ByteArray(4)))
        source.database.locationDao().insertAll(List(points) { LocationPointEntity(timestamp = it * 1000L, latitude = 1.0, longitude = 2.0, source = LocationSource.APP) })
        val file = folders.newFile()
        file.outputStream().use { source.writer().write(it) }
        target.use()
        target.onDisk += photo.copy(path = target.libraryFile("2026/2026-10-08/DSC00001.ARW").path, rating = 0, edit = null)
        return file
    }

    /** Fails unless the target phone has nothing of a backup, as before a restore. */
    private suspend fun assertNothingRestored() {
        val dao = target.database.backupDao()
        assertTrue(dao.albums().isEmpty())
        assertEquals(0, dao.countLocations())
        assertTrue(dao.luts().isEmpty())
        assertTrue(target.database.mediaDao().all().all { it.rating == 0 && it.edit == null })
        assertFalse(File(target.luts, "$lutId.lut").exists())
        assertFalse(File(target.masks, "$maskId.mask").exists())
        assertEquals(AppSettings(), settings.settings.first())
    }

    private fun zip(vararg entries: Pair<String, String>): File = folders.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun refusesWhatIsNoZip() = runBlocking {
        val garbage = folders.newFile().apply { writeBytes(ByteArray(4096) { (it * 31 % 251).toByte() }) }
        target.use()

        assertThrows<NotABackupException> { target.restorer().restore(garbage) }
        assertThrows<NotABackupException> { target.restorer().inspect(garbage) }
        assertNothingRestored()
    }

    @Test
    fun refusesAZipWithoutAManifestAndOneOfSomethingElse() = runBlocking {
        target.use()
        val noManifest = zip(BackupFormat.MEDIA to "", BackupFormat.LOCATIONS to """{"timestamp":1,"latitude":1,"longitude":2,"source":"APP"}""")
        val foreign = zip(BackupFormat.MANIFEST to """{"name":"another app's export","version":3}""")
        val unreadable = zip(BackupFormat.MANIFEST to "not json at all {")

        for (file in listOf(noManifest, foreign, unreadable)) {
            assertThrows<NotABackupException> { target.restorer().restore(file) }
        }
        assertNothingRestored()
    }

    @Test
    fun refusesANewerFormatAndSaysWhich() = runBlocking {
        target.use()
        val newer = zip(
            BackupFormat.MANIFEST to """{"format":"latent-backup","formatVersion":2,"createdAt":"2027-01-01T00:00:00Z","counts":{}}""",
            BackupFormat.LOCATIONS to """{"timestamp":1,"latitude":1,"longitude":2,"source":"APP"}""",
        )

        assertEquals(2, assertThrows<NewerBackupException> { target.restorer().inspect(newer) }.version)
        assertThrows<NewerBackupException> { target.restorer().restore(newer) }
        assertNothingRestored()
    }

    @Test
    fun refusesABackupCutShort() = runBlocking {
        val whole = backup()
        val half = folders.newFile().apply { writeBytes(whole.readBytes().copyOf(whole.length().toInt() / 2)) }

        assertThrows<NotABackupException> { target.restorer().restore(half) }
        assertNothingRestored()
    }

    @Test
    fun refusesABackupDamagedInsideAndChangesNothing() = runBlocking {
        val whole = backup(points = 5_000)
        // A byte in the middle of the timeline, which is stored packed.
        val offset = ZipFile(whole).use { zip ->
            val entry = zip.getEntry(BackupFormat.LOCATIONS)
            val bytes = whole.readBytes()
            val name = BackupFormat.LOCATIONS.toByteArray()
            val header = (0 until bytes.size - name.size).first { at -> name.indices.all { bytes[at + it] == name[it] } }
            header + name.size + (entry.compressedSize / 2).toInt()
        }
        val damaged = folders.newFile().apply { writeBytes(whole.readBytes().also { it[offset] = (it[offset].toInt() xor 0x5A).toByte() }) }

        assertThrows<DamagedBackupException> { target.restorer().inspect(damaged) }
        assertThrows<DamagedBackupException> { target.restorer().restore(damaged) }
        assertNothingRestored()
        // The photo the library would have taken in anyway is not even looked for.
        assertTrue(target.database.mediaDao().all().isEmpty())
    }

    @Test
    fun refusesALineThatIsNotARecord() = runBlocking {
        target.use()
        val manifest = BackupManifest(1, Instant.EPOCH, AppIdentity("p", "v", 1), BackupCounts()).toJson().toString()
        val broken = zip(
            BackupFormat.MANIFEST to manifest,
            BackupFormat.ALBUMS to """{"id":1,"name":"Trip","createdAt":3}""",
            BackupFormat.LOCATIONS to "{\"timestamp\":1,\"latitude\":1,\"longitude\":2,\"source\":\"APP\"}\n{\"timestamp\":\"soon\"}\n",
        )

        val error = assertThrows<DamagedBackupException> { target.restorer().restore(broken) }
        assertTrue(error.message!!.contains("locations.jsonl, line 2"))
        assertNothingRestored()
    }

    @Test
    fun aCancelledRestoreLeavesNothingHalfDone() = runBlocking {
        val file = backup(points = 6_000)
        lateinit var job: Job
        var cancelledAt: BackupStep? = null
        job = launch(Dispatchers.Default) {
            target.restorer().restore(file) { step, done, _ ->
                // Well into the transaction: the photo, its album and the LUT are in, a third of the timeline too.
                if (step == BackupStep.TIMELINE && done >= 2_000 && cancelledAt == null) {
                    cancelledAt = step
                    job.cancel()
                }
            }
        }
        job.join()

        assertEquals(BackupStep.TIMELINE, cancelledAt)
        assertTrue(job.isCancelled)
        // The library took the photo in, as it does whenever it opens; nothing of the backup's came with it.
        assertEquals(1, target.database.mediaDao().all().size)
        assertNothingRestored()
    }

    @Test
    fun aFailedRestoreTakesTheFilesItBroughtAwayAgain() = runBlocking {
        val file = backup()
        val failing = Restorer(
            target.database, settings, target.library,
            hasAccess = { true },
            noticeFiles = { target.onDisk.forEach { target.database.mediaDao().insert(it) } },
            hasOriginal = { throw IllegalStateException("The disk went away") },
            phoneRelativePath = { it },
        )
        // Only a JPEG of the library asks for its original; this one is made to fail on it.
        target.onDisk[0] = target.onDisk[0].copy(path = target.libraryFile("2026/2026-10-08/DSC00001.JPG").path, fileName = "DSC00001.JPG", kind = MediaKind.PHOTO)
        rewriteKind(file)

        assertThrows<IllegalStateException> { failing.restore(file) }
        assertNothingRestored()
    }

    /** Makes the backup's one photo a JPEG, as the target phone has it. */
    private fun rewriteKind(file: File) {
        val entries = ZipFile(file).use { zip -> zip.entries().toList().associate { it.name to zip.getInputStream(it).readBytes() } }
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (name == BackupFormat.MEDIA) String(bytes).replace("DSC00001.ARW", "DSC00001.JPG").replace("\"RAW\"", "\"PHOTO\"").toByteArray() else bytes)
                zip.closeEntry()
            }
        }
        File(target.library, ".originals/2026/2026-10-08/DSC00001.JPG").apply { parentFile!!.mkdirs() }.writeText("original")
    }

    @Test
    fun readsTheFirstFormatAsItWasWritten() = runBlocking {
        // Written by hand from docs/BACKUP_FORMAT.md (see make_format1.py beside it): later versions of the app must keep reading it.
        val fixture = folders.newFile().apply { BackupFileTest::class.java.getResourceAsStream("/backup/latent-backup-format1.zip")!!.use { input -> outputStream().use { input.copyTo(it) } } }
        target.use()
        val jpeg = MediaItemEntity(path = target.libraryFile("2025/2025-12-24/DSC09001.JPG").path, fileName = "DSC09001.JPG", kind = MediaKind.PHOTO, sizeBytes = 4_100_000, capturedAt = 1_766_566_500_000, importedAt = 9, edit = UNKNOWN_EDIT)
        File(target.library, ".originals/2025/2025-12-24/DSC09001.JPG").apply { parentFile!!.mkdirs() }.writeText("original")
        // Moved since, to a folder of another day: found by its name and size.
        val raw = MediaItemEntity(path = target.libraryFile("2025/2025-12-25/DSC09001.ARW").path, fileName = "DSC09001.ARW", kind = MediaKind.RAW, sizeBytes = 24_500_000, capturedAt = 1_766_566_500_000, importedAt = 9)
        val phone = MediaItemEntity(path = target.phonePath("DCIM/Camera/20251224_101500.jpg"), fileName = "20251224_101500.jpg", kind = MediaKind.PHOTO, sizeBytes = 3_500_000, capturedAt = 1_766_567_700_000, importedAt = 9, origin = MediaOrigin.PHONE)
        target.onDisk += listOf(jpeg, raw, phone)

        val plan = target.restorer().inspect(fixture)
        assertEquals(1, plan.manifest.formatVersion)
        assertEquals(Instant.parse("2026-10-20T06:30:00Z"), plan.manifest.createdAt)
        assertEquals("0.1.0", plan.manifest.app.versionName)
        assertEquals(3, plan.found)
        val result = target.restorer().restore(fixture)

        val byName = target.database.mediaDao().all().associateBy { it.fileName }
        byName.getValue("DSC09001.JPG").let {
            assertEquals(5, it.rating)
            assertEquals(FLAG_PICKED, it.flag)
            assertEquals("{\"exposure\":0.3}", it.edit)
            assertEquals(GeoSource.TRACK, it.geoSource)
            assertEquals(48.8584, it.latitude!!, 0.0)
            assertEquals(35.0, it.altitude!!, 0.0)
            assertEquals("4321|DSC09001.JPG|4000000|1766566500000", it.sourceKey)
        }
        val restoredRaw = byName.getValue("DSC09001.ARW")
        val rawEdit = PhotoEdit.fromJson(restoredRaw.edit)!!
        assertEquals(-0.5f, rawEdit.adjustments.exposure)
        assertEquals("a1b2c3d4e5f60718293a4b5c6d7e8f90", rawEdit.lut!!.id)
        assertEquals(0.6f, rawEdit.lut!!.intensity)
        assertEquals("0f1e2d3c4b5a69788796a5b4c3d2e1f0", rawEdit.locals.single().mask.detection!!.raster)
        byName.getValue("20251224_101500.jpg").let {
            assertEquals(3, it.rating)
            assertEquals(FLAG_REJECTED, it.flag)
        }
        val album = target.database.backupDao().albums().single()
        assertEquals("Christmas", album.name)
        assertEquals(
            setOf(byName.getValue("DSC09001.JPG").id, byName.getValue("20251224_101500.jpg").id),
            target.database.albumDao().observeMembers(album.id).first().toSet(),
        )
        assertEquals(listOf("Cool"), target.database.backupDao().versionsOf(restoredRaw.id).map { it.name })
        assertEquals(listOf("Faded"), target.database.backupDao().filters().map { it.name })
        assertEquals(listOf("Film"), target.database.backupDao().luts().map { it.name })
        val lut = LutStore.load("a1b2c3d4e5f60718293a4b5c6d7e8f90")!!
        assertEquals(2, lut.size)
        assertEquals(0.9f, lut.table.last())
        assertArrayEquals(byteArrayOf(0, 128.toByte(), 255.toByte(), 255.toByte(), 128.toByte(), 0), MaskStore.load("0f1e2d3c4b5a69788796a5b4c3d2e1f0")!!.levels)
        assertEquals(3, result.locationsAdded)
        assertEquals(2, result.syncRecordsAdded)
        assertEquals(1, target.database.backupDao().syncedFolders().size)
        settings.settings.first().let {
            assertEquals(ThemeMode.LIGHT, it.theme)
            assertEquals(GridSize.SMALL, it.gridSize)
            assertEquals(TabPosition.BOTTOM, it.tabPosition)
            assertEquals(90, it.trashKeptDays)
            assertEquals("Europe/Paris", it.cameraZoneId)
            assertEquals(-2, it.cameraClockOffsetMinutes)
            assertEquals(80, it.jpegQuality)
            assertTrue(it.removeLocationWhenSharing)
            assertFalse(it.syncRaw)
            assertEquals(30, it.geotagMaxGapMinutes)
            assertEquals(setOf("DCIM/Camera/", "Pictures/Snapseed/"), it.phoneFolders)
            assertFalse(it.onlineMaps)
        }
        assertTrue(result.onlineMapsLeftOff)
    }

    @Test
    fun namesTheFileByWhenItWasMade() {
        assertEquals("latent-backup-20261020-0930.zip", BackupFormat.fileName(Instant.parse("2026-10-20T00:30:00Z"), ZoneId.of("Asia/Seoul")))
    }

    private fun <T> Enumeration<T>.toList(): List<T> = buildList { while (hasMoreElements()) add(nextElement()) }
}
