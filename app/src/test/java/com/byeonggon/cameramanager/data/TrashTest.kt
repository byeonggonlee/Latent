package com.byeonggon.cameramanager.data

import com.byeonggon.cameramanager.grantLibraryAccess
import android.os.Environment
import com.byeonggon.cameramanager.appContainer
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrashTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val mediaDao get() = container.database.mediaDao()
    private val trash get() = container.trash
    private lateinit var folder: File
    private lateinit var raw: File
    private lateinit var sidecar: File
    private var rawId = 0L

    @Before
    fun writeLibrary() {
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
        raw = File(folder, "DSC00002.ARW").apply { writeText("raw") }
        sidecar = File(folder, "DSC00002.xmp").apply { writeText("<x/>") }
        rawId = runBlocking {
            mediaDao.insert(MediaItemEntity(path = raw.path, fileName = raw.name, kind = MediaKind.RAW, sizeBytes = 3, capturedAt = 1, importedAt = 1))
        }
    }

    private fun item() = runBlocking { mediaDao.byId(rawId)!! }

    @Test
    fun takesAPhotoOutOfTheLibraryWithWhatBelongsToIt() {
        runBlocking { trash.throwAway(listOf(item())) }

        assertFalse(raw.exists())
        assertFalse(sidecar.exists())
        val kept = File(item().path)
        assertEquals("raw", kept.readText())
        assertTrue(File(kept.parentFile, "DSC00002.xmp").exists())
        assertEquals(emptyList<MediaItemEntity>(), runBlocking { mediaDao.observeAll().first() })
        assertEquals(listOf(rawId), runBlocking { trash.items.first() }.map { it.id })
        // Gallery apps keep out of folders whose names start with a dot.
        assertTrue(kept.path.contains("/.trash/"))
    }

    @Test
    fun putsAPhotoBackWhereItWas() {
        runBlocking { trash.throwAway(listOf(item())) }

        runBlocking { trash.restore(listOf(item())) }

        assertEquals(raw.path, item().path)
        assertNull(item().trashedAt)
        assertEquals("raw", raw.readText())
        assertTrue(sidecar.exists())
        assertEquals(listOf(rawId), runBlocking { mediaDao.observeAll().first() }.map { it.id })
    }

    @Test
    fun putsAPhotoBesideItsOldPlaceIfThatIsTaken() {
        runBlocking { trash.throwAway(listOf(item())) }
        raw.writeText("another")

        runBlocking { trash.restore(listOf(item())) }

        assertEquals("DSC00002_1.ARW", File(item().path).name)
        assertEquals("another", raw.readText())
    }

    @Test
    fun keepsAnEditedJpegsOriginalWithIt() {
        val photo = File(folder, "DSC00001.JPG").apply { writeText("edited") }
        val original = container.storage.originalOf(photo).apply { parentFile!!.mkdirs(); writeText("original") }
        val id = runBlocking {
            mediaDao.insert(MediaItemEntity(path = photo.path, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = 6, capturedAt = 1, importedAt = 1))
        }

        runBlocking { trash.throwAway(listOf(mediaDao.byId(id)!!)) }
        assertFalse(original.exists())
        runBlocking { trash.restore(listOf(mediaDao.byId(id)!!)) }

        assertEquals("original", original.readText())
        assertEquals("edited", photo.readText())
    }

    @Test
    fun deletesForGoodOnlyWhatIsInTheTrash() {
        runBlocking { trash.deleteForever(listOf(item())) }
        assertTrue(raw.exists())

        runBlocking { trash.throwAway(listOf(item())) }
        val kept = File(item().path)
        runBlocking { trash.deleteForever(listOf(item())) }

        assertFalse(kept.exists())
        assertNull(runBlocking { mediaDao.byId(rawId) })
    }

    @Test
    fun emptiesItselfOfWhatHasBeenThereForAMonth() {
        runBlocking { trash.throwAway(listOf(item())) }
        val now = System.currentTimeMillis()

        runBlocking { trash.emptyExpired(now + TimeUnit.DAYS.toMillis(29)) }
        assertEquals(1, runBlocking { trash.items.first() }.size)
        runBlocking { trash.emptyExpired(now + TimeUnit.DAYS.toMillis(31)) }
        assertEquals(0, runBlocking { trash.items.first() }.size)
    }
}
