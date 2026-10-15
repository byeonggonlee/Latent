package com.byeonggon.latent.enhance

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Masks of what a model found, kept as outlines in files of their own, through the processor's renderer. */
class DetectedMaskTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var directory: File

    @Before
    fun keepOutlinesInAFolderOfTheirOwn() {
        directory = folder.newFolder("masks")
        MaskStore.directory = directory
    }

    @After
    fun forgetThem() {
        MaskStore.directory = null
    }

    private val leftHalf = "0123456789abcdef0123456789abcdef"

    /** An outline 200 by 100 cells that covers the left half of the picture. */
    private fun keepLeftHalf(id: String = leftHalf): Detection {
        MaskStore.save(id, MaskRaster.of(200, 100, FloatArray(200 * 100) { if (it % 200 < 100) 1f else 0f }))
        return Detection(DetectedKind.SUBJECT, id, "test", 1)
    }

    private fun detected(detection: Detection, inverted: Boolean = false, feather: Float = 0f, strokes: List<BrushStroke> = emptyList()) =
        Mask(MaskShape.DETECTED, 0f, 0f, 0f, 0f, feather = feather, inverted = inverted, strokes = strokes, detection = detection)

    private val flat = LinearImage.fromArgb(IntArray(120 * 80) { (0xFF shl 24) or 0x646464 }, 120, 80)

    private fun green(pixel: Int) = (pixel ushr 8) and 0xFF

    @Test
    fun brightensTheSubjectOrEverythingElse() {
        val subject = keepLeftHalf()
        val brighter = Adjustments(exposure = 1f)

        val onSubject = flat.render(PhotoEdit(locals = listOf(LocalEdit(detected(subject), brighter))))
        assertTrue(green(onSubject[40 * 120 + 20]) > 130)
        assertEquals(100, green(onSubject[40 * 120 + 100]))

        val onBackground = flat.render(PhotoEdit(locals = listOf(LocalEdit(detected(subject, inverted = true), brighter))))
        assertEquals(100, green(onBackground[40 * 120 + 20]))
        assertTrue(green(onBackground[40 * 120 + 100]) > 130)
    }

    @Test
    fun staysOnWhatItCoveredWhenThePictureIsCut() {
        val subject = keepLeftHalf()
        val framing = Framing(left = 0.5f)
        val edit = PhotoEdit(framing = framing, locals = listOf(LocalEdit(detected(subject), Adjustments(exposure = 1f))))

        // Only the right half is kept, where the subject is not.
        val cut = flat.framed(framing)
        assertArrayEquals(cut.render(PhotoEdit(framing = framing)), cut.render(edit))
    }

    @Test
    fun coversNothingWithoutItsOutlineInvertedOrNot() {
        val lost = Detection(DetectedKind.SUBJECT, "fedcba9876543210fedcba9876543210", "test", 1)
        val untouched = flat.render(PhotoEdit())

        for (inverted in listOf(false, true)) {
            val mask = detected(lost, inverted = inverted, strokes = listOf(BrushStroke(listOf(0.5f, 0.5f), 0.2f)))
            assertEquals(0f, mask.weightAt(0.5f, 0.5f, 120f, 80f), 0f)
            assertArrayEquals(untouched, flat.render(PhotoEdit(locals = listOf(LocalEdit(mask, Adjustments(exposure = 1f))))))
        }
    }

    @Test
    fun softensItsEdgeByTheFeather() {
        val subject = keepLeftHalf()
        // Two cells left of the edge, of 200 across.
        val nearEdge = 0.49f

        assertEquals(1f, detected(subject).weightAt(nearEdge, 0.5f, 200f, 100f), 1e-3f)
        val feathered = detected(subject, feather = 1f).weightAt(nearEdge, 0.5f, 200f, 100f)
        assertTrue("$feathered", feathered in 0.5f..0.95f)
        assertEquals(1f, detected(subject, feather = 1f).weightAt(0.2f, 0.5f, 200f, 100f), 1e-3f)
    }

    @Test
    fun theBrushAddsToWhatTheMaskCoversAndTakesFromIt() {
        val subject = keepLeftHalf()
        val across = BrushStroke(listOf(0.1f, 0.5f, 0.4f, 0.5f), radius = 0.05f)
        val wipe = BrushStroke(listOf(0.7f, 0.5f, 0.9f, 0.5f), radius = 0.05f, erasing = true)

        // Painted on the background, the brush adds to the background, whichever way it was found.
        val background = detected(subject, inverted = true, strokes = listOf(across, wipe))
        assertEquals(1f, background.weightAt(0.25f, 0.5f, 200f, 100f), 1e-3f)
        assertEquals(0f, background.weightAt(0.25f, 0.1f, 200f, 100f), 1e-3f)
        assertEquals(0f, background.weightAt(0.8f, 0.5f, 200f, 100f), 1e-3f)
        assertEquals(1f, background.weightAt(0.8f, 0.1f, 200f, 100f), 1e-3f)
    }

    @Test
    fun aDetectionIsWrittenAndReadBackWithTheEdit() {
        val detection = Detection(DetectedKind.SUBJECT, leftHalf, "u2netp", 1)
        val edit = PhotoEdit(
            locals = listOf(
                LocalEdit(detected(detection, feather = 0.2f), Adjustments(exposure = 0.5f)),
                LocalEdit(detected(detection, inverted = true, strokes = listOf(BrushStroke(listOf(0.1f, 0.2f), 0.03f, erasing = true))), Adjustments(saturation = -1f)),
            ),
        )

        val json = edit.toJson()

        assertEquals(edit, PhotoEdit.fromJson(json))
        // The edit names the outline; the outline itself is not in it.
        assertTrue(json, json.contains("\"raster\":\"$leftHalf\""))
        assertTrue(json, json.length < 1000)
    }

    @Test
    fun editsMadeBeforeDetectedMasksReadAsTheyDid() {
        val stored = """{"exposure":0.5,"locals":[{"shape":"RADIAL","mask":[0.5,0.5,0.75,0.8,0.5],"exposure":-1.0}]}"""

        val edit = PhotoEdit.fromJson(stored)!!

        assertEquals(PhotoEdit(Adjustments(exposure = 0.5f), locals = listOf(LocalEdit(Mask(MaskShape.RADIAL, 0.5f, 0.5f, 0.75f, 0.8f), Adjustments(exposure = -1f)))), edit)
        assertNull(edit.locals.single().mask.detection)
    }

    @Test
    fun aKindOfThingThisVersionDoesNotKnowCoversNothing() {
        keepLeftHalf()
        val stored = """{"locals":[{"shape":"DETECTED","mask":[0,0,0,0,0],"inverted":true,"exposure":1.0,""" +
            """"detected":{"kind":"SKY","raster":"$leftHalf","model":"later","modelVersion":3}}]}"""

        val edit = PhotoEdit.fromJson(stored)!!

        assertNull(edit.locals.single().mask.detection)
        assertArrayEquals(flat.render(PhotoEdit()), flat.render(edit))
    }

    @Test
    fun keepsOutlinesInFilesAndReadsThemBack() {
        val raster = MaskRaster.of(7, 3, FloatArray(21) { it / 20f })
        MaskStore.save(leftHalf, raster)
        // Forgets the outline at hand, so that it is read from its file.
        MaskStore.directory = directory

        val read = MaskStore.load(leftHalf)!!

        assertEquals(7, read.width)
        assertEquals(3, read.height)
        assertArrayEquals(raster.levels, read.levels)
        assertTrue(MaskStore.isKept(leftHalf))
        assertEquals(setOf(leftHalf), MaskStore.kept().keys)
        MaskStore.delete(leftHalf)
        assertNull(MaskStore.load(leftHalf))
    }

    @Test
    fun readsNoOutlineFromABrokenFileOrAnIdThatIsNone() {
        File(directory, "$leftHalf.mask").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))

        assertNull(MaskStore.load(leftHalf))
        assertNull(MaskStore.fileOf("../../databases/latent"))
        assertNull(MaskStore.load("../../databases/latent"))
    }
}
