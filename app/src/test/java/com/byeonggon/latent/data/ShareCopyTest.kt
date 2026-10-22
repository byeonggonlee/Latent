package com.byeonggon.latent.data

import com.byeonggon.latent.grantLibraryAccess
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.raw.RawCache
import com.byeonggon.latent.raw.RawRenderer
import com.byeonggon.latent.raw.SyntheticArw
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareCopyTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private lateinit var photo: MediaItemEntity
    private lateinit var raw: MediaItemEntity

    @Before
    fun writeLibrary() {
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
        val photoFile = File(folder, "DSC00001.JPG")
        Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
            .let { bitmap -> photoFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        ExifInterface(photoFile).apply {
            setAttribute(ExifInterface.TAG_MODEL, "ZV-E10")
            saveAttributes()
        }
        val rawFile = File(folder, "DSC00002.ARW").apply { writeBytes(SyntheticArw.file(red = 600, green = 900, blue = 700, orientation = 6)) }
        photo = MediaItemEntity(path = photoFile.path, fileName = photoFile.name, kind = MediaKind.PHOTO, sizeBytes = photoFile.length(), capturedAt = 1, importedAt = 1, latitude = 37.5, longitude = 127.0)
        raw = photo.copy(path = rawFile.path, fileName = rawFile.name, kind = MediaKind.RAW, sizeBytes = rawFile.length())
    }

    @After
    fun forgetTheSettings() = runBlocking { container.settings.update { AppSettings() } }

    private fun sizeOf(file: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeFile(file.path, it) }
        .let { it.outWidth to it.outHeight }

    @Test
    fun makesACopyOfAJpegWhenAnotherAppIsToEditIt() {
        val copy = runBlocking { container.libraryEdits.shareCopy(photo, null, forceCopy = true) }

        assertNotEquals(File(photo.path).path, copy.path)
        org.junit.Assert.assertArrayEquals(File(photo.path).readBytes(), copy.readBytes())
    }

    @Test
    fun sharesAJpegAsItIsAtFullSize() {
        assertSame(File(photo.path).path, runBlocking { container.libraryEdits.shareCopy(photo, null) }.path)
    }

    @Test
    fun sharesASmallerCopyOfAJpegWithWhatTheCameraRecorded() {
        val copy = runBlocking { container.libraryEdits.shareCopy(photo, 1080) }

        assertNotEquals(photo.path, copy.path)
        assertEquals(1080 to 720, sizeOf(copy))
        val exif = ExifInterface(copy)
        assertEquals("ZV-E10", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals(37.5, exif.latLong!![0], 1e-4)
    }

    @Test
    fun sharesARawFileDevelopedUpright() {
        val copy = runBlocking { container.libraryEdits.shareCopy(raw, 2048) }

        // Taken on its side: half the sensor's 56 by 8 pixels, stood upright.
        assertEquals(4 to 28, sizeOf(copy))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifInterface(copy).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("DSC00002.jpg", copy.name)
    }

    @Test(timeout = 30_000)
    fun developsAtFullSizeOnlyOnceTheCachesDecodeHasEnded() {
        // The viewer's decode of the same raw file, held up as a slow file would be.
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = RawCache { file, checkCancelled ->
            started.countDown()
            release.await(20, TimeUnit.SECONDS)
            RawRenderer.read(file, checkCancelled)
        }
        val database = container.database
        val edits = LibraryEdits(
            container.storage, database.mediaDao(), database.filterDao(), container.geotagger, cache, database.lutDao(),
            database.editVersionDao(),
        )
        val viewer = thread { cache.read(File(raw.path)) }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        var copy: File? = null
        val sharing = thread { copy = runBlocking { edits.shareCopy(raw, null) } }
        sharing.join(1_000)
        assertTrue("the full-size development ran beside the cache's decode", sharing.isAlive)

        release.countDown()
        viewer.join()
        sharing.join()
        assertTrue(copy!!.length() > 0)
    }

    @Test
    fun keepsTheCopiesOfOneShareTogether() {
        val first = runBlocking { container.libraryEdits.shareCopy(raw, 2048, alongsideOthers = true) }
        val second = runBlocking { container.libraryEdits.shareCopy(photo, 1080, alongsideOthers = true) }

        assertTrue(first.exists() && second.exists())
        assertNotEquals(first.path, second.path)
        // The next share on its own clears them away.
        runBlocking { container.libraryEdits.shareCopy(photo, 1080) }
        assertFalse(first.exists())
    }

    @Test
    fun leavesThePlaceOutOfWhatIsSharedWhenTheSettingsSayAndNeverTouchesTheOriginal() {
        ExifInterface(File(photo.path)).apply { setLatLong(37.5, 127.0); saveAttributes() }
        val original = File(photo.path).readBytes()
        runBlocking { container.settings.update { it.copy(removeLocationWhenSharing = true) } }

        // As it is would hand over the file with its place in it, so a copy is made.
        val asIs = runBlocking { container.libraryEdits.shareCopy(photo, null) }
        assertNotEquals(photo.path, asIs.path)
        assertEquals(null, ExifInterface(asIs).latLong)
        assertEquals("ZV-E10", ExifInterface(asIs).getAttribute(ExifInterface.TAG_MODEL))

        val smaller = runBlocking { container.libraryEdits.shareCopy(photo, 1080) }
        assertEquals(null, ExifInterface(smaller).latLong)
        assertEquals("ZV-E10", ExifInterface(smaller).getAttribute(ExifInterface.TAG_MODEL))

        val developed = runBlocking { container.libraryEdits.shareCopy(raw, 2048) }
        assertEquals(null, ExifInterface(developed).latLong)

        org.junit.Assert.assertArrayEquals(original, File(photo.path).readBytes())
        assertEquals(37.5, ExifInterface(File(photo.path)).latLong!![0], 1e-4)
    }

    @Test
    fun leavesThePlaceOutOfAHeifPictureByMakingAJpegOfIt() {
        // A JPEG under a HEIF name, as the JVM cannot encode HEIF: what counts is that no Exif is written into a HEIF file,
        // which a phone's ExifInterface refuses, so that the share failed every time.
        val file = File(File(photo.path).parentFile, "DSC00004.HIF")
        File(photo.path).copyTo(file)
        ExifInterface(file).apply { setLatLong(37.5, 127.0); saveAttributes() }
        val heif = photo.copy(path = file.path, fileName = file.name, sizeBytes = file.length())
        val original = file.readBytes()

        assertEquals(file.path, runBlocking { container.libraryEdits.shareCopy(heif, null) }.path)

        runBlocking { container.settings.update { it.copy(removeLocationWhenSharing = true) } }
        val shared = runBlocking { container.libraryEdits.shareCopy(heif, null) }

        assertEquals("DSC00004.jpg", shared.name)
        assertEquals(null, ExifInterface(shared).latLong)
        assertEquals("ZV-E10", ExifInterface(shared).getAttribute(ExifInterface.TAG_MODEL))
        assertEquals(sizeOf(file), sizeOf(shared))
        org.junit.Assert.assertArrayEquals(original, file.readBytes())
        // For another app to edit, the file goes as it is, with its place.
        val forEditing = runBlocking { container.libraryEdits.shareCopy(heif, null, forceCopy = true) }
        assertEquals("DSC00004.HIF", forEditing.name)
        assertEquals(37.5, ExifInterface(forEditing).latLong!![0], 1e-4)
    }

    @Test
    fun keepsThePlaceInACopyMadeForAnotherAppToEdit() {
        ExifInterface(File(photo.path)).apply { setLatLong(37.5, 127.0); saveAttributes() }
        runBlocking { container.settings.update { it.copy(removeLocationWhenSharing = true) } }

        val copy = runBlocking { container.libraryEdits.shareCopy(photo, null, forceCopy = true) }

        assertEquals(37.5, ExifInterface(copy).latLong!![0], 1e-4)
    }

    @Test
    fun compressesSharedJpegsAsFinelyAsTheSettingsSay() {
        val noisy = File(File(photo.path).parentFile, "DSC00003.JPG")
        val random = java.util.Random(7)
        val bitmap = Bitmap.createBitmap(1500, 1000, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(1500 * 1000) { 0xFF000000.toInt() or random.nextInt(0x1000000) }, 0, 1500, 0, 0, 1500, 1000)
        }
        noisy.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        val item = photo.copy(path = noisy.path, fileName = noisy.name, sizeBytes = noisy.length())

        runBlocking { container.settings.update { it.copy(jpegQuality = 50) } }
        val coarse = runBlocking { container.libraryEdits.shareCopy(item, 1000) }.length()
        runBlocking { container.settings.update { it.copy(jpegQuality = 100) } }
        val fine = runBlocking { container.libraryEdits.shareCopy(item, 1000) }.length()

        assertTrue("coarse $coarse, fine $fine", fine > coarse * 2)
    }
}
