package com.byeonggon.latent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.byeonggon.latent.SystemFilesProvider
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.grantLibraryAccess
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.io.IOException

/**
 * The originals that another installation of the app kept, under the package name before say, which shared
 * storage shuts to this one's paths: they are made this installation's own, and edits start from them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForeignOriginalsTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val storage get() = container.storage
    private lateinit var photo: File
    private lateinit var original: File
    private lateinit var originalBytes: ByteArray

    @Before
    fun keepAnOriginalOfAnotherInstallation() {
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        SystemFilesProvider.install()
        photo = File(storage.root, "2026/2026-10-08/DSC00001.JPG").apply { parentFile!!.mkdirs() }
        original = storage.originalOf(photo).apply { parentFile!!.mkdirs() }
        writeGrey(photo, EDITED_GREY)
        writeGrey(original, ORIGINAL_GREY)
        originalBytes = original.readBytes()
        original.setLastModified(KEPT_AT)
        original.setReadable(false)
        assumeFalse("The tests run as a user who reads every file", original.canRead())
    }

    @After
    fun clearTheLibrary() {
        storage.root.walk().forEach {
            it.setReadable(true)
            it.setWritable(true)
        }
        storage.root.deleteRecursively()
    }

    private fun leftBeside(name: String) = File(original.parentFile, name)

    private fun item(): MediaItemEntity = runBlocking {
        val id = container.database.mediaDao().insert(
            MediaItemEntity(path = photo.path, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 1, importedAt = 2),
        )
        container.database.mediaDao().byId(id)!!
    }

    private fun writeGrey(file: File, grey: Int) {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt() or grey * 0x010101) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    private fun greyOf(file: File) = BitmapFactory.decodeFile(file.path).getPixel(32, 24) and 0xFF

    @Test
    fun makesTheOriginalItsOwnWhereItIs() {
        assertTrue(storage.makeOwn(original))

        assertTrue(original.canRead())
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(KEPT_AT, original.lastModified())
        // Nothing set aside is left behind.
        assertEquals(listOf(original.name), original.parentFile!!.list()!!.toList())
    }

    @Test
    fun startsAgainFromACopyCutShort() {
        // The app was stopped while it copied: the original is still the other installation's.
        leftBeside(".DSC00001-copying.JPG").writeBytes(originalBytes.copyOf(20))

        assertTrue(storage.makeOwn(original))

        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(listOf(original.name), original.parentFile!!.list()!!.toList())
    }

    @Test
    fun finishesAMoveStoppedAfterTheCopy() {
        // Stopped once the copy was whole, before the other installation's file was deleted.
        leftBeside(".DSC00001-copied.JPG").writeBytes(originalBytes)

        assertTrue(storage.makeOwn(original))

        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(listOf(original.name), original.parentFile!!.list()!!.toList())
    }

    @Test
    fun finishesAMoveStoppedAfterTheOriginalWasDeleted() {
        original.delete()
        leftBeside(".DSC00001-copied.JPG").writeBytes(originalBytes)

        assertTrue(storage.makeOwn(original))

        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(listOf(original.name), original.parentFile!!.list()!!.toList())
    }

    @Test
    fun refusesToEditFromThePictureWhenTheOriginalCannotBeTakenOver() {
        // Nothing can be written beside the original, so it stays the other installation's.
        original.parentFile!!.setWritable(false)
        val item = item()

        assertFalse(storage.makeOwn(original))
        assertThrows(IOException::class.java) { container.libraryEdits.originalOf(item) }
        assertThrows(IOException::class.java) { runBlocking { container.libraryEdits.apply(item, PhotoEdit(Adjustments(exposure = 0.5f))) } }
        assertEquals(EDITED_GREY.toDouble(), greyOf(photo).toDouble(), 2.0)
    }

    @Test
    fun adoptsTheEditedPhotoWithItsOriginal() = runBlocking {
        assertEquals(1, container.libraryIndex.adoptUnknownFiles())

        val item = container.database.mediaDao().all().single()
        assertTrue(container.libraryEdits.isEdited(item))
        assertArrayEquals(originalBytes, original.readBytes())
    }

    @Test
    fun editsFromTheOriginalAndKeepsIt() = runBlocking {
        val item = item()

        assertEquals(original, container.libraryEdits.originalOf(item))
        container.libraryEdits.apply(item, PhotoEdit(Adjustments(exposure = 0.5f)))

        assertArrayEquals(originalBytes, original.readBytes())
        // Brightened from the dark original, not from the light picture the other installation saved.
        val grey = greyOf(photo)
        assertTrue("grey $grey", grey in (ORIGINAL_GREY + 1) until EDITED_GREY)
    }

    private companion object {
        const val ORIGINAL_GREY = 0x40
        const val EDITED_GREY = 0xC0
        const val KEPT_AT = 1_760_000_000_000L
    }
}
