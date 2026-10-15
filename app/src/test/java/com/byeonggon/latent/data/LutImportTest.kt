package com.byeonggon.latent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.LutLook
import com.byeonggon.latent.enhance.LutProblem
import com.byeonggon.latent.enhance.LutStore
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.enhance.UnusableLutException
import com.byeonggon.latent.grantLibraryAccess
import com.byeonggon.latent.raw.SyntheticArw
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LutImportTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val edits get() = container.libraryEdits

    /** A .cube file that turns every colour into its opposite. */
    private fun inverting(title: String? = "Negative", size: Int = 5) = buildString {
        if (title != null) append("TITLE \"$title\"\n")
        append("# Made for a test\nLUT_3D_SIZE $size\n")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            append("${1f - r / (size - 1f)} ${1f - g / (size - 1f)} ${1f - b / (size - 1f)}\n")
        }
    }

    private fun refusal(text: String): UnusableLutException {
        try {
            runBlocking { edits.importLut(text.byteInputStream(), "x") }
        } catch (e: UnusableLutException) {
            return e
        }
        fail("imported")
        throw AssertionError()
    }

    @Test
    fun keepsALutUnderItsTitleOnceHoweverOftenItIsImported() = runBlocking {
        val lut = edits.importLut(inverting().byteInputStream(), "negative-file")

        assertEquals("Negative", lut.name)
        assertEquals(5, lut.size)
        assertEquals(32, lut.hash.length)
        assertTrue(File(RuntimeEnvironment.getApplication().filesDir, "luts/${lut.hash}.lut").isFile)
        assertNotNull(LutStore.load(lut.hash))

        assertEquals(lut, edits.importLut(inverting().byteInputStream(), "again"))
        assertEquals(listOf(lut), edits.luts.first())
    }

    @Test
    fun namesALutAfterItsFileWhenItHasNoTitle() = runBlocking {
        assertEquals("Teal and orange", edits.importLut(inverting(title = null).byteInputStream(), "Teal and orange").name)
    }

    @Test
    fun saysWhyAFileCannotBeUsedAndKeepsNothingOfIt() = runBlocking {
        assertEquals(LutProblem.ONE_DIMENSIONAL, refusal("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n").problem)
        assertEquals(LutProblem.NOT_A_LUT, refusal("Hello\n").problem)
        val short = refusal(inverting().lines().dropLast(3).joinToString("\n"))
        assertEquals(LutProblem.WRONG_COUNT, short.problem)
        val broken = refusal(inverting().replace("\n0.75 1.0 1.0\n", "\n0.75 one 1.0\n"))
        assertEquals(LutProblem.NOT_A_NUMBER, broken.problem)
        assertEquals(5, broken.line)

        assertEquals(emptyList<Any>(), edits.luts.first())
        assertEquals(emptyList<String>(), LutStore.ids())
    }

    /** A gray JPEG and a raw file in the library, listed in the database. */
    private fun library(): Pair<MediaItemEntity, MediaItemEntity> {
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
        val photoFile = File(folder, "DSC00001.JPG")
        Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF505050.toInt()) }
            .let { bitmap -> photoFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val rawFile = File(folder, "DSC00002.ARW").apply { writeBytes(SyntheticArw.file(red = 600, green = 900, blue = 700)) }
        val photo = MediaItemEntity(path = photoFile.path, fileName = photoFile.name, kind = MediaKind.PHOTO, sizeBytes = photoFile.length(), capturedAt = 1, importedAt = 1)
        val raw = photo.copy(path = rawFile.path, fileName = rawFile.name, kind = MediaKind.RAW, sizeBytes = rawFile.length())
        val dao = container.database.mediaDao()
        return runBlocking { photo.copy(id = dao.insert(photo)) to raw.copy(id = dao.insert(raw)) }
    }

    private fun greenOf(file: File): Int {
        val bitmap = BitmapFactory.decodeFile(file.path)
        return (bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) ushr 8) and 0xFF
    }

    @Test
    fun bakesALutIntoAJpegAndDevelopsARawFileWithIt() = runBlocking {
        val (photo, raw) = library()
        val lut = edits.importLut(inverting().byteInputStream(), "x")
        val inverted = PhotoEdit(lut = LutLook(lut.hash))

        // A JPEG of the library is written with the LUT in it.
        edits.apply(photo, inverted)
        assertEquals(0xAF.toDouble(), greenOf(File(photo.path)).toDouble(), 3.0)
        assertEquals(inverted, edits.editOf(container.database.mediaDao().byId(photo.id)!!))

        // A raw file keeps the LUT as a setting, and what is made of it has it in.
        val plain = greenOf(edits.shareCopy(raw, 2048))
        edits.apply(raw, PhotoEdit(lut = LutLook(lut.hash, 0.5f)))
        val half = greenOf(edits.shareCopy(container.database.mediaDao().byId(raw.id)!!, 2048))
        assertTrue("plain $plain, half $half", abs(half - 128) < abs(plain - 128) / 2 + 4)
    }

    @Test
    fun aLutTakenOffTheListStaysForThePhotosThatUseIt() = runBlocking {
        val (_, raw) = library()
        val used = edits.importLut(inverting().byteInputStream(), "used")
        val unused = edits.importLut(inverting(size = 3).byteInputStream(), "unused")
        edits.apply(raw, PhotoEdit(lut = LutLook(used.hash)))
        val developed = greenOf(edits.shareCopy(container.database.mediaDao().byId(raw.id)!!, 2048))

        edits.deleteLut(used)
        edits.deleteLut(unused)

        assertEquals(emptyList<Any>(), edits.luts.first())
        assertEquals(listOf(used.hash), LutStore.ids())
        // Forgotten and read again from its file, it gives the photo the same look.
        LutStore.directory = File(RuntimeEnvironment.getApplication().filesDir, "luts")
        assertEquals(developed, greenOf(edits.shareCopy(container.database.mediaDao().byId(raw.id)!!, 2048)))

        // A filter of one's own keeps it too; once nothing uses it, the next deletion takes it away.
        edits.saveFilter("Negative", PhotoEdit(lut = LutLook(used.hash)))
        edits.remove(container.database.mediaDao().byId(raw.id)!!)
        edits.deleteLut(used)
        assertEquals(listOf(used.hash), LutStore.ids())
        edits.deleteFilter(edits.filters.first().single { it.custom })
        edits.deleteLut(used)
        assertEquals(emptyList<String>(), LutStore.ids())
    }

    @Test
    fun aPhotoWhoseLutIsGoneLooksAsIfItHadNone() = runBlocking {
        val (_, raw) = library()
        val black = edits.importLut(("LUT_3D_SIZE 2\n" + "0 0 0\n".repeat(8)).byteInputStream(), "Black")
        val plain = greenOf(edits.shareCopy(raw, 2048))
        edits.apply(raw, PhotoEdit(lut = LutLook(black.hash)))
        assertEquals(0, greenOf(edits.shareCopy(container.database.mediaDao().byId(raw.id)!!, 2048)))
        assertTrue(plain > 20)

        LutStore.delete(black.hash)

        assertEquals(plain, greenOf(edits.shareCopy(container.database.mediaDao().byId(raw.id)!!, 2048)))
    }
}
