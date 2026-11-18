package com.byeonggon.latent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Environment
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import coil3.SingletonImageLoader
import com.byeonggon.latent.data.Thumbnails
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.raw.RawCache
import com.byeonggon.latent.raw.SyntheticDng
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w600dp-h900dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailRefreshReproductionTest {
    private companion object {
        const val CAPTURED_AT = 1_790_812_800_000L
    }

    @get:Rule(order = 0)
    val environment = object : TestWatcher() {
        @OptIn(coil3.annotation.DelicateCoilApi::class)
        override fun starting(description: Description) {
            SingletonImageLoader.reset()
            ShadowEnvironment.addExternalDir("shared")
            ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
            forgetTheApplicationOfEarlierTests()
            grantLibraryAccess()
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = compose.activity
    private val container get() = compose.activity.application.appContainer
    private val dao get() = container.database.mediaDao()

    @After
    fun cleanUp() {
        container.storage.root.deleteRecursively()
        File(context.cacheDir, "thumbnails").deleteRecursively()
    }

    @Test
    fun rawEditContinuousEditsAndResetRefreshThumbnailWithoutRestartAndPreserveOtherCaches() {
        val dir = File(container.storage.root, "2026/2026-10-05").apply { mkdirs() }
        val rawFile = File(dir, "RAW_ONLY.dng").apply {
            writeBytes(SyntheticDng.file(red = 400, green = 250, blue = 200))
        }
        val keepFile = File(dir, "KEEP_ME.jpg").apply {
            writeBytes(jpegBytes(120, 80, Color.rgb(64, 64, 64)))
        }
        val (keepId, keepCached, keepBytesBefore, rawId, raw0) = runBlocking {
            val kId = dao.insert(
                MediaItemEntity(
                    path = keepFile.path,
                    fileName = keepFile.name,
                    kind = MediaKind.PHOTO,
                    sizeBytes = keepFile.length(),
                    capturedAt = CAPTURED_AT - 10_000,
                    importedAt = CAPTURED_AT - 10_000,
                    origin = MediaOrigin.LIBRARY,
                ),
            )
            val kCached = container.thumbnails.of(keepFile, keepFile.length())
            assertNotNull(kCached)
            val kBytes = kCached!!.readBytes()
            val rId = dao.insert(
                MediaItemEntity(
                    path = rawFile.path,
                    fileName = rawFile.name,
                    kind = MediaKind.RAW,
                    sizeBytes = rawFile.length(),
                    capturedAt = CAPTURED_AT,
                    importedAt = CAPTURED_AT,
                    origin = MediaOrigin.LIBRARY,
                ),
            )
            listOf(kId, kCached, kBytes, rId, dao.byId(rId)!!)
        }.let { Five(it[0] as Long, it[1] as File, it[2] as ByteArray, it[3] as Long, it[4] as MediaItemEntity) }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("RAW_ONLY.dng").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            container.thumbnails.fileFor(raw0.path, raw0.sizeBytes).exists()
        }
        val uneditedThumb = container.thumbnails.fileFor(raw0.path, raw0.sizeBytes)
        val uneditedLuma = meanLuminance(uneditedThumb)

        // First edit (+1.8 exposure) via real UI -> Auto -> Done -> return to Library without restart.
        compose.onNodeWithContentDescription("RAW_ONLY.dng").performClick()
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

        val closeLabel = context.getString(R.string.close)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(closeLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(closeLabel).performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("RAW_ONLY.dng").fetchSemanticsNodes().isNotEmpty()
        }
        waitForSaveFinished()
        val rawAuto = runBlocking { dao.byId(rawId)!! }
        assertNotNull(rawAuto.edit)

        // Thumbnail refreshes in-place in active UI/cache WITHOUT restart.
        val autoFile = container.thumbnails.fileFor(rawAuto.path, rawAuto.sizeBytes, rawAuto.edit)
        compose.waitUntil(timeoutMillis = 10_000) { autoFile.exists() }
        assertTrue(autoFile != uneditedThumb)
        assertTrue("Keep unrelated cache intact", keepCached.exists() && keepCached.readBytes().contentEquals(keepBytesBefore))

        // Continuous edit 1 (+1.8 exposure) and continuous edit 2 (-1.8 exposure) refresh without restart.
        container.editSaver.save(rawAuto, PhotoEdit(Adjustments(exposure = 1.8f)))
        waitForSaveFinished()
        val raw1 = runBlocking { dao.byId(rawId)!! }
        val edit1File = container.thumbnails.fileFor(raw1.path, raw1.sizeBytes, raw1.edit)
        compose.waitUntil(timeoutMillis = 10_000) { edit1File.exists() }
        val edit1Luma = meanLuminance(edit1File)
        assertTrue("Expected brighter +1.8 edit than unedited ($edit1Luma > $uneditedLuma)", edit1Luma > uneditedLuma + 5.0)

        val edit2 = PhotoEdit(Adjustments(exposure = -1.8f))
        container.editSaver.save(raw1, edit2)
        waitForSaveFinished()
        val raw2 = runBlocking { dao.byId(rawId)!! }
        val edit2File = container.thumbnails.fileFor(raw2.path, raw2.sizeBytes, raw2.edit)
        compose.waitUntil(timeoutMillis = 10_000) { edit2File.exists() }
        val edit2Luma = meanLuminance(edit2File)
        assertTrue("Expected darker second edit ($edit2Luma < $edit1Luma)", edit2Luma + 5.0 < edit1Luma)

        // Simulated restart keeps edited RAW thumbnail in keepOnly and regenerates identically if purged.
        val restartedThumbnails = Thumbnails(context, RawCache())
        runBlocking {
            val all = dao.all()
            restartedThumbnails.keepOnly(all.map { restartedThumbnails.fileFor(it.path, it.sizeBytes, it.edit) })
            assertTrue("Restart keepOnly must keep edited RAW thumbnail", edit2File.exists())
            assertTrue(edit2File.delete())
            val regenerated = restartedThumbnails.of(rawFile, raw2.sizeBytes, raw2.edit, applyEdit = raw2.editsAsSettings, raw = true)
            assertNotNull(regenerated)
            assertEquals(edit2Luma, meanLuminance(regenerated!!), 1.0)
        }

        // Re-open Editor via UI -> Reset -> Done -> Close clears DB edit, Edited badge, and reverts active thumbnail.
        compose.onNodeWithContentDescription("RAW_ONLY.dng").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(editLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(editLabel).performClick()
        val moreLabel = context.getString(R.string.more)
        val resetLabel = context.getString(R.string.reset)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(moreLabel).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithContentDescription(moreLabel).assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithContentDescription(moreLabel).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(resetLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(resetLabel).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching { compose.onNodeWithText(doneLabel).assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText(doneLabel).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(closeLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(closeLabel).performClick()

        waitForSaveFinished()
        val rawReset = runBlocking { dao.byId(rawId)!! }
        assertNull("RAW reset should clear edit column in DB", rawReset.edit)
        assertFalse(runBlocking { container.libraryEdits.isEdited(rawReset) })
        val editedLabel = context.getString(R.string.edited)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(editedLabel).fetchSemanticsNodes().isEmpty()
        }
        assertFalse("Database keep check", runBlocking { dao.byId(keepId)?.edit != null })
    }

    @Test
    fun libraryJpegContinuousEditsAndResetRefreshThumbnails() {
        val dir = File(container.storage.root, "2026/2026-10-06").apply { mkdirs() }
        val jpgFile = File(dir, "LIB_PHOTO.jpg").apply {
            writeBytes(jpegBytes(120, 80, Color.rgb(90, 90, 90)))
        }
        val (id, item0) = runBlocking {
            val inserted = dao.insert(
                MediaItemEntity(
                    path = jpgFile.path,
                    fileName = jpgFile.name,
                    kind = MediaKind.PHOTO,
                    sizeBytes = jpgFile.length(),
                    capturedAt = CAPTURED_AT,
                    importedAt = CAPTURED_AT,
                    origin = MediaOrigin.LIBRARY,
                ),
            )
            inserted to dao.byId(inserted)!!
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("LIB_PHOTO.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            container.thumbnails.fileFor(item0.path, item0.sizeBytes, item0.edit).exists()
        }
        val luma0 = meanLuminance(container.thumbnails.fileFor(item0.path, item0.sizeBytes, item0.edit))

        // Continuous edit 1: brighter.
        container.editSaver.save(item0, PhotoEdit(Adjustments(exposure = 1.5f)))
        waitForSaveFinished()
        val item1 = runBlocking { dao.byId(id)!! }
        assertNotNull(item1.edit)
        val thumb1 = container.thumbnails.fileFor(item1.path, item1.sizeBytes, item1.edit)
        compose.waitUntil(timeoutMillis = 10_000) { thumb1.exists() }
        val luma1 = meanLuminance(thumb1)
        assertTrue("Expected brighter JPEG thumbnail ($luma1 > $luma0)", luma1 > luma0 + 5.0)

        // Continuous edit 2: darker.
        container.editSaver.save(item1, PhotoEdit(Adjustments(exposure = -1.5f)))
        waitForSaveFinished()
        val item2 = runBlocking { dao.byId(id)!! }
        val thumb2 = container.thumbnails.fileFor(item2.path, item2.sizeBytes, item2.edit)
        compose.waitUntil(timeoutMillis = 10_000) { thumb2.exists() }
        val luma2 = meanLuminance(thumb2)
        assertTrue("Expected darker JPEG thumbnail ($luma2 < $luma1)", luma2 + 5.0 < luma1)

        // Reset restores unedited JPEG and clears edit.
        container.editSaver.save(item2, PhotoEdit())
        waitForSaveFinished()
        val itemReset = runBlocking { dao.byId(id)!! }
        assertNull(itemReset.edit)
        val thumbReset = container.thumbnails.fileFor(itemReset.path, itemReset.sizeBytes, itemReset.edit)
        compose.waitUntil(timeoutMillis = 10_000) { thumbReset.exists() }
        val lumaReset = meanLuminance(thumbReset)
        assertEquals(luma0, lumaReset, 3.0)
    }

    @Test
    fun stackedRawPlusJpegPairUpdatesLibraryThumbnailWhenRawPartnerIsEdited() {
        val dir = File(container.storage.root, "2026/2026-10-07").apply { mkdirs() }
        val jpgFile = File(dir, "PAIR_001.jpg").apply {
            writeBytes(jpegBytes(120, 80, Color.rgb(30, 40, 120)))
        }
        val dngFile = File(dir, "PAIR_001.dng").apply {
            writeBytes(SyntheticDng.file(red = 900, green = 250, blue = 120))
        }
        val (rawId, raw0) = runBlocking {
            dao.insert(
                MediaItemEntity(
                    path = jpgFile.path,
                    fileName = jpgFile.name,
                    kind = MediaKind.PHOTO,
                    sizeBytes = jpgFile.length(),
                    capturedAt = CAPTURED_AT,
                    importedAt = CAPTURED_AT,
                    origin = MediaOrigin.LIBRARY,
                ),
            )
            val rId = dao.insert(
                MediaItemEntity(
                    path = dngFile.path,
                    fileName = dngFile.name,
                    kind = MediaKind.RAW,
                    sizeBytes = dngFile.length(),
                    capturedAt = CAPTURED_AT,
                    importedAt = CAPTURED_AT,
                    origin = MediaOrigin.LIBRARY,
                ),
            )
            rId to dao.byId(rId)!!
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("PAIR_001.jpg").fetchSemanticsNodes().isNotEmpty()
        }

        // Edit only the stacked RAW partner.
        container.editSaver.save(raw0, PhotoEdit(Adjustments(exposure = 1.4f)))
        waitForSaveFinished()
        val raw1 = runBlocking { dao.byId(rawId)!! }
        assertNotNull(raw1.edit)

        // Stacked JPEG cell should stay the stacked item, display Edited badge, and load the edited RAW thumbnail!
        val editedLabel = context.getString(R.string.edited)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(editedLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            container.thumbnails.fileFor(raw1.path, raw1.sizeBytes, raw1.edit).exists()
        }

        // Resetting the RAW partner restores the pair back to the unedited JPEG thumbnail cleanly.
        container.editSaver.save(raw1, PhotoEdit())
        waitForSaveFinished()
        assertNull(runBlocking { dao.byId(rawId)!!.edit })
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(editedLabel).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(container.thumbnails.fileFor(jpgFile.path, jpgFile.length(), null).exists())
    }

    @Test
    fun failedSaveReportsFailurePreservesEditAndDoesNotPoisonEditedCache() {
        val dir = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
        val missingRaw = File(dir, "GONE_RAW.dng").apply { writeBytes(SyntheticDng.file(200, 400, 300)) }
        val phoneDir = File(Environment.getExternalStorageDirectory(), "DCIM/Camera").apply { mkdirs() }
        val missingPhone = File(phoneDir, "GONE_PHONE.jpg").apply { writeBytes(jpegBytes(120, 80, Color.GRAY)) }
        val (rawId, rawItem, phoneId, phoneItem) = runBlocking {
            val rId = dao.insert(
                MediaItemEntity(
                    path = missingRaw.path,
                    fileName = missingRaw.name,
                    kind = MediaKind.RAW,
                    sizeBytes = missingRaw.length(),
                    capturedAt = CAPTURED_AT,
                    importedAt = CAPTURED_AT,
                    origin = MediaOrigin.LIBRARY,
                ),
            )
            val pId = dao.insert(
                MediaItemEntity(
                    path = missingPhone.path,
                    fileName = missingPhone.name,
                    kind = MediaKind.PHOTO,
                    sizeBytes = missingPhone.length(),
                    capturedAt = CAPTURED_AT - 1_000,
                    importedAt = CAPTURED_AT - 1_000,
                    origin = MediaOrigin.PHONE,
                ),
            )
            listOf(rId, dao.byId(rId)!!, pId, dao.byId(pId)!!)
        }.let { Four(it[0] as Long, it[1] as MediaItemEntity, it[2] as Long, it[3] as MediaItemEntity) }

        assertTrue(missingRaw.delete())
        assertTrue(missingPhone.delete())

        val edit = PhotoEdit(Adjustments(exposure = 1.0f))
        container.editSaver.save(rawItem, edit)
        container.editSaver.save(phoneItem, edit)
        waitForSaveFinished()

        assertTrue("Missing RAW save must fail", rawId in container.editSaver.failed.value)
        assertTrue("Missing phone photo save must fail", phoneId in container.editSaver.failed.value)
        runBlocking {
            assertNull(dao.byId(rawId)?.edit)
            assertNull(dao.byId(phoneId)?.edit)
        }

        // Broken primary scan JPEG with valid white MPF preview: unedited decode succeeds via MPF preview,
        // while edited render fails. Edited thumbnail generation must NOT cache the unedited MPF preview!
        val poisonFile = File(dir, "MPF_POISON.jpg").apply {
            val preview = jpegBytes(64, 48, Color.WHITE)
            writeBytes(mpfWithBrokenPrimary(preview))
        }
        val editJson = PhotoEdit(Adjustments(exposure = -2.0f)).toJson()
        runBlocking {
            val uneditedFromMpf = container.thumbnails.of(poisonFile, poisonFile.length())
            assertNotNull(uneditedFromMpf)
            val editedOnPoison = container.thumbnails.of(poisonFile, poisonFile.length(), editJson)
            assertNull("Failed edited render must not return unedited MPF fallback", editedOnPoison)
            assertFalse(
                "Edited cache file must not be poisoned by unedited fallback",
                container.thumbnails.fileFor(poisonFile.path, poisonFile.length(), editJson).exists(),
            )
        }
    }

    private fun waitForSaveFinished() {
        compose.waitUntil(timeoutMillis = 10_000) {
            container.editSaver.saving.value.isEmpty()
        }
    }

    private fun meanLuminance(file: File): Double {
        val bitmap = BitmapFactory.decodeFile(file.path)
        assertNotNull("Expected decodable bitmap for ${file.name}", bitmap)
        val pixels = IntArray(bitmap!!.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0.0
        for (pixel in pixels) {
            sum += 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
        }
        bitmap.recycle()
        return sum / pixels.size
    }

    private fun jpegBytes(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun mpfWithBrokenPrimary(preview: ByteArray): ByteArray {
        val tiffBytes = 8 + 2 + 12 + 4 + 32
        val segmentLength = 2 + 4 + tiffBytes
        val tiffStart = 2 + 2 + 2 + 4
        val brokenTail = ByteArray(16) { 0x11 }
        val previewStart = 2 + 2 + segmentLength + brokenTail.size
        val tiff = ByteBuffer.allocate(tiffBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("II".toByteArray()); putShort(42); putInt(8)
            putShort(1)
            putShort(0xB002.toShort()); putShort(7); putInt(32); putInt(8 + 2 + 12 + 4)
            putInt(0)
            putInt(0x20030000); putInt(previewStart); putInt(0); putInt(0)
            putInt(0x010001); putInt(preview.size); putInt(previewStart - tiffStart); putInt(0)
        }.array()
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE2.toByte()))
            write(byteArrayOf((segmentLength shr 8).toByte(), segmentLength.toByte()))
            write("MPF\u0000".toByteArray())
            write(tiff)
            write(brokenTail)
            write(preview)
        }.toByteArray()
    }

    private data class Four<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
    private data class Five<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}
