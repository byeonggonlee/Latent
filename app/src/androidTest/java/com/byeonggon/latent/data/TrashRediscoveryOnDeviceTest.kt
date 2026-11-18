package com.byeonggon.latent.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Uses a scratch database and uniquely named files only; no uninstall or app database reset. */
@RunWith(AndroidJUnit4::class)
class TrashRediscoveryOnDeviceTest {
    @Test
    fun failedRestoreReturnsARenamedProviderSidecarToItsBundle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val storage = LibraryStorage(context)
        assertTrue("Grant media and library-folder access to Latent Debug first", storage.isAvailable())
        val dao = database.mediaDao()
        val trash = Trash(storage, dao)
        val token = "TRASH_TEST_${UUID.randomUUID()}"
        val bundle = File(storage.trashRoot, System.currentTimeMillis().toString())
        val destinationFolder = File(storage.root, token)
        val originalFolder = File(storage.root, ".originals/$token")
        var madeBundle = false
        try {
            for (entry in storage.children(storage.trashRoot).orEmpty().filter { it.isDirectory }) {
                dao.insert(MediaItemEntity(path = File(entry.file, ".known-to-test").path, fileName = ".known-to-test", kind = MediaKind.PHOTO, sizeBytes = 0, capturedAt = 1, importedAt = 1))
            }
            assertTrue(bundle.mkdirs())
            madeBundle = true
            val photo = File(bundle, "$token.ARW").apply { writeText("raw bytes") }
            val sidecar = File(bundle, "$token.xmp")
            storage.writeNew(sidecar, "recovered location")
            val original = File(bundle, "original-$token.ARW").apply { writeText("original bytes") }
            assertEquals(1, trash.rediscover())
            val recovered = trash.items.first().single()
            val target = File(destinationFolder, "$token.ARW")
            dao.setPlace(recovered.id, recovered.path, recovered.trashedAt, target.path)
            val item = dao.byId(recovered.id)!!
            assertTrue(destinationFolder.mkdirs())
            val occupied = File(destinationFolder, "$token.xmp")
            storage.writeNew(occupied, "other location")
            // A unique regular file blocks just this test's kept-original directory.
            originalFolder.parentFile!!.mkdirs()
            storage.writeNew(originalFolder, "blocked directory")

            assertThrows(java.io.IOException::class.java) { runBlocking { trash.restore(listOf(item)) } }

            assertEquals(item, dao.byId(item.id))
            assertEquals("raw bytes", photo.readText())
            assertEquals("original bytes", original.readText())
            assertTrue("The provider-only sidecar must return with its photo", storage.exists(sidecar))
            assertEquals("recovered location", storage.folder.openInput(sidecar).bufferedReader().use { it.readText() })
            assertEquals("other location", storage.folder.openInput(occupied).bufferedReader().use { it.readText() })
            assertFalse(storage.exists(File(destinationFolder, "${token}_1.ARW")))
            assertFalse(storage.exists(File(destinationFolder, "${token}_1.xmp")))
        } finally {
            if (madeBundle) storage.delete(bundle)
            storage.delete(destinationFolder)
            storage.delete(originalFolder)
            database.close()
        }
    }

    @Test
    fun rediscoversRestoresAndEmptiesBundlesThroughRealStorage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val storage = LibraryStorage(context)
        assertTrue("Grant media and library-folder access to Latent Debug first", storage.isAvailable())
        val dao = database.mediaDao()
        val trash = Trash(storage, dao)
        val token = "TRASH_TEST_${UUID.randomUUID()}"
        val oldFolder = File(storage.trashRoot, System.currentTimeMillis().toString())
        val otherFolder = File(storage.trashRoot, (System.currentTimeMillis() + 1).toString())
        val cleanup = mutableListOf<File>()
        val createdDirectories = mutableListOf<File>()
        try {
            // Never rediscover or operate on pre-existing Debug files in this scratch database.
            for (entry in storage.children(storage.trashRoot).orEmpty().filter { it.isDirectory }) {
                dao.insert(MediaItemEntity(path = File(entry.file, ".known-to-test").path, fileName = ".known-to-test", kind = MediaKind.PHOTO, sizeBytes = 0, capturedAt = 1, importedAt = 1))
            }
            assertTrue(oldFolder.mkdirs())
            assertTrue(otherFolder.mkdirs())
            cleanup += oldFolder
            cleanup += otherFolder
            val raw = File(oldFolder, "$token.ARW").apply { writeText("raw bytes"); setLastModified(1) }
            storage.writeNew(File(oldFolder, "$token.xmp"), "location bytes")
            File(oldFolder, "original-$token.ARW").writeText("original bytes")
            val other = File(otherFolder, "${token}_EMPTY.ARW").apply { writeText("delete these test bytes") }
            // Exercise the real DocumentsProvider rather than only java.io.File listing.
            assertEquals(setOf(raw.name, "$token.xmp", "original-$token.ARW"), storage.folder.children(oldFolder)!!.map { it.file.name }.toSet())
            assertEquals(2, trash.rediscover())
            assertEquals(0, trash.rediscover())
            val items = trash.items.first()
            assertEquals(2, items.size)
            trash.emptyExpired(now = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650), keptDays = 1)
            assertEquals(2, trash.items.first().size)
            val recover = items.single { it.fileName == raw.name }
            val destination = File(recover.trashedFrom!!)
            if (!destination.parentFile!!.exists()) createdDirectories += destination.parentFile!!
            if (!destination.parentFile!!.parentFile!!.exists()) createdDirectories += destination.parentFile!!.parentFile!!
            destination.parentFile!!.mkdirs()
            // XMP is visible through the provider even when scoped storage hides it from File.exists().
            val occupiedSidecar = File(destination.parentFile, "$token.xmp")
            cleanup += occupiedSidecar
            storage.writeNew(occupiedSidecar, "other location bytes")
            val occupiedOriginal = storage.originalOf(File(destination.parentFile, "${token}_1.ARW"))
            if (!occupiedOriginal.parentFile!!.exists()) createdDirectories.add(0, occupiedOriginal.parentFile!!)
            if (!occupiedOriginal.parentFile!!.parentFile!!.exists()) createdDirectories.add(1, occupiedOriginal.parentFile!!.parentFile!!)
            occupiedOriginal.parentFile!!.mkdirs()
            cleanup += occupiedOriginal
            occupiedOriginal.writeText("other original bytes")
            val expected = File(destination.parentFile, "${token}_2.ARW")
            cleanup += expected
            cleanup += File(destination.parentFile, "${token}_2.xmp")
            cleanup += storage.originalOf(expected)
            trash.restore(listOf(recover))
            val restored = dao.byId(recover.id)!!
            assertNull(restored.trashedAt)
            assertEquals(expected.path, restored.path)
            assertEquals("raw bytes", File(restored.path).readText())
            assertEquals("location bytes", storage.folder.openInput(File(destination.parentFile, "${token}_2.xmp")).bufferedReader().use { it.readText() })
            assertEquals("original bytes", storage.originalOf(File(restored.path)).readText())
            assertEquals("other location bytes", storage.folder.openInput(occupiedSidecar).bufferedReader().use { it.readText() })
            assertEquals("other original bytes", occupiedOriginal.readText())
            val remove = items.single { it.fileName == other.name }
            trash.deleteForever(listOf(remove))
            assertFalse(other.exists())
            assertNull(dao.byId(remove.id))
            assertTrue(trash.items.first().isEmpty())
        } finally {
            cleanup.forEach { storage.delete(it) }
            createdDirectories.forEach { it.delete() }
            database.close()
        }
    }
}
