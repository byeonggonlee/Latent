package com.byeonggon.latent.camera

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.Mutator
import com.byeonggon.latent.fuzz
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * What a camera or a card says about its files: names, sizes and times of any value, folders listed in themselves or
 * not at all, and files that only look like the ones imported. A sync lists what it can and compares what it must,
 * and never throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraListingFuzzTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A camera's storage made up as it goes: folders inside themselves, objects it cannot describe, names of every kind. */
    private class MadeUpTree(private val mutator: Mutator) : RemoteTree {
        val random = mutator.random
        private val objects = HashMap<Int, RemoteObject?>()
        private val names = listOf("DCIM", "100MSDCF", "2024-12-08", "2024-02-30", "+999999999-01-01", "DSC00001.JPG", "DSC00002.ARW", "C0001.MP4", ".", "..", "", "a/b.JPG", "..\\x.JPG", "x\u0000.JPG", "ÄÖ.JPG", "README", ".hidden.JPG", "x".repeat(300) + ".JPG")
        var listings = 0

        override fun childrenOf(parent: Int): IntArray? {
            listings++
            if (random.nextInt(10) == 0) return null
            // Handles already seen come again, as a folder listed inside itself would.
            return IntArray(random.nextInt(if (listings > 200) 1 else 8)) { random.nextInt(-2, 60) }
        }

        override fun objectOf(handle: Int): RemoteObject? = objects.getOrPut(handle) {
            if (random.nextInt(12) == 0) {
                null
            } else {
                RemoteObject(
                    name = names[random.nextInt(names.size)],
                    isFolder = random.nextInt(3) == 0,
                    sizeBytes = listOf(0L, -1L, 100L, Long.MAX_VALUE, Long.MIN_VALUE)[random.nextInt(5)],
                    modifiedAt = listOf(0L, Long.MIN_VALUE, Long.MAX_VALUE, 1_733_600_000_000L)[random.nextInt(4)],
                )
            }
        }
    }

    @Test
    fun anyStorageIsListedToItsEnd() = fuzz(seed = 62, cases = 3_000) { mutator ->
        val tree = MadeUpTree(mutator)
        val dates = if (mutator.random.nextBoolean()) SyncDates(LocalDate.of(2024, 12, 1), LocalDate.of(2024, 12, 31)) else null
        val listing = listStorage(tree, -1, "card", ListingRequest(dates, mapOf("card:/DCIM/" to "1|x|1|0|x|1|0")), onFound = {})
        for (file in listing.files) {
            assertTrue(file.name, isSafeName(file.name))
            assertTrue(file.folder, file.folder.startsWith("card:/"))
        }
    }

    private fun jpeg(name: String): File = folder.newFile(name).apply {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(this).apply {
            setAttribute(ExifInterface.TAG_MODEL, "ZV-E10")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:12:08 10:00:00")
            saveAttributes()
        }
    }

    @Test
    fun aDamagedCopyIsComparedWithoutThrowing() {
        val imported = jpeg("imported.jpg")
        val bytes = imported.readBytes()
        val copy = folder.newFile("copy.jpg")
        copy.writeBytes(bytes)
        assertTrue(sameAsImported(copy, imported))
        fuzz(seed = 63, cases = 1_500) { mutator ->
            val changed = mutator.bytes(bytes)
            copy.writeBytes(changed)
            val same = sameAsImported(copy, imported)
            // Changed anywhere but in its EXIF, which a place is written into, a copy is another file.
            if (same) assertTrue(changed.size == bytes.size || changed.size > 4)
            val other = folder.newFile()
            other.writeBytes(mutator.bytes(bytes))
            sameAsImported(copy, other)
            other.delete()
        }
    }
}
