package com.byeonggon.latent.data

import android.graphics.Bitmap
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.LutLook
import com.byeonggon.latent.enhance.LutStore
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.forgetTheApplicationOfEarlierTests
import com.byeonggon.latent.grantLibraryAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * An edit that could not be saved and is tried again later is saved to the photo as it is then, wherever it has
 * moved, and keeps what it needs (a LUT) until it is saved or given up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SaveFailureRecoveryTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val mediaDao get() = container.database.mediaDao()
    private lateinit var folder: File

    @Before
    fun setUp() {
        forgetTheApplicationOfEarlierTests()
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        folder = File(container.storage.root, "2026/2026-10-27").apply { mkdirs() }
    }

    private fun writePhoto(file: File, grey: Int) {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor((0xFF shl 24) or (grey * 0x010101)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun add(file: File): MediaItemEntity {
        val item = MediaItemEntity(path = file.absolutePath, fileName = file.name, kind = MediaKind.PHOTO, sizeBytes = file.length(), capturedAt = 1, importedAt = 1)
        return item.copy(id = runBlocking { mediaDao.insert(item) })
    }

    private fun waitUntil(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "Timed out" }
            Thread.sleep(20)
        }
    }

    /** Saves [edit] of [item], whose file is not there yet, and waits for the failure to be kept. */
    private fun failToSave(item: MediaItemEntity, edit: PhotoEdit) {
        container.editSaver.save(item, edit)
        waitUntil { item.id in container.editSaver.failed.value && item.id !in container.editSaver.saving.value }
    }

    @Test
    fun aRetryAfterTheTrashSavesToThePhotoWhereItIsNowAndLeavesTheOtherAlone() {
        val a = File(folder, "A.JPG")
        val photoA = add(a)
        val edit = PhotoEdit(Adjustments(exposure = 1f))
        failToSave(photoA, edit)

        // A is there after all; it goes to the trash, B is taken at A's old place, and A comes back under another name.
        writePhoto(a, grey = 100)
        runBlocking { container.trash.throwAway(listOf(mediaDao.byId(photoA.id)!!)) }
        writePhoto(a, grey = 200)
        val photoB = add(a)
        val bBefore = a.readBytes()
        runBlocking { container.trash.restore(listOf(mediaDao.byId(photoA.id)!!)) }
        val restored = File(runBlocking { mediaDao.byId(photoA.id)!!.path })
        assertFalse("A should come back under another name", restored == a)
        val aBefore = restored.readBytes()

        container.editSaver.retry(photoA.id)
        waitUntil { photoA.id !in container.editSaver.saving.value }

        assertNull(container.editSaver.failed.value[photoA.id])
        assertArrayEquals("B's file must not change", bBefore, a.readBytes())
        assertNull(runBlocking { mediaDao.byId(photoB.id)!!.edit })
        assertFalse("A's file should carry the edit", aBefore.contentEquals(restored.readBytes()))
        assertEquals(edit, PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoA.id)!!.edit }))
    }

    @Test
    fun aRetryOfAPhotoInTheTrashFailsAgainAndKeepsTheEdit() {
        val a = File(folder, "A.JPG")
        val photoA = add(a)
        failToSave(photoA, PhotoEdit(Adjustments(exposure = 1f)))
        writePhoto(a, grey = 100)
        runBlocking { container.trash.throwAway(listOf(mediaDao.byId(photoA.id)!!)) }

        container.editSaver.retry(photoA.id)
        waitUntil { photoA.id !in container.editSaver.saving.value }

        assertNotNull(container.editSaver.failed.value[photoA.id])
        assertNull(runBlocking { mediaDao.byId(photoA.id)!!.edit })
    }

    @Test
    fun aLutUsedOnlyByAFailedEditIsKeptUntilTheEditIsGivenUp() {
        val cube = buildString {
            append("TITLE \"Negative\"\nLUT_3D_SIZE 5\n")
            for (b in 0 until 5) for (g in 0 until 5) for (r in 0 until 5) append("${1f - r / 4f} ${1f - g / 4f} ${1f - b / 4f}\n")
        }
        val lut = runBlocking { container.libraryEdits.importLut(cube.byteInputStream(), "negative") }
        val photo = add(File(folder, "LUT.JPG"))
        failToSave(photo, PhotoEdit(lut = LutLook(lut.hash)))

        // Taken off the list while only the failed edit uses it: the table stays, so trying again keeps the look.
        runBlocking { container.libraryEdits.deleteLut(lut) }
        assertNotNull(LutStore.load(lut.hash))

        // Given up, nothing uses it any more, and the next clean-up deletes it.
        container.editSaver.discard(photo.id)
        runBlocking { container.libraryEdits.deleteLut(lut) }
        assertTrue(LutStore.load(lut.hash) == null)
    }
}
