package com.byeonggon.latent.enhance

import android.graphics.Bitmap
import com.byeonggon.latent.fuzz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/**
 * A stored edit comes back from a backup or a filter as text, which may be damaged or written by something else:
 * [PhotoEdit.fromJson] reads it or says it cannot (null), and what it reads can be rendered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEditFuzzTest {
    private val seed = PhotoEdit(
        adjustments = Adjustments(exposure = 0.4f, contrast = 0.2f, clarity = 0.3f, vignette = -0.2f, grain = 0.1f, sharpening = 0.4f, noiseReduction = 0.2f),
        lensCorrection = false,
        framing = Framing(quarterTurns = 1, angle = 3f, left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.85f, vertical = 0.1f),
        curves = ToneCurves(all = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f)))),
        mixer = ColorMixer(hue = List(8) { 0.1f }, saturation = List(8) { -0.1f }, luminance = List(8) { 0.05f }),
        grading = ColorGrading(shadows = Tint(220f, 0.3f), highlights = Tint(40f, 0.2f), balance = -0.2f),
        locals = listOf(
            LocalEdit(Mask(MaskShape.RADIAL, 0.5f, 0.5f, 0.8f, 0.7f, feather = 0.4f), Adjustments(exposure = 0.5f), hue = 30f),
            LocalEdit(
                Mask(MaskShape.BRUSH, 0f, 0f, 0f, 0f, strokes = listOf(BrushStroke(listOf(0.1f, 0.1f, 0.4f, 0.5f), radius = 0.05f))),
                Adjustments(saturation = -0.5f),
                darkest = 0.1f,
                brightest = 0.9f,
            ),
            LocalEdit(
                Mask(MaskShape.DETECTED, 0f, 0f, 0f, 0f, detection = Detection(DetectedKind.SUBJECT, "0123456789abcdef0123456789abcdef", "m", 1)),
                Adjustments(shadows = 0.3f),
            ),
        ),
        spots = listOf(Spot(0.3f, 0.3f, 0.05f, 0.6f, 0.6f)),
        lut = LutLook("0123456789abcdef0123456789abcdef", 0.5f),
    ).toJson()

    private val folder = File(RuntimeEnvironment.getApplication().cacheDir, "fuzz")
    private val photo = File(folder, "DSC00001.JPG")

    @Before
    fun writePhoto() {
        folder.deleteRecursively()
        folder.mkdirs()
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) for (x in 0 until 48) bitmap.setPixel(x, y, (0xFF shl 24) or ((x * 5) shl 16) or ((y * 7) shl 8) or 128)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    @Test
    fun theSeedReadsBackAsItWasWritten() {
        assertEquals(seed, PhotoEdit.fromJson(seed)!!.toJson())
    }

    @Test
    fun aDamagedEditIsReadOrIsNone() = fuzz(seed = 51, cases = 5_000) { mutator ->
        PhotoEdit.fromJson(mutator.json(seed))
    }

    @Test
    fun anEditReadFromElsewhereRenders() = fuzz(seed = 52, cases = 1_500, slowest = 5_000) { mutator ->
        val edit = PhotoEdit.fromJson(mutator.json(seed)) ?: return@fuzz
        val bitmap = try {
            PhotoEnhancer.preview(photo, edit, maxEdge = 48)
        } catch (e: IOException) {
            return@fuzz
        }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test
    fun anEditWithANumberThatIsNoneIsNone() {
        // Healing rounded a spot of NaN and threw; any NaN would spread over the picture.
        assertNull(PhotoEdit.fromJson("""{"exposure":NaN}"""))
        assertNull(PhotoEdit.fromJson("""{"spots":[[0.5,0.5,NaN,0.2,0.2]]}"""))
        assertNull(PhotoEdit.fromJson("""{"curves":{"all":[[0,0],[NaN,1]]}}"""))
        assertNull(PhotoEdit.fromJson("""{"curves":{"all":[[0,0],[Infinity,1]]}}"""))
        assertNull(PhotoEdit.fromJson("""{"curves":{"red":[[0,-Infinity],[1,1]]}}"""))
        assertNull(PhotoEdit.fromJson("""{"mixer":{"hue":[Infinity]}}"""))
        assertEquals(0.5f, PhotoEdit.fromJson("""{"exposure":0.5}""")!!.adjustments.exposure)
    }

    @Test
    fun aCropFarOutsideThePictureIsShrunkToIt() {
        // 2000 times the photo's width was a picture of that size; a million, past what an Int holds, a crash.
        val picture = LinearImage(48, 32)
        for (far in listOf(Framing(left = 0.5f, top = -3f, right = 2000f, bottom = 0.5f), Framing(right = 1e6f, bottom = 1e6f), Framing(left = -5e5f, right = 5e5f, angle = 3f))) {
            val kept = picture.framed(far)

            assertTrue("${kept.width} by ${kept.height}", kept.width <= 48 && kept.height <= 32)
            assertTrue(PhotoEnhancer.preview(photo, PhotoEdit(framing = far), maxEdge = 48).width > 0)
        }
    }

    @Test
    fun aBrushOnACropFarOutsideThePictureIsPlacedByTheCropThatWasKept() {
        // The bounds overflow a Float's width to infinity, or lie wholly outside the picture: either way the crop kept is
        // another than the edit's, and the brush has to be placed by that, not by a width of infinity or nothing.
        val brushed = PhotoEdit.fromJson("""{"locals":[{"shape":"BRUSH","mask":[0,0,0,0,0.5],"strokes":[{"radius":0.3,"points":[0.2,0.2,0.8,0.8]}],"exposure":1}]}""")!!
        assertTrue(brushed.locals.isNotEmpty())
        val framings = listOf(
            Framing(left = -3e38f, top = -3e38f, right = 3e38f, bottom = 3e38f),
            Framing(left = 1e30f, top = 1e30f, right = 3e30f, bottom = 3e30f),
            Framing(left = -1e6f, top = -1e6f, right = 1e6f, bottom = 1e6f, quarterTurns = 1),
        )
        for (far in framings) {
            val bitmap = PhotoEnhancer.preview(photo, brushed.copy(framing = far), maxEdge = 48)

            assertTrue("$far: ${bitmap.width} by ${bitmap.height}", bitmap.width in 1..48 && bitmap.height in 1..48)
        }
    }

    @Test
    fun aCropFarOutsideThePictureIsKeptAsAFramingThatIsSomething() {
        for (far in listOf(Framing(left = -3e38f, right = 3e38f, top = -3e38f, bottom = 3e38f), Framing(left = 1e30f, top = 1e30f, right = 3e30f, bottom = 3e30f), Framing(right = 1e6f, bottom = 1e6f))) {
            val kept = far.keptWithin(48f, 32f)

            assertTrue("$kept", kept.width > 0f && kept.height > 0f && kept.width.isFinite() && kept.height.isFinite())
            assertTrue("$kept", kept.fits(48f, 32f))
        }
        val reasonable = Framing(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f, angle = 2f)
        assertEquals(reasonable, reasonable.keptWithin(48f, 32f))
    }

    @Test
    fun aLevelledCropReachingPastTheFrameIsKeptAsItIs() {
        // Levelled by 45 degrees, a square reaches past its own frame along its middle: the editor can keep a strip there.
        val strip = Framing(angle = 45f, left = -0.15f, top = 0.45f, right = 1.15f, bottom = 0.55f)
        assertTrue(strip.fits(32f, 32f))
        assertEquals(strip, PhotoEdit.fromJson(PhotoEdit(framing = strip).toJson())!!.framing)

        val kept = LinearImage(32, 32).framed(strip)

        assertEquals(42 to 3, kept.width to kept.height)
    }

    @Test
    fun aStrokeWithoutAWholePointPaintsNothing() {
        for (points in listOf("[]", "[0.5]")) {
            val edit = PhotoEdit.fromJson("""{"locals":[{"shape":"BRUSH","mask":[0,0,0,0,0.5],"strokes":[{"radius":0.1,"points":$points}],"exposure":1}]}""")!!

            assertTrue(PhotoEnhancer.preview(photo, edit, maxEdge = 48).width > 0)
        }
    }
}
