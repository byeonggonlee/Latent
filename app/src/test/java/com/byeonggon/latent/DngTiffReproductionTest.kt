package com.byeonggon.latent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import coil3.SingletonImageLoader
import com.byeonggon.latent.camera.CameraInfo
import com.byeonggon.latent.camera.CameraSource
import com.byeonggon.latent.camera.Listing
import com.byeonggon.latent.camera.ListingRequest
import com.byeonggon.latent.camera.RemoteFile
import com.byeonggon.latent.camera.RemoteObject
import com.byeonggon.latent.camera.RemoteTree
import com.byeonggon.latent.camera.SyncEngine
import com.byeonggon.latent.camera.SyncState
import com.byeonggon.latent.camera.listStorage
import com.byeonggon.latent.data.LibraryIndex
import com.byeonggon.latent.data.PhoneLibrary
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.data.shotOf
import com.byeonggon.latent.location.GeoPoint
import com.byeonggon.latent.location.MediaMetadataReader
import com.byeonggon.latent.raw.Dng
import com.byeonggon.latent.raw.RawRenderer
import com.byeonggon.latent.raw.SyntheticArw
import com.byeonggon.latent.raw.SyntheticDng
import com.byeonggon.latent.ui.viewer.PhotoDetails
import com.byeonggon.latent.ui.viewer.shareMimeType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w600dp-h900dp-port-mdpi")
class DngTiffReproductionTest {
    class FakeMediaStore : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection ?: arrayOf("_id")
            val cursor = MatrixCursor(columns)
            for ((index, row) in rows.withIndex()) {
                val path = row[0] as String
                val name = row[2] as String
                val mediaType = row[5] as Int
                if (selection?.contains("_data = ?") == true && path != selectionArgs?.firstOrNull()) continue
                if (selection?.contains("media_type") == true) {
                    val allowedType = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ||
                        mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val matchesDngTiff = selection.lowercase().contains(".dng.tiff") &&
                        name.lowercase().endsWith(".dng.tiff")
                    if (!allowedType && !matchesDngTiff) continue
                }
                cursor.addRow(columns.map { column ->
                    when (column) {
                        "_data" -> row[0]
                        "relative_path" -> row[1]
                        "DISPLAY_NAME", "_display_name" -> row[2]
                        "_size" -> row[3]
                        "datetaken" -> row[4]
                        "media_type" -> row[5]
                        "_id" -> index + 1L
                        else -> null
                    }
                })
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    }

    private companion object {
        val rows = ArrayList<Array<Any?>>()
        const val CAPTURED_AT = 1_790_812_800_000L
    }

