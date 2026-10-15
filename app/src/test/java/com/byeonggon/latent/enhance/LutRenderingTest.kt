package com.byeonggon.latent.enhance

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

class LutRenderingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var directory: File

    @Before
    fun keepLutsInAFolderOfTheirOwn() {
        directory = folder.newFolder("luts")
        LutStore.directory = directory
    }

    @After
    fun forgetThem() {
        LutStore.directory = null
    }

    private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    private fun red(pixel: Int) = (pixel ushr 16) and 0xFF
    private fun green(pixel: Int) = (pixel ushr 8) and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF

    private fun picture(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int) =
        LinearImage.fromArgb(IntArray(width * height) { pixel(it % width, it / width) }, width, height)

    private val colours = picture(32, 32) { x, y -> rgb(x * 8, y * 8, 255 - (x + y) * 4) }

    private val inverted = ColorLut.of(17) { r, g, b -> floatArrayOf(1f - r, 1f - g, 1f - b) }
    private val invertedId = "00000000000000000000000000000001"

    private fun assertNear(expected: Int, actual: Int, what: String) = assertTrue("$what: $actual, not $expected", abs(expected - actual) <= 1)

    @Test
    fun takesAllOfItHalfOfItOrNoneOfIt() {
        LutStore.save(invertedId, inverted)
        val untouched = colours.render(PhotoEdit())

        val full = colours.render(PhotoEdit(lut = LutLook(invertedId)))
        val half = colours.render(PhotoEdit(lut = LutLook(invertedId, 0.5f)))
        val none = colours.render(PhotoEdit(lut = LutLook(invertedId, 0f)))

        for (index in untouched.indices) {
            for ((name, channel) in listOf<Pair<String, (Int) -> Int>>("red" to ::red, "green" to ::green, "blue" to ::blue)) {
                assertNear(255 - channel(untouched[index]), channel(full[index]), "$name of $index, inverted")
                // Half of the colour and half of its opposite: middle gray.
                assertTrue("$name of $index, half: ${channel(half[index])}", channel(half[index]) in 127..128)
            }
        }
        assertArrayEquals(untouched, none)
    }

    @Test
    fun comesAfterTheColoursAndBeforeTheVignette() {
        // Blue is taken out by the LUT; a black-and-white picture before it comes out yellowish.
        val withoutBlue = "00000000000000000000000000000002"
        LutStore.save(withoutBlue, ColorLut.of(5) { r, g, _ -> floatArrayOf(r, g, 0f) })
        val toned = colours.render(PhotoEdit(monochrome = true, lut = LutLook(withoutBlue)))
        for (pixel in toned) {
            assertEquals(0, blue(pixel))
            assertNear(red(pixel), green(pixel), "gray")
        }

        // The corners are darkened after the LUT: inverted first, they would come out lighter.
        LutStore.save(invertedId, inverted)
        val gray = picture(64, 64) { _, _ -> rgb(60, 60, 60) }
        val vignetted = gray.render(PhotoEdit(Adjustments(vignette = -1f), lut = LutLook(invertedId)))
        val centre = green(vignetted[32 * 64 + 32])
        val corner = green(vignetted[0])
        assertNear(195, centre, "centre")
        assertTrue("corner $corner, centre $centre", corner < centre - 40)
    }

    @Test
    fun aLutWhoseFileIsGoneIsNoLut() {
        val untouched = colours.render(PhotoEdit(Adjustments(contrast = 0.3f)))

        assertArrayEquals(untouched, colours.render(PhotoEdit(Adjustments(contrast = 0.3f), lut = LutLook("0000000000000000000000000000dead"))))
        // Nor is one whose file was spoiled, or that is no id at all.
        File(directory, "0000000000000000000000000000beef.lut").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertArrayEquals(untouched, colours.render(PhotoEdit(Adjustments(contrast = 0.3f), lut = LutLook("0000000000000000000000000000beef"))))
        assertArrayEquals(untouched, colours.render(PhotoEdit(Adjustments(contrast = 0.3f), lut = LutLook("../../secrets"))))

        // And without a place for LUTs at all.
        LutStore.directory = null
        assertArrayEquals(untouched, colours.render(PhotoEdit(Adjustments(contrast = 0.3f), lut = LutLook(invertedId))))
    }

    @Test
    fun keepsATableAsItWasImported() {
        val text = "TITLE \"Odd\"\nDOMAIN_MIN -0.1 0 0.2\nDOMAIN_MAX 1.1 1 0.9\nLUT_3D_SIZE 3\n" + buildString {
            for (i in 0 until 27) append("${i / 27f} ${1f - i / 30f} ${(i % 3) * 0.4f}\n")
        }
        val bytes = text.toByteArray()
        val lut = CubeLut.parse(bytes).lut
        val id = LutStore.idOf(bytes)

        assertEquals(32, id.length)
        assertEquals(id, LutStore.idOf(text.toByteArray()))
        LutStore.save(id, lut)
        // Saving the same one again changes nothing.
        LutStore.save(id, lut)
        assertEquals(listOf(id), LutStore.ids())

        // Forgetting what is at hand, so that the table is read from its file.
        LutStore.directory = directory
        val read = LutStore.load(id)!!
        assertEquals(3, read.size)
        assertArrayEquals(lut.table, read.table, 0f)
        assertArrayEquals(lut.domainMin, read.domainMin, 0f)
        assertArrayEquals(lut.domainMax, read.domainMax, 0f)
        // Read once, then kept at hand.
        assertSame(read, LutStore.load(id))

        LutStore.delete(id)
        assertNull(LutStore.load(id))
        assertEquals(emptyList<String>(), LutStore.ids())
    }
}
