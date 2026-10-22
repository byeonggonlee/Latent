package com.byeonggon.latent.data

import android.graphics.Bitmap
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.forgetTheApplicationOfEarlierTests
import com.byeonggon.latent.grantLibraryAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * An edit of a library photo that is stopped, by the Stop of a batch or a screen that closes, once it has begun writing
 * the photo, still records what it wrote: the file and the library agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditCancellationTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private lateinit var photo: File
    private lateinit var item: MediaItemEntity

    @Before
    fun photo() {
        forgetTheApplicationOfEarlierTests()
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        photo = File(container.storage.root, "2026/2026-10-22/DSC00001.JPG").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF607080.toInt()) }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        item = runBlocking {
            val id = container.database.mediaDao().insert(
                MediaItemEntity(path = photo.path, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 1, importedAt = 1),
            )
            container.database.mediaDao().byId(id)!!
        }
    }

    @Test
    fun recordsAnEditWhoseWritingHadBegunWhenItWasStopped() {
        val before = photo.readBytes()
        val edit = PhotoEdit(Adjustments(exposure = 1f))

        // Stopped as the edit begins: what it gets to write, it must also record.
        runBlocking(Dispatchers.Default) {
            val work = async {
                coroutineContext.job.cancel()
                container.libraryEdits.apply(item, edit)
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                // Stopped, as asked.
            }
        }

        val stored = runBlocking { container.database.mediaDao().byId(item.id)!! }
        if (photo.readBytes().contentEquals(before)) {
            // Stopped before anything was written: nothing is recorded, and no original is kept aside.
            assertEquals(null, stored.edit)
            assertFalse(container.storage.originalOf(photo).exists())
        } else {
            assertEquals(edit, PhotoEdit.fromJson(stored.edit))
            assertEquals(photo.length(), stored.sizeBytes)
            assertArrayEquals(before, container.storage.originalOf(photo).readBytes())
        }
    }

    @Test
    fun recordsAPutBackOriginalWhosePuttingBackHadBegunWhenItWasStopped() {
        val before = photo.readBytes()
        runBlocking { container.libraryEdits.apply(item, PhotoEdit(Adjustments(exposure = 1f))) }
        val edited = runBlocking { container.database.mediaDao().byId(item.id)!! }

        runBlocking(Dispatchers.Default) {
            val work = async {
                coroutineContext.job.cancel()
                container.libraryEdits.remove(edited)
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                // Stopped, as asked.
            }
        }

        val stored = runBlocking { container.database.mediaDao().byId(item.id)!! }
        if (photo.readBytes().contentEquals(before)) {
            assertEquals(null, stored.edit)
            assertEquals(photo.length(), stored.sizeBytes)
            assertFalse(container.storage.originalOf(photo).exists())
        } else {
            assertEquals(edited.edit, stored.edit)
            assertEquals(edited.sizeBytes, stored.sizeBytes)
        }
    }
}
