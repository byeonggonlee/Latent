package com.byeonggon.latent.data

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import android.os.Environment
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.byeonggon.latent.SystemFilesProvider
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.grantLibraryAccess
import com.byeonggon.latent.ui.library.LibraryViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrashRediscoveryTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val container get() = application.appContainer
    private val dao get() = container.database.mediaDao()
    private val storage get() = container.storage
    private val trash get() = container.trash

    @Before
    fun allowStorage() {
        ShadowEnvironment.addExternalDir("shared")
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        grantLibraryAccess()
    }

    @After
    fun clearFiles() {
        storage.root.deleteRecursively()
    }

    @Test
    fun libraryRefreshFindsTrashWhoseDatabaseWasLost() {
        val kept = File(storage.root, ".trash/41/OLD.ARW").apply { parentFile!!.mkdirs(); writeText("old raw") }
        refreshUntil { trashItems().isNotEmpty() }

        assertEquals("path=${kept.absoluteFile}, canonical=${kept.canonicalFile}, access=${storage.isAvailable()}", 1, trashItems().size)
        val recovered = trashItems().single()
        assertEquals(kept.path, recovered.path)
        assertEquals("old raw", kept.readText())
        assertEquals(emptyList<MediaItemEntity>(), runBlocking { dao.observeAll().first() })
        assertTrue(recovered.trashedFrom!!.startsWith(storage.root.path + "/"))
    }

    @Test
    fun aRestartedDatabaseIdCannotOverwriteAnOldTrashBundle() = runBlocking {
        val live = File(storage.root, "2026/2026-11-18/SAME.ARW").apply { parentFile!!.mkdirs(); writeText("new raw") }
        val id = dao.insert(MediaItemEntity(path = live.path, fileName = live.name, kind = MediaKind.RAW, sizeBytes = live.length(), capturedAt = 1, importedAt = 1))
        val orphan = File(storage.root, ".trash/$id/SAME.ARW").apply { parentFile!!.mkdirs(); writeText("old raw") }

        trash.throwAway(listOf(dao.byId(id)!!))

        assertEquals("old raw", orphan.readText())
        val current = dao.byId(id)!!
        assertTrue(current.path != orphan.path)
        assertEquals("new raw", File(current.path).readText())
    }

    @Test
    fun repeatedDiscoveryDoesNotDuplicateOrAutomaticallyExpireRecoveredPhotos() = runBlocking {
        val old = File(storage.root, ".trash/91/OLD.ARW").apply {
            parentFile!!.mkdirs(); writeText("keep"); setLastModified(1)
        }
        refreshUntil { trashItems().size == 1 }
        assertEquals(1, trashItems().size)
        val first = trashItems().single()
        assertEquals(0, trash.rediscover())
        assertEquals(listOf(first.id), trashItems().map { it.id })
        trash.emptyExpired(now = System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(3650), keptDays = 1)
        assertEquals(listOf(first.id), trashItems().map { it.id })
        assertEquals("keep", old.readText())
    }

    @Test
    fun restoresRecoveredBundlesWithTheirOriginalAndLocationSidecar() = runBlocking {
        val raw = File(storage.root, ".trash/12/RAW.ARW").apply { parentFile!!.mkdirs(); writeText("raw") }
        File(raw.parentFile, "RAW.xmp").writeText("place")
        val photo = File(storage.root, ".trash/13/PHOTO.JPG").apply { parentFile!!.mkdirs(); writeText("edited") }
        File(photo.parentFile, "original-PHOTO.JPG").writeText("original")
        refreshUntil { trashItems().size == 2 }
        assertEquals(2, trashItems().size)
        val recovered = trashItems()
        // A file arriving at the proposed restore path after discovery must be kept too.
        val rawItem = recovered.single { it.kind == MediaKind.RAW }
        File(rawItem.trashedFrom!!).apply { parentFile!!.mkdirs(); writeText("different") }
        val unexpected = File(raw.parentFile, "unexpected.bin").apply { writeText("preserve") }
        trash.restore(recovered)
        assertEquals("preserve", unexpected.readText())
        val restoredRaw = dao.byId(rawItem.id)!!
        val restoredPhoto = dao.byId(recovered.single { it.kind == MediaKind.PHOTO }.id)!!
        assertNull(restoredRaw.trashedAt)
        assertEquals("RAW_1.ARW", File(restoredRaw.path).name)
        assertEquals("different", File(rawItem.trashedFrom).readText())
        assertEquals("raw", File(restoredRaw.path).readText())
        assertEquals("place", File(File(restoredRaw.path).parentFile, "RAW_1.xmp").readText())
        assertEquals("edited", File(restoredPhoto.path).readText())
        assertEquals("original", storage.originalOf(File(restoredPhoto.path)).readText())
        assertTrue(trashItems().isEmpty())
    }

    @Test
    fun restoreSkipsNamesWhoseLocationSidecarsAreAlreadyTaken() = runBlocking {
        val raw = File(storage.root, ".trash/71/RAW.ARW").apply { parentFile!!.mkdirs(); writeText("raw") }
        File(raw.parentFile, "RAW.xmp").writeText("recovered place")
        assertEquals(1, trash.rediscover())
        val item = trashItems().single()
        val destination = File(item.trashedFrom!!).parentFile!!.apply { mkdirs() }
        File(destination, "RAW.xmp").writeText("other place")
        File(destination, "RAW_1.xmp").writeText("another place")

        trash.restore(listOf(item))

        val restored = dao.byId(item.id)!!
        assertEquals("RAW_2.ARW", File(restored.path).name)
        assertEquals("raw", File(restored.path).readText())
        assertEquals("recovered place", File(destination, "RAW_2.xmp").readText())
        assertEquals("other place", File(destination, "RAW.xmp").readText())
        assertEquals("another place", File(destination, "RAW_1.xmp").readText())
    }

    @Test
    fun restoreSkipsNamesWhoseKeptOriginalsAreAlreadyTaken() = runBlocking {
        val photo = File(storage.root, ".trash/72/PHOTO.JPG").apply { parentFile!!.mkdirs(); writeText("edited") }
        File(photo.parentFile, "original-PHOTO.JPG").writeText("recovered original")
        assertEquals(1, trash.rediscover())
        val item = trashItems().single()
        val destination = File(item.trashedFrom!!)
        val occupied = storage.originalOf(destination).apply { parentFile!!.mkdirs(); writeText("other original") }
        val occupiedSuffix = File(occupied.parentFile, "PHOTO_1.JPG").apply { writeText("another original") }

        trash.restore(listOf(item))

        val restored = File(dao.byId(item.id)!!.path)
        assertEquals("PHOTO_2.JPG", restored.name)
        assertEquals("edited", restored.readText())
        assertEquals("recovered original", storage.originalOf(restored).readText())
        assertEquals("other original", occupied.readText())
        assertEquals("another original", occupiedSuffix.readText())
    }

    @Test
    fun failedOriginalRestoreRollsBackThePhotoAndItsAlreadyMovedSidecar() = runBlocking {
        val photo = File(storage.root, ".trash/74/PHOTO.JPG").apply { parentFile!!.mkdirs(); writeText("edited") }
        val sidecar = File(photo.parentFile, "PHOTO.xmp").apply { writeText("place") }
        val original = File(photo.parentFile, "original-PHOTO.JPG").apply { writeText("original") }
        assertEquals(1, trash.rediscover())
        val item = trashItems().single()
        // A real I/O failure after the photo and sidecar move: the original's parent cannot be made.
        val blocked = File(storage.root, ".originals").apply { writeText("not a directory") }
        val target = File(item.trashedFrom!!)

        assertThrows(java.io.IOException::class.java) { runBlocking { trash.restore(listOf(item)) } }

        assertEquals(item, dao.byId(item.id))
        assertEquals("edited", photo.readText())
        assertEquals("place", sidecar.readText())
        assertEquals("original", original.readText())
        assertFalse(target.exists())
        assertFalse(File(target.parentFile, "PHOTO.xmp").exists())
        assertEquals(0, trash.rediscover())
        // Retry remains possible after the storage failure is fixed.
        blocked.delete()
        trash.restore(listOf(item))
        assertNull(dao.byId(item.id)!!.trashedAt)
        assertEquals("edited", target.readText())
        assertEquals("place", File(target.parentFile, "PHOTO.xmp").readText())
        assertEquals("original", storage.originalOf(target).readText())
    }

    @Test
    fun explicitEmptyDeletesOnlyTheChosenRecoveredBundle() = runBlocking {
        val a = File(storage.root, ".trash/7/A.ARW").apply { parentFile!!.mkdirs(); writeText("a") }
        val b = File(storage.root, ".trash/8/B.ARW").apply { parentFile!!.mkdirs(); writeText("b") }
        refreshUntil { trashItems().size == 2 }
        assertEquals(2, trashItems().size)
        val first = trashItems().single { it.fileName == a.name }
        trash.deleteForever(listOf(first))
        assertFalse(a.exists())
        assertNull(dao.byId(first.id))
        assertEquals("b", b.readText())
        assertEquals(listOf(b.name), trashItems().map { it.fileName })
    }

    @Test
    fun leavesAmbiguousOriginalOnlyAndUnknownBundlesUntouched() {
        val files = listOf(".trash/1/A.JPG", ".trash/1/B.JPG", ".trash/2/original-C.JPG", ".trash/3/D.JPG", ".trash/3/unknown.bin", ".trash/notes/E.JPG", ".trash/5/.PHOTO-copying.JPG")
            .map { File(storage.root, it).apply { parentFile!!.mkdirs(); writeText("untouched") } }
        assertEquals(0, runBlocking { trash.rediscover() })
        assertTrue(trashItems().isEmpty())
        assertTrue(files.all { it.readText() == "untouched" })
    }

    @Test
    fun aSymlinkedTrashRootCannotExposeFilesOutsideTheTrash() = runBlocking {
        val external = File(application.cacheDir, "outside-trash").apply { mkdirs() }
        val photo = File(external, "73/OUTSIDE.ARW").apply { parentFile!!.mkdirs(); writeText("outside") }
        storage.root.mkdirs()
        java.nio.file.Files.createSymbolicLink(File(storage.root, ".trash").toPath(), external.toPath())
        try {
            trash.rediscover()
            assertTrue(trashItems().isEmpty())
            assertEquals("outside", photo.readText())
        } finally {
            File(storage.root, ".trash").delete()
            external.deleteRecursively()
        }
    }

    @Test
    fun enumeratesOldHiddenFilesThroughTheGrantedFolderProvider() {
        SystemFilesProvider.install()
        val folder = File(storage.root, ".trash/34").apply { mkdirs() }
        File(folder, "OLD.ARW").writeText("raw")
        File(folder, "OLD.xmp").writeText("place")
        val children = storage.folder.children(folder)!!
        assertEquals(setOf("OLD.ARW", "OLD.xmp"), children.map { it.file.name }.toSet())
        assertTrue(children.none { it.isDirectory })
    }

    @Test
    fun preservesKnownTrashAndLeavesFilesAloneWithoutStorageAccess() = runBlocking {
        val photo = File(storage.root, "2026/2026-11-18/KNOWN.ARW").apply { parentFile!!.mkdirs(); writeText("known") }
        val id = dao.insert(MediaItemEntity(path = photo.path, fileName = photo.name, kind = MediaKind.RAW, sizeBytes = photo.length(), capturedAt = 1, importedAt = 1))
        trash.throwAway(listOf(dao.byId(id)!!))
        val known = dao.byId(id)!!
        assertEquals(0, trash.rediscover())
        assertEquals(known, dao.byId(id))
        val orphan = File(storage.root, ".trash/99/LEFT.ARW").apply { parentFile!!.mkdirs(); writeText("left") }
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_UNMOUNTED)
        assertEquals(0, trash.rediscover())
        assertEquals(listOf(id), trashItems().map { it.id })
        assertEquals("left", orphan.readText())
    }

    @Test
    fun unfinishedTakeoverCopiesDoNotHideTheIntactPhoto() = runBlocking {
        val main = File(storage.root, ".trash/61/INTACT.ARW").apply { parentFile!!.mkdirs(); writeText("whole") }
        val partial = File(main.parentFile, ".INTACT-copying.ARW").apply { writeText("partial") }
        val wholeCopy = File(main.parentFile, ".INTACT-copied.ARW").apply { writeText("whole") }
        assertEquals(1, trash.rediscover())
        assertEquals(main.path, trashItems().single().path)
        assertEquals("whole", main.readText())
        assertEquals("partial", partial.readText())
        assertEquals("whole", wholeCopy.readText())
    }

    @Test
    fun failedAutomaticExpiryKeepsItsRowAndContinuesWithOtherPhotos() = runBlocking {
        fun photo(name: String) = File(storage.root, "2026/2026-11-18/$name.ARW").apply { parentFile!!.mkdirs(); writeText(name) }
        suspend fun insert(file: File) = dao.insert(MediaItemEntity(path = file.path, fileName = file.name, kind = MediaKind.RAW, sizeBytes = file.length(), capturedAt = 1, importedAt = 1))
        val a = insert(photo("LOCKED"))
        val b = insert(photo("FREE"))
        trash.throwAway(listOf(dao.byId(a)!!, dao.byId(b)!!))
        val blocked = dao.byId(a)!!
        val folder = File(blocked.path).parentFile!!
        folder.setWritable(false)
        try {
            assumeFalse("A privileged user bypasses filesystem write protection", folder.canWrite())
            assertThrows(java.io.IOException::class.java) { runBlocking { trash.deleteForever(listOf(blocked)) } }
            assertEquals(blocked, dao.byId(a))
            try {
                trash.emptyExpired(now = System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(31))
            } catch (e: java.io.IOException) {
                throw AssertionError("Background expiry must leave failures for retry rather than crash refresh", e)
            }
            assertEquals(blocked, dao.byId(a))
            assertNull(dao.byId(b))
        } finally {
            folder.setWritable(true)
        }
    }

    @Test
    fun recoveredCaptureTimeUsesTheConfiguredCameraZone() = runBlocking {
        container.settings.update { it.copy(cameraZoneId = "Pacific/Honolulu") }
        val photo = datedJpeg("62")
        assertEquals(1, trash.rediscover())
        val recovered = trashItems().single()
        assertEquals(java.time.Instant.parse("2026-01-02T09:30:00Z").toEpochMilli(), recovered.capturedAt)
        assertEquals(photo.path, recovered.path)
    }

    @Test
    fun discoveryDoesNotCreateTheRestoreDestination() = runBlocking {
        datedJpeg("63")
        assertEquals(1, trash.rediscover())
        val destination = File(trashItems().single().trashedFrom!!)
        assertFalse(destination.parentFile!!.exists())
    }

    private fun datedJpeg(bundle: String): File {
        val file = File(storage.root, ".trash/$bundle/DATE.JPG").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } } finally { bitmap.recycle() }
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:01 23:30:00"); saveAttributes() }
        return file
    }

    private fun trashItems() = runBlocking { trash.items.first() }

    private fun refreshUntil(done: () -> Boolean) {
        val viewModel = LibraryViewModel(application)
        try {
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (done()) return
                Thread.sleep(5)
            }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
