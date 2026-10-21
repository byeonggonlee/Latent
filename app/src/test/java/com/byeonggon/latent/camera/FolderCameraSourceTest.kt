package com.byeonggon.latent.camera

import android.os.Environment
import android.provider.DocumentsContract
import com.byeonggon.latent.SystemFilesProvider
import com.byeonggon.latent.data.db.MediaKind
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.io.IOException

/** A Sony card as a card reader shows it: what the camera shows over MTP is read, its own folders are not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderCameraSourceTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var card: File
    private val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Card")

    @Before
    fun writeCard() {
        ShadowEnvironment.addExternalDir("shared")
        SystemFilesProvider.install()
        card = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).parentFile, "Card")
        put("DCIM/100MSDCF/DSC00001.JPG", "picture")
        put("DCIM/100MSDCF/DSC00001.ARW", "raw file")
        // What a Mac leaves on a card it opened.
        put("DCIM/100MSDCF/._DSC00001.JPG", "resource fork")
        put("PRIVATE/M4ROOT/CLIP/C0001.MP4", "video")
        put("PRIVATE/M4ROOT/CLIP/C0001M01.XML", "<xml/>")
        put("PRIVATE/M4ROOT/THMBNL/C0001T01.JPG", "thumbnail")
        put("PRIVATE/M4ROOT/SUB/C0001S03.MP4", "smaller copy")
    }

    @After
    fun clearCard() {
        card.deleteRecursively()
    }

    private fun put(path: String, text: String) = File(card, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
        setLastModified(MODIFIED_AT)
    }

    @Test
    fun readsTheFilesACameraShowsOverMtp() {
        val source = FolderCameraSource(context, tree)

        val camera = source.open()
        val listing = source.listFiles(ListingRequest()) {}

        assertEquals("Card", camera.model)
        assertTrue(isFolderCamera(camera.serial))
        assertEquals(
            setOf(
                "card:/DCIM/100MSDCF/DSC00001.JPG" to MediaKind.PHOTO,
                "card:/DCIM/100MSDCF/DSC00001.ARW" to MediaKind.RAW,
                "card:/PRIVATE/M4ROOT/CLIP/C0001.MP4" to MediaKind.VIDEO,
            ),
            listing.files.map { it.folder + it.name to it.kind }.toSet(),
        )
        val video = listing.files.single { it.name == "C0001.MP4" }
        assertEquals(5L, video.sizeBytes)
        assertEquals(MODIFIED_AT, video.modifiedAt)
    }

    @Test
    fun copiesAFileOffTheCard() {
        val source = FolderCameraSource(context, tree).apply { open() }
        val raw = source.listFiles(ListingRequest()) {}.files.single { it.name == "DSC00001.ARW" }
        val copy = File(context.cacheDir, "copy.ARW")

        assertTrue(source.download(raw, copy))

        assertArrayEquals("raw file".toByteArray(), copy.readBytes())
    }

    @Test
    fun readsTheEndOfAFileOffTheCard() {
        val source = FolderCameraSource(context, tree).apply { open() }
        val raw = source.listFiles(ListingRequest()) {}.files.single { it.name == "DSC00001.ARW" }

        assertArrayEquals("file".toByteArray(), source.readTail(raw, 4))
        assertArrayEquals("raw file".toByteArray(), source.readTail(raw, 1024))
    }

    @Test
    fun saysWhenTheCardIsOut() {
        card.deleteRecursively()

        assertThrows(IOException::class.java) { FolderCameraSource(context, tree).open() }
    }

    private companion object {
        const val MODIFIED_AT = 1_760_000_000_000L
    }
}