    @get:Rule(order = 0)
    val environment = object : TestWatcher() {
        @OptIn(coil3.annotation.DelicateCoilApi::class)
        override fun starting(description: Description) {
            rows.clear()
            SingletonImageLoader.reset()
            androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {
                isAccessible = true
                (get(null) as MutableMap<*, *>).clear()
            }
            ShadowEnvironment.addExternalDir("shared")
            ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
            forgetTheApplicationOfEarlierTests()
            grantLibraryAccess()
            Robolectric.buildContentProvider(FakeMediaStore::class.java).create("media")
        }

        override fun finished(description: Description) {
            rows.clear()
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = compose.activity
    private val container get() = compose.activity.application.appContainer

    private lateinit var validDngBytes: ByteArray
    private lateinit var plainTiffBytes: ByteArray
    private lateinit var stripTruncatedDngBytes: ByteArray
    private lateinit var tailTruncatedDngBytes: ByteArray

    @Before
    fun setUpFixtures() {
        validDngBytes = SyntheticDng.file(red = 200, green = 400, blue = 300, orientation = 6)
        plainTiffBytes = createPlainTiffBytes()
        stripTruncatedDngBytes = validDngBytes.copyOf(4096 + 100)
        tailTruncatedDngBytes = validDngBytes.copyOf(validDngBytes.size - 32)
    }

    @After
    fun cleanUp() {
        container.storage.root.deleteRecursively()
    }

    @Test
    fun recognizesDngTiffExtensionAndCaseVariants() {
        assertEquals(MediaKind.RAW, MediaKind.fromFileName("IMG_0001.dng"))
        assertEquals(MediaKind.RAW, MediaKind.fromFileName("IMG_0001.dng.tiff"))
        assertEquals(MediaKind.RAW, MediaKind.fromFileName("IMG_0002.DNG.TIFF"))
        assertEquals(MediaKind.RAW, MediaKind.fromFileName("IMG_0003.Dng.Tiff"))
        assertNull(MediaKind.fromFileName("plain.tiff"))
        assertNull(MediaKind.fromFileName(".dng.tiff"))
    }

    @Test
    fun pairsDngTiffWithJpegAndPreservesSuffixOnUniqueCollision() {
        val dir = File(container.storage.root, "2026/2026-10-01").apply { mkdirs() }
        assertEquals(shotOf(File(dir, "IMG_0001.jpg")), shotOf(File(dir, "IMG_0001.dng")))
        assertEquals(shotOf(File(dir, "IMG_0001.jpg")), shotOf(File(dir, "IMG_0001.dng.tiff")))
        assertEquals(shotOf(File(dir, "IMG_0002.JPG")), shotOf(File(dir, "IMG_0002.DNG.TIFF")))

        val existing = File(dir, "IMG_0001.dng.tiff").apply { writeBytes(validDngBytes) }
        val unique = container.storage.freeFileLike(existing)
        assertEquals("IMG_0001_1.dng.tiff", unique.name)
    }

    @Test
    fun cameraScanAndSyncImportValidDngTiffAndRejectPlainOrDamagedTiff() = runBlocking {
        val listing = listStorage(
            tree = object : RemoteTree {
                override fun childrenOf(parent: Int) = if (parent == 0) intArrayOf(1, 2, 3, 4) else null
                override fun objectOf(handle: Int) = when (handle) {
                    0 -> RemoteObject("DCIM", isFolder = true, sizeBytes = 0L, modifiedAt = 0L)
                    1 -> RemoteObject("DSC0001.dng.tiff", isFolder = false, sizeBytes = validDngBytes.size.toLong(), modifiedAt = CAPTURED_AT)
                    2 -> RemoteObject("DSC0002.DNG.TIFF", isFolder = false, sizeBytes = validDngBytes.size.toLong(), modifiedAt = CAPTURED_AT)
                    3 -> RemoteObject("DSC0003.JPG", isFolder = false, sizeBytes = 100L, modifiedAt = CAPTURED_AT)
                    4 -> RemoteObject("PLAIN.tiff", isFolder = false, sizeBytes = plainTiffBytes.size.toLong(), modifiedAt = CAPTURED_AT)
                    else -> null
                }
            },
            rootHandle = 0,
            storageName = "card",
            request = ListingRequest(),
            onFound = {},
        )
        assertEquals(
            setOf("DSC0001.dng.tiff", "DSC0002.DNG.TIFF", "DSC0003.JPG"),
            listing.files.map { it.name }.toSet(),
        )
        assertEquals(MediaKind.RAW, listing.files.first { it.name == "DSC0001.dng.tiff" }.kind)
        assertEquals(MediaKind.RAW, listing.files.first { it.name == "DSC0002.DNG.TIFF" }.kind)
        assertEquals(mapOf("tiff" to 1), listing.skippedExtensions)

        val remoteValid = RemoteFile(10, "CAM_VALID.dng.tiff", validDngBytes.size.toLong(), CAPTURED_AT, MediaKind.RAW, "DCIM")
        val remotePlain = RemoteFile(11, "CAM_PLAIN.dng.tiff", plainTiffBytes.size.toLong(), CAPTURED_AT, MediaKind.RAW, "DCIM")
        val remoteDamaged = RemoteFile(12, "CAM_DAMAGED.dng.tiff", stripTruncatedDngBytes.size.toLong(), CAPTURED_AT, MediaKind.RAW, "DCIM")
        val source = object : CameraSource {
            private val payloads = mapOf(
                10 to validDngBytes,
                11 to plainTiffBytes,
                12 to stripTruncatedDngBytes,
            )
            override fun open() = CameraInfo("Sony", "TestCam", "SN1")
            override fun listFiles(request: ListingRequest, onFound: (Int) -> Unit) =
                Listing(listOf(remoteValid, remotePlain, remoteDamaged), emptyMap())

            override fun download(file: RemoteFile, destination: File): Boolean {
                destination.writeBytes(payloads.getValue(file.handle))
                return true
            }

            override fun close() {}
        }

        container.syncEngine.run(source)
        val finished = container.syncEngine.state.value as SyncState.Finished
        assertEquals(1, finished.imported)
        assertEquals(setOf("CAM_PLAIN.dng.tiff", "CAM_DAMAGED.dng.tiff"), finished.failed.toSet())

        val stored = container.database.mediaDao().all()
        assertEquals(1, stored.size)
        assertEquals("CAM_VALID.dng.tiff", stored.single().fileName)
        assertEquals(MediaKind.RAW, stored.single().kind)
        assertArrayEquals(validDngBytes, File(stored.single().path).readBytes())
        assertEquals(
            setOf(remoteValid.sourceKey("SN1")),
            container.database.backupDao().syncRecordsAfter("", 100).map { r -> r.sourceKey }.toSet(),
        )
    }

    @Test
    fun phoneLibraryAndLibraryIndexAcceptValidDngTiffAndRejectPlainOrDamagedTiff() = runBlocking {
        val cameraDir = File(Environment.getExternalStorageDirectory(), "DCIM/Camera").apply { mkdirs() }
        val phoneValid = File(cameraDir, "PHONE_OK.DNG.TIFF").apply { writeBytes(validDngBytes) }
        val phoneValidNoneType = File(cameraDir, "PHONE_NONE.dng.tiff").apply { writeBytes(validDngBytes) }
        val phonePlain = File(cameraDir, "PHONE_PLAIN.dng.tiff").apply { writeBytes(plainTiffBytes) }
        val phoneDamaged = File(cameraDir, "PHONE_BAD.dng.tiff").apply { writeBytes(stripTruncatedDngBytes) }
        val phoneRealPlainTiff = File(cameraDir, "REAL_PLAIN.tiff").apply { writeBytes(plainTiffBytes) }

        rows += arrayOf(phoneValid.path, "DCIM/Camera/", phoneValid.name, phoneValid.length(), CAPTURED_AT, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
        rows += arrayOf(phoneValidNoneType.path, "DCIM/Camera/", phoneValidNoneType.name, phoneValidNoneType.length(), CAPTURED_AT, MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
        rows += arrayOf(phonePlain.path, "DCIM/Camera/", phonePlain.name, phonePlain.length(), CAPTURED_AT, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
        rows += arrayOf(phoneDamaged.path, "DCIM/Camera/", phoneDamaged.name, phoneDamaged.length(), CAPTURED_AT, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
        rows += arrayOf(phoneRealPlainTiff.path, "DCIM/Camera/", phoneRealPlainTiff.name, phoneRealPlainTiff.length(), CAPTURED_AT, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)

        val folders = container.phoneLibrary.folders()
        assertEquals(listOf(PhoneLibrary.Folder("DCIM/Camera/", 2)), folders)

        val inserted = container.phoneLibrary.refresh(setOf("DCIM/Camera/"))
        assertEquals(2, inserted)
        val phoneItems = container.database.mediaDao().all().associateBy { it.fileName }
        assertEquals(setOf("PHONE_OK.DNG.TIFF", "PHONE_NONE.dng.tiff"), phoneItems.keys)
        assertEquals(MediaKind.RAW, phoneItems.getValue("PHONE_OK.DNG.TIFF").kind)
        assertEquals(MediaKind.RAW, phoneItems.getValue("PHONE_NONE.dng.tiff").kind)

        val libDir = File(container.storage.root, "2026/2026-10-02").apply { mkdirs() }
        File(libDir, "ADOPT_OK.dng.tiff").writeBytes(validDngBytes)
        File(libDir, "ADOPT_PLAIN.dng.tiff").writeBytes(plainTiffBytes)
        File(libDir, "ADOPT_BAD.dng.tiff").writeBytes(tailTruncatedDngBytes)
        File(libDir, "ADOPT_TIFF.tiff").writeBytes(plainTiffBytes)

        val adopted = container.libraryIndex.adoptUnknownFiles()
        assertEquals(1, adopted)
        val allNames = container.database.mediaDao().all().map { it.fileName }.toSet()
        assertTrue("ADOPT_OK.dng.tiff" in allNames)
        assertFalse("ADOPT_PLAIN.dng.tiff" in allNames)
        assertFalse("ADOPT_BAD.dng.tiff" in allNames)
        assertFalse("ADOPT_TIFF.tiff" in allNames)
    }

    @Test
    fun dngValidationRejectsPlainTiffWrongVersionAndTruncatedPixelData() {
        assertTrue(Dng.isDng(validDngBytes))
        assertTrue(Dng.isDng(SyntheticDng.file(200, 400, 300, layout = SyntheticDng.Layout.ADOBE, storage = SyntheticDng.Storage.PACKED_12)))
        assertTrue(Dng.isDng(SyntheticDng.file(200, 400, 300, storage = SyntheticDng.Storage.LOSSLESS_JPEG)))
        assertTrue(Dng.isDng(SyntheticDng.linear(192, 380, 264, lossless = true)))
        assertTrue(Dng.isDng(SyntheticDng.linear(192, 380, 264, jpegXl = true)))
        assertFalse(Dng.isDng(plainTiffBytes))
        assertFalse(Dng.isDng(stripTruncatedDngBytes))
        assertFalse(Dng.isDng(tailTruncatedDngBytes))
        assertFalse(Dng.isDng(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun decodesReadsMetadataViewsEditsAndSharesDngTiffIdenticallyToDng() {
        val compareDir = File(context.cacheDir, "compare").apply { mkdirs() }
        val dir = File(container.storage.root, "2026/2026-10-03").apply { mkdirs() }
        val dngFile = File(compareDir, "SAME.dng").apply { writeBytes(validDngBytes) }
        val dngTiffFile = File(dir, "SAME.dng.tiff").apply { writeBytes(validDngBytes) }

        assertEquals(
            MediaMetadataReader.read(dngFile, MediaKind.RAW),
            MediaMetadataReader.read(dngTiffFile, MediaKind.RAW),
        )
        assertEquals(PhotoDetails.of(dngFile), PhotoDetails.of(dngTiffFile))
        assertEquals(PhotoDetails.all(dngFile), PhotoDetails.all(dngTiffFile))

        val decodedDng = RawRenderer.read(dngFile)
        val decodedDngTiff = RawRenderer.read(dngTiffFile)
        assertArrayEquals(decodedDng.sensor, decodedDngTiff.sensor)
        assertEquals("image/x-adobe-dng", shareMimeType(dngTiffFile, MediaKind.RAW))
        assertEquals("image/x-adobe-dng", shareMimeType(File("UPPER.DNG.TIFF"), MediaKind.RAW))
        assertEquals("image/x-canon-cr2", shareMimeType(File("A.CR2"), MediaKind.RAW))
        assertEquals("image/x-sony-arw", shareMimeType(File("A.ARW"), MediaKind.RAW))
        assertTrue(container.geotagger.writeSidecar(dngTiffFile, GeoPoint(37.5665, 126.9780)))
        assertTrue(File(dir, "SAME.xmp").exists())
        assertFalse(File(dir, "SAME.dng.xmp").exists())

        val id = runBlocking {
            val inserted = container.database.mediaDao().insert(
                MediaItemEntity(
                    path = dngTiffFile.path,
                    fileName = dngTiffFile.name,
                    kind = MediaKind.RAW,
                    sizeBytes = dngTiffFile.length(),
                    capturedAt = CAPTURED_AT,
                    importedAt = CAPTURED_AT,
                    origin = MediaOrigin.LIBRARY,
                ),
            )
            assertEquals(1, container.exifIndex.run())
            val developed = container.libraryEdits.exportRaw(container.database.mediaDao().byId(inserted)!!)
            assertEquals("SAME_developed.jpg", developed.name)
            assertTrue(developed.exists())
            inserted
        }

        // Open Viewer -> Editor -> Auto -> Done via real UI, verifying decode, rendering, save, and Viewer re-entry.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("SAME.dng.tiff").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("SAME.dng.tiff").performClick()

        val editLabel = context.getString(R.string.edit)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(editLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(editLabel).performClick()

        val autoLabel = context.getString(R.string.auto)
        val doneLabel = context.getString(R.string.done)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(autoLabel).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithText(doneLabel).assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText(autoLabel).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching { compose.onNodeWithText(doneLabel).assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText(doneLabel).performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            container.editSaver.saving.value.isEmpty() && runBlocking { container.database.mediaDao().byId(id)?.edit != null }
        }

        val shareLabel = context.getString(R.string.share)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(shareLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(shareLabel).performClick()
        val shareJpegLabel = context.getString(R.string.share_jpeg_1080)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(shareJpegLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(shareJpegLabel).performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            Shadows.shadowOf(compose.activity).peekNextStartedActivity() != null
        }
        val chooser = Shadows.shadowOf(compose.activity).nextStartedActivity
        val inner = chooser?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals("image/jpeg", inner?.type)
        assertTrue(File(context.cacheDir, "share/SAME.jpg").exists())
        assertFalse(File(context.cacheDir, "share/SAME.dng.jpg").exists())
    }

    @Test
    fun deduplicatesRepeatedSubIfdOffsetsAndRejectsDistinctDirectoryFloodsWithinBudget() = runBlocking {
        // Exact ~65 KB adversarial shape: valid DNGVersion + 4,096 pointers to one 4,096-entry non-raw child.
        val repeatedInvalid = createSubIfdProbeBytes(
            subIfdOffsetsCount = 4096,
            distinctChildren = 1,
            entriesPerChild = 4096,
            validRawFirstChild = false,
        )
        assertTrue(repeatedInvalid.size in 65_000..66_000)
        assertFalse(Dng.isDng(repeatedInvalid))
        val repeatedInvalidFile = File(context.cacheDir, "REPEATED_INVALID.dng.tiff").apply { writeBytes(repeatedInvalid) }
        assertFalse(Dng.isDng(repeatedInvalidFile))
        assertNull(MediaKind.fromFile(repeatedInvalidFile))

        // Valid raw child referenced 4,096 times is deduplicated once without exhausting heap or failing.
        val repeatedValid = createSubIfdProbeBytes(
            subIfdOffsetsCount = 4096,
            distinctChildren = 1,
            entriesPerChild = 4096,
            validRawFirstChild = true,
        )
        assertTrue(Dng.isDng(repeatedValid))
        val repeatedValidFile = File(context.cacheDir, "REPEATED_VALID.dng.tiff").apply { writeBytes(repeatedValid) }
        assertTrue(Dng.isDng(repeatedValidFile))
        assertEquals(MediaKind.RAW, MediaKind.fromFile(repeatedValidFile))

        // Many distinct small directories (> 64) are rejected before exceeding the directory cap.
        val manyDistinctDirs = createSubIfdProbeBytes(
            subIfdOffsetsCount = 80,
            distinctChildren = 80,
            entriesPerChild = 12,
            validRawFirstChild = true,
        )
        assertFalse(Dng.isDng(manyDistinctDirs))
        val manyDistinctDirsFile = File(context.cacheDir, "MANY_DIRS.dng.tiff").apply { writeBytes(manyDistinctDirs) }
        assertFalse(Dng.isDng(manyDistinctDirsFile))

        // Several distinct large directories exceeding the aggregate entry budget are rejected even if child #0 is valid raw.
        val manyDistinctEntries = createSubIfdProbeBytes(
            subIfdOffsetsCount = 3,
            distinctChildren = 3,
            entriesPerChild = 4096,
            validRawFirstChild = true,
        )
        assertFalse(Dng.isDng(manyDistinctEntries))
        val manyDistinctEntriesFile = File(context.cacheDir, "MANY_ENTRIES.dng.tiff").apply { writeBytes(manyDistinctEntries) }
        assertFalse(Dng.isDng(manyDistinctEntriesFile))

        // Ensure ingestion rejects the pathological repeated-offset and distinct-directory files as .dng.tiff.
        val dir = File(container.storage.root, "2026/2026-10-04").apply { mkdirs() }
        File(dir, "FLOOD_DIRS.dng.tiff").writeBytes(manyDistinctDirs)
        File(dir, "FLOOD_REPEAT.dng.tiff").writeBytes(repeatedInvalid)
        assertEquals(0, container.libraryIndex.adoptUnknownFiles())
    }

    @Test
    fun acceptsLosslessJpegOverstatedStripLengthAcrossExtensionsWhileRejectingTruncatedStreams() = runBlocking {
        val losslessValid = SyntheticDng.file(200, 400, 300, storage = SyntheticDng.Storage.LOSSLESS_JPEG)
        val expectedSensor = Dng.read(losslessValid).sensor

        // Overstated lossless JPEG StripByteCounts (Int.MAX_VALUE) clips to EOF identically for .dng and .dng.tiff.
        val losslessOverstated = losslessValid.copyOf().also { setFirstTagInt(it, 0x0117, Int.MAX_VALUE) }
        assertTrue(Dng.isDng(losslessOverstated))

        val dir = File(container.storage.root, "2026/2026-10-05").apply { mkdirs() }
        val dngFile = File(dir, "OVERSTATED.dng").apply { writeBytes(losslessOverstated) }
        val dngTiffFile = File(dir, "OVERSTATED.dng.tiff").apply { writeBytes(losslessOverstated) }
        assertTrue(Dng.isDng(dngFile))
        assertTrue(Dng.isDng(dngTiffFile))
        assertEquals(MediaKind.RAW, MediaKind.fromFile(dngFile))
        assertEquals(MediaKind.RAW, MediaKind.fromFile(dngTiffFile))
        assertArrayEquals(expectedSensor, RawRenderer.read(dngFile).sensor)
        assertArrayEquals(expectedSensor, RawRenderer.read(dngTiffFile).sensor)

        // JPEG XL keeps strict declared-range enforcement (overstated Int.MAX_VALUE and truncated both rejected).
        val jxlValid = SyntheticDng.linear(192, 380, 264, jpegXl = true)
        assertTrue(Dng.isDng(jxlValid))
        val jxlOverstated = jxlValid.copyOf().also { setFirstTagInt(it, 0x0117, Int.MAX_VALUE) }
        val jxlTruncated = jxlValid.copyOf(jxlValid.size - 32)
        assertFalse(Dng.isDng(jxlOverstated))
        assertFalse(Dng.isDng(jxlTruncated))

        // Uncompressed WORDS & PACKED_12 with one missing row byte must still be rejected even with Int.MAX_VALUE.
        for (storage in listOf(SyntheticDng.Storage.WORDS, SyntheticDng.Storage.PACKED_12)) {
            val raw = SyntheticDng.file(200, 400, 300, storage = storage)
            val stripOffset = firstTagInt(raw, 0x0111)
            val stripLength = firstTagInt(raw, 0x0117)
            val cutShort = (raw + raw.copyOfRange(stripOffset, stripOffset + stripLength - 1)).also {
                setFirstTagInt(it, 0x0111, raw.size)
                setFirstTagInt(it, 0x0117, Int.MAX_VALUE)
            }
            assertFalse(Dng.isDng(cutShort))
        }

        // Lossless JPEG streams truncated before offset, < 4 bytes, or mid-JPEG marker header are rejected.
        val jpegOffset = firstTagInt(losslessValid, 0x0111)
        val jpegTruncBeforeData = losslessValid.copyOf(jpegOffset)
        val jpegTruncTiny = losslessValid.copyOf(jpegOffset + 2)
        val jpegTruncMidHeader = losslessValid.copyOf(jpegOffset + 12)
        assertFalse(Dng.isDng(jpegTruncBeforeData))
        assertFalse(Dng.isDng(jpegTruncTiny))
        assertFalse(Dng.isDng(jpegTruncMidHeader))

        // Verify PhoneLibrary & SyncEngine & LibraryIndex accept overstated lossless JPEG .dng.tiff and reject truncated ones.
        val cameraDir = File(Environment.getExternalStorageDirectory(), "DCIM/Camera").apply { mkdirs() }
        val phoneOverstated = File(cameraDir, "PHONE_OVER.dng.tiff").apply { writeBytes(losslessOverstated) }
        val phoneTruncJxl = File(cameraDir, "PHONE_TRUNC_JXL.dng.tiff").apply { writeBytes(jxlTruncated) }
        val phoneTruncJpeg = File(cameraDir, "PHONE_TRUNC_LJ.dng.tiff").apply { writeBytes(jpegTruncMidHeader) }
        rows += arrayOf(phoneOverstated.path, "DCIM/Camera/", phoneOverstated.name, phoneOverstated.length(), CAPTURED_AT, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
        rows += arrayOf(phoneTruncJxl.path, "DCIM/Camera/", phoneTruncJxl.name, phoneTruncJxl.length(), CAPTURED_AT, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
        rows += arrayOf(phoneTruncJpeg.path, "DCIM/Camera/", phoneTruncJpeg.name, phoneTruncJpeg.length(), CAPTURED_AT, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)

        assertEquals(1, container.phoneLibrary.refresh(setOf("DCIM/Camera/")))
        val importedNames = container.database.mediaDao().all().map { it.fileName }.toSet()
        assertEquals(setOf("PHONE_OVER.dng.tiff"), importedNames)

        val remoteOver = RemoteFile(20, "CAM_OVER.dng.tiff", losslessOverstated.size.toLong(), CAPTURED_AT, MediaKind.RAW, "DCIM")
        val remoteTruncJpeg = RemoteFile(21, "CAM_TRUNC_LJ.dng.tiff", jpegTruncMidHeader.size.toLong(), CAPTURED_AT, MediaKind.RAW, "DCIM")
        val syncSource = object : CameraSource {
            private val payloads = mapOf(20 to losslessOverstated, 21 to jpegTruncMidHeader)
            override fun open() = CameraInfo("Sony", "TestCam", "SN2")
            override fun listFiles(request: ListingRequest, onFound: (Int) -> Unit) =
                Listing(listOf(remoteOver, remoteTruncJpeg), emptyMap())

            override fun download(file: RemoteFile, destination: File): Boolean {
                destination.writeBytes(payloads.getValue(file.handle))
                return true
            }

            override fun close() {}
        }
        container.syncEngine.run(syncSource)
        val syncFinished = container.syncEngine.state.value as SyncState.Finished
        assertEquals(1, syncFinished.imported)
        assertEquals(listOf("CAM_TRUNC_LJ.dng.tiff"), syncFinished.failed)

        File(dir, "TRUNC_JXL.dng.tiff").writeBytes(jxlTruncated)
        File(dir, "TRUNC_LJ.dng.tiff").writeBytes(jpegTruncMidHeader)
        assertEquals(2, container.libraryIndex.adoptUnknownFiles())
        val finalNames = container.database.mediaDao().all().map { it.fileName }.toSet()
        assertTrue("OVERSTATED.dng" in finalNames)
        assertTrue("OVERSTATED.dng.tiff" in finalNames)
        assertFalse("TRUNC_JXL.dng.tiff" in finalNames)
        assertFalse("TRUNC_LJ.dng.tiff" in finalNames)
    }

    private fun firstTagInt(file: ByteArray, tag: Int): Int {
        val data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val count = data.getShort(8).toInt() and 0xFFFF
        val at = (0 until count).map { 10 + 12 * it }.first { (data.getShort(it).toInt() and 0xFFFF) == tag }
        return data.getInt(at + 8)
    }

    private fun setFirstTagInt(file: ByteArray, tag: Int, value: Int) {
        val data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val count = data.getShort(8).toInt() and 0xFFFF
        val at = (0 until count).map { 10 + 12 * it }.first { (data.getShort(it).toInt() and 0xFFFF) == tag }
        data.putInt(at + 8, value)
    }

    private fun createSubIfdProbeBytes(
        subIfdOffsetsCount: Int,
        distinctChildren: Int,
        entriesPerChild: Int,
        validRawFirstChild: Boolean,
    ): ByteArray {
        val mainEntryCount = 2 // TAG_SUB_IFDS (0x014A) + TAG_DNG_VERSION (0xC612)
        val pointersAt = 8 + 2 + 12 * mainEntryCount
        val pointerBytes = subIfdOffsetsCount * 4
        val rawPixelData = if (validRawFirstChild) ByteArray(16 * 16 * 2) { 0x40 } else ByteArray(0)
        val firstChildAt = pointersAt + pointerBytes
        val childSize = 2 + 12 * entriesPerChild
        val rawPixelsAt = firstChildAt + distinctChildren * childSize
        val totalSize = rawPixelsAt + rawPixelData.size

        val bytes = ByteArray(totalSize)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 'I'.code.toByte()
        bytes[1] = 'I'.code.toByte()
        buf.putShort(2, 42)
        buf.putInt(4, 8)

        fun putEntry(pos: Int, tag: Int, type: Int, count: Int, value: Int) {
            buf.putShort(pos, tag.toShort())
            buf.putShort(pos + 2, type.toShort())
            buf.putInt(pos + 4, count)
            buf.putInt(pos + 8, value)
        }

        buf.putShort(8, mainEntryCount.toShort())
        putEntry(10, 0x014A, 4, subIfdOffsetsCount, pointersAt)
        // TAG_DNG_VERSION [1, 4, 0, 0] inline in little-endian bytes: 0x00_00_04_01
        putEntry(22, 0xC612, 1, 4, 0x00000401)

        for (i in 0 until subIfdOffsetsCount) {
            val childIndex = i % distinctChildren
            buf.putInt(pointersAt + 4 * i, firstChildAt + childIndex * childSize)
        }

        for (childIndex in 0 until distinctChildren) {
            val childOffset = firstChildAt + childIndex * childSize
            buf.putShort(childOffset, entriesPerChild.toShort())
            val isValidRaw = validRawFirstChild && childIndex == 0
            val layoutTags = if (isValidRaw) {
                listOf(
                    intArrayOf(0x00FE, 4, 1, 0),
                    intArrayOf(0x0100, 3, 1, 16),
                    intArrayOf(0x0101, 3, 1, 16),
                    intArrayOf(0x0102, 3, 1, 16),
                    intArrayOf(0x0103, 3, 1, 1),
                    intArrayOf(0x0106, 3, 1, 32803),
                    intArrayOf(0x0111, 4, 1, rawPixelsAt),
                    intArrayOf(0x0115, 3, 1, 1),
                    intArrayOf(0x0116, 3, 1, 16),
                    intArrayOf(0x0117, 4, 1, rawPixelData.size),
                )
            } else emptyList()
            for (e in 0 until entriesPerChild) {
                val entryPos = childOffset + 2 + 12 * e
                if (e < layoutTags.size) {
                    val t = layoutTags[e]
                    putEntry(entryPos, t[0], t[1], t[2], t[3])
                } else {
                    val padTag = 0xE000 + (e and 0x0FFF)
                    putEntry(entryPos, padTag, 4, 1, 0)
                }
            }
        }
        if (rawPixelData.isNotEmpty()) {
            System.arraycopy(rawPixelData, 0, bytes, rawPixelsAt, rawPixelData.size)
        }
        return bytes
    }

    private fun createPlainTiffBytes(): ByteArray {
        val pixels = ByteArray(32 * 24) { (it and 0xFF).toByte() }
        val tiff = SyntheticArw.Tiff()
        val dataAt = tiff.reserve()
        tiff.directory(
            8,
            SyntheticArw.Entry.longs(0x00FE, 0),
            SyntheticArw.Entry.shorts(0x0100, 32),
            SyntheticArw.Entry.shorts(0x0101, 24),
            SyntheticArw.Entry.shorts(0x0102, 8),
            SyntheticArw.Entry.shorts(0x0103, 1),
            SyntheticArw.Entry.shorts(0x0106, 1),
            SyntheticArw.Entry.longs(0x0111, dataAt),
            SyntheticArw.Entry.shorts(0x0115, 1),
            SyntheticArw.Entry.shorts(0x0116, 24),
            SyntheticArw.Entry.longs(0x0117, pixels.size),
        )
        tiff.write(dataAt, pixels)
        return tiff.bytes()
    }
}
