package com.byeonggon.latent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.enhance.PhotoEnhancer
import com.byeonggon.latent.grantLibraryAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/**
 * HEIF pictures (HEIC, HIF) are read and drawn but never written: they are treated like another app's photos.
 * The test files are JPEGs under HEIF names, as the JVM cannot encode HEIF; what is tested is that nothing
 * is written into them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeifTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private lateinit var heif: MediaItemEntity

    @Before
    fun writeLibrary() {
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val file = File(container.storage.root, "2026/2026-10-08/DSC00001.HIF").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 96) bitmap.setPixel(x, y, (0xFF shl 24) or ((60 + x + y / 2) * 0x010101))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_MODEL, "ILCE-6700"); saveAttributes() }
        val item = MediaItemEntity(path = file.path, fileName = file.name, kind = MediaKind.PHOTO, sizeBytes = file.length(), capturedAt = 1_000_000, importedAt = 1)
        heif = item.copy(id = runBlocking { container.database.mediaDao().insert(item) })
    }

    @Test
    fun knowsHeifPicturesByTheirNames() {
        val jpeg = heif.copy(fileName = "DSC00001.JPG")
        assertTrue(heif.isHeif && heif.editsAsSettings && heif.photoKeptAsIs)
        assertTrue(heif.copy(fileName = "IMG_0001.heic").isHeif)
        assertTrue(heif.copy(fileName = "IMG_0002.HEIF").isHeif)
        assertFalse(jpeg.isHeif || jpeg.editsAsSettings || jpeg.photoKeptAsIs)
        // A raw file is kept as settings too, but is developed, not copied.
        assertTrue(jpeg.copy(kind = MediaKind.RAW, fileName = "DSC00001.ARW").let { it.editsAsSettings && !it.photoKeptAsIs })
        assertTrue(jpeg.copy(origin = MediaOrigin.PHONE).photoKeptAsIs)
    }

    @Test
    fun keepsAnEditAsSettingsAndNeverWritesIntoTheFile() = runBlocking {
        val before = File(heif.path).readBytes()
        val edit = PhotoEdit(Adjustments(exposure = 0.5f))

        container.libraryEdits.apply(heif, edit)

        val stored = container.database.mediaDao().byId(heif.id)!!
        assertEquals(edit, container.libraryEdits.editOf(stored))
        assertTrue(container.libraryEdits.isEdited(stored))
        assertArrayEquals(before, File(heif.path).readBytes())
        assertFalse(container.storage.originalOf(File(heif.path)).exists())

        container.libraryEdits.remove(stored)
        assertFalse(container.libraryEdits.isEdited(container.database.mediaDao().byId(heif.id)!!))
    }

    @Test
    fun savesAnEditedCopyAsAJpegWithTheCamerasExifAndSharesTheEditedPicture() = runBlocking {
        container.libraryEdits.apply(heif, PhotoEdit(Adjustments(exposure = 0.5f)))
        val edited = container.database.mediaDao().byId(heif.id)!!

        val copy = container.libraryEdits.saveCopy(edited)

        assertTrue(copy.name.endsWith("_edited.jpg"))
        assertNotNull(BitmapFactory.decodeFile(copy.path))
        assertEquals("ILCE-6700", ExifInterface(copy).getAttribute(ExifInterface.TAG_MODEL))
        val shared = container.libraryEdits.shareCopy(edited, null)
        assertTrue(shared.path != heif.path)
        assertNotNull(BitmapFactory.decodeFile(shared.path))
    }

    @Test
    fun exportsFromAPictureThatIsNotAJpegWithItsExifCopiedOver() {
        // A PNG stands in for any picture the Exif block cannot be lifted from byte for byte.
        val png = File(container.storage.root, "source.png")
        png.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val target = File(container.storage.root, "out.jpg")

        PhotoEnhancer.exportEdited(png, target, PhotoEdit(Adjustments(exposure = 0.3f)))

        assertEquals(0xFF, target.readBytes()[0].toInt() and 0xFF)
        assertEquals(0xD8, target.readBytes()[1].toInt() and 0xFF)
        assertNotNull(BitmapFactory.decodeFile(target.path))
    }

    @Test
    fun putsAPlaceBesideAHeifPictureInASidecarAndLeavesTheFileAlone() = runBlocking {
        container.database.locationDao().insertAll(listOf(LocationPointEntity(timestamp = 1_000_000, latitude = 37.5, longitude = 127.0, source = LocationSource.APP)))
        val before = File(heif.path).readBytes()

        assertEquals(1, container.geotagger.tagPending(maxGapMs = 60_000L))

        assertArrayEquals(before, File(heif.path).readBytes())
        assertTrue(File(heif.path).resolveSibling("DSC00001.xmp").exists())
        assertEquals(37.5, container.database.mediaDao().byId(heif.id)!!.latitude!!, 1e-6)
    }
}
