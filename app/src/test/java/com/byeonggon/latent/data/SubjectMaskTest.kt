package com.byeonggon.latent.data

import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.EditVersionEntity
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.DetectedKind
import com.byeonggon.latent.enhance.Detection
import com.byeonggon.latent.enhance.LinearImage
import com.byeonggon.latent.enhance.LocalEdit
import com.byeonggon.latent.enhance.Mask
import com.byeonggon.latent.enhance.MaskRaster
import com.byeonggon.latent.enhance.MaskShape
import com.byeonggon.latent.enhance.MaskStore
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.enhance.Segmentation
import com.byeonggon.latent.enhance.Segmenter
import com.byeonggon.latent.enhance.SegmenterException
import com.byeonggon.latent.enhance.ShownPicture
import com.byeonggon.latent.enhance.SubjectMasks
import com.byeonggon.latent.enhance.render
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Subject masks from a model to a rendered picture, with a stand-in for the model: the real one is native
 * code, which only runs on a phone (see SubjectMaskOnDeviceTest there).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubjectMaskTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer

    /** A model that sees as its subject whatever is bright, on a coarse grid of its own, as a real one would. */
    private class BrightIsSubject(private val grid: Int = 32) : Segmenter {
        override val model = "bright"
        override val modelVersion = 7
        var calls = 0

        override fun segment(picture: ShownPicture): Segmentation {
            calls++
            val values = FloatArray(grid * grid) { cell ->
                val x = ((cell % grid + 0.5f) / grid * picture.width).toInt()
                val y = ((cell / grid + 0.5f) / grid * picture.height).toInt()
                if (picture.green[y * picture.width + x] > 0.5f) 0.97f else 0.02f
            }
            return Segmentation(grid, grid, values)
        }
    }

    private class Answering(private val answer: () -> Segmentation) : Segmenter {
        override val model = "fixed"
        override val modelVersion = 1
        override fun segment(picture: ShownPicture) = answer()
    }

    /** A bright disc, the subject, on a dark ground, 160 by 120. */
    private val disc = LinearImage.fromArgb(
        IntArray(160 * 120) { index ->
            val inside = hypot(index % 160 - 70f, index / 160 - 60f) < 30f
            (0xFF shl 24) or (if (inside) 0xD8D8D8 else 0x303030)
        },
        160,
        120,
    )

    private fun green(pixel: Int) = (pixel ushr 8) and 0xFF

    @Test
    fun findsTheSubjectKeepsItsOutlineAndEditsOnlyIt() = runBlocking {
        val model = BrightIsSubject()
        val detector = MaskDetector { model }

        val found = detector.detect(disc, DetectedKind.SUBJECT) as DetectionResult.Found

        assertEquals(Detection(DetectedKind.SUBJECT, found.detection.raster, "bright", 7), found.detection)
        assertTrue(File(RuntimeEnvironment.getApplication().filesDir, "masks/${found.detection.raster}.mask").isFile)
        val outline = MaskStore.load(found.detection.raster)!!
        // The whole picture, upright, at its own size: it is smaller than an outline is kept at.
        assertEquals(160, outline.width)
        assertEquals(120, outline.height)
        val coverage = outline.coverage()
        assertTrue(coverage[60 * 160 + 70] > 0.95f)
        assertTrue(coverage[60 * 160 + 82] > 0.9f)
        assertTrue(coverage[10 * 160 + 10] < 0.05f)
        assertTrue(coverage[60 * 160 + 130] < 0.05f)

        val darker = Adjustments(exposure = -1f)
        val subject = Mask(MaskShape.DETECTED, 0f, 0f, 0f, 0f, feather = 0f, detection = found.detection)
        val untouched = disc.render(PhotoEdit())
        val onSubject = disc.render(PhotoEdit(locals = listOf(LocalEdit(subject, darker))))
        assertTrue(green(onSubject[60 * 160 + 70]) < green(untouched[60 * 160 + 70]) - 40)
        assertEquals(green(untouched[10 * 160 + 10]), green(onSubject[10 * 160 + 10]))

        val onBackground = disc.render(PhotoEdit(locals = listOf(LocalEdit(subject.copy(inverted = true), darker))))
        assertEquals(green(untouched[60 * 160 + 70]), green(onBackground[60 * 160 + 70]))
        assertTrue(green(onBackground[10 * 160 + 10]) < green(untouched[10 * 160 + 10]) - 10)
        assertEquals(1, model.calls)
    }

    @Test
    fun sharpensTheModelsEdgeAlongThePicturesOwn() {
        // Dark on the left, bright from column 576 on; the subject is the bright part.
        val width = 960
        val height = 120
        val edge = 576
        val shade = FloatArray(width * height) { if (it % width < edge) 0.2f else 0.8f }
        val shown = ShownPicture(width, height, shade, shade, shade)
        // The model's answer is coarse, 320 cells across as U2-Netp's, with the edge in the right place but
        // spread over one of its cells, three columns of the picture: stretched over the picture, it is soft.
        val grid = 320
        val coarse = Segmentation(
            grid,
            1,
            FloatArray(grid) { cell ->
                val x = (cell + 0.5f) * width / grid
                ((x - (edge - 1.5f)) / 3f).coerceIn(0f, 1f)
            },
        )
        val truth = FloatArray(width) { if (it < edge) 0f else 1f }

        val refined = SubjectMasks.refined(shown, coarse)!!
        val stretched = com.byeonggon.latent.enhance.resampled(coarse.values, grid, 1, width, height)

        val row = 60 * width
        var before = 0f
        var after = 0f
        for (x in edge - 20 until edge + 20) {
            before += abs(stretched[row + x] - truth[x])
            after += abs(refined[row + x] - truth[x])
        }
        val profile = (edge - 6..edge + 12).joinToString { "%.2f/%.2f".format(stretched[row + it], refined[row + it]) }
        assertTrue("before $before, after $after: $profile", after < before * 0.4f)
        // Half way up where the picture's edge is.
        assertTrue(profile, refined[row + edge - 1] < 0.5f && refined[row + edge] > 0.5f)
        // Away from the edge nothing changes.
        assertEquals(0f, refined[row + 100], 0.01f)
        assertEquals(1f, refined[row + 900], 0.01f)
    }

    @Test
    fun saysWhenNothingStandsOut() = runBlocking {
        val detector = MaskDetector { Answering { Segmentation(4, 4, FloatArray(16) { 0.05f + it * 0.001f }) } }

        assertEquals(DetectionResult.NothingFound, detector.detect(disc, DetectedKind.SUBJECT))
        assertTrue(MaskStore.kept().isEmpty())
    }

    @Test
    fun aPictureThatIsAllSubjectIsCoveredWhole() = runBlocking {
        val detector = MaskDetector { Answering { Segmentation(4, 4, FloatArray(16) { 0.9f + it * 0.005f }) } }

        val found = detector.detect(disc, DetectedKind.SUBJECT) as DetectionResult.Found

        assertTrue(MaskStore.load(found.detection.raster)!!.coverage().all { it > 0.85f })
    }

    @Test
    fun saysWhenTheModelFailsAndTriesAgainNextTime() = runBlocking {
        var made = 0
        val detector = MaskDetector {
            made++
            Answering { throw SegmenterException("no") }
        }

        assertEquals(DetectionResult.Failed, detector.detect(disc, DetectedKind.SUBJECT))
        assertEquals(DetectionResult.Failed, detector.detect(disc, DetectedKind.SUBJECT))
        assertEquals(2, made)
        assertTrue(detector.unavailable.value.isEmpty())
    }

    @Test
    fun isUnavailableWhereTheModelCannotRunAtAll() = runBlocking {
        val detector = MaskDetector { throw UnsatisfiedLinkError("no LiteRt here") }

        assertEquals(DetectionResult.Unavailable, detector.detect(disc, DetectedKind.SUBJECT))
        assertEquals(setOf(DetectedKind.SUBJECT), detector.unavailable.value)
    }

    @Test
    fun keepsTheOtherKindWhereOnlyOneModelCannotRun() = runBlocking {
        val detector = MaskDetector { kind -> if (kind == DetectedKind.SKY) throw UnsatisfiedLinkError("no sky here") else BrightIsSubject() }

        assertEquals(DetectionResult.Unavailable, detector.detect(disc, DetectedKind.SKY))
        assertTrue(detector.detect(disc, DetectedKind.SUBJECT) is DetectionResult.Found)
        assertEquals(setOf(DetectedKind.SKY), detector.unavailable.value)
    }

    /** Sky above row 40 of 120, sure of it; the ground below, sure it is none. */
    private class SkyAbove(private val grid: Int = 16) : Segmenter {
        override val model = "sky"
        override val modelVersion = 3
        var calls = 0

        override fun segment(picture: ShownPicture): Segmentation {
            calls++
            return Segmentation(grid, grid, FloatArray(grid * grid) { cell -> if ((cell / grid + 0.5f) / grid < 1f / 3) 0.96f else 0.01f })
        }
    }

    @Test
    fun findsTheSkyWithAModelOfItsOwn() = runBlocking {
        val sky = SkyAbove()
        val subject = BrightIsSubject()
        val asked = ArrayList<DetectedKind>()
        val detector = MaskDetector { kind -> asked += kind; if (kind == DetectedKind.SKY) sky else subject }

        val found = detector.detect(disc, DetectedKind.SKY) as DetectionResult.Found
        detector.detect(disc, DetectedKind.SKY)
        detector.detect(disc, DetectedKind.SUBJECT)

        assertEquals(Detection(DetectedKind.SKY, found.detection.raster, "sky", 3), found.detection)
        // Each kind's model is made once and kept.
        assertEquals(listOf(DetectedKind.SKY, DetectedKind.SUBJECT), asked)
        assertEquals(2, sky.calls)
        assertEquals(1, subject.calls)
        val coverage = MaskStore.load(found.detection.raster)!!.coverage()
        assertTrue(coverage[10 * 160 + 80] > 0.9f)
        assertTrue(coverage[100 * 160 + 80] < 0.1f)
    }

    @Test
    fun takesTheSkysProbabilityAsItIsWithoutStretchingIt() = runBlocking {
        // Unsure everywhere, at most 0.5: a sky that is not stretched to a sure one, as a subject would be.
        val unsure = MaskDetector { Answering { Segmentation(4, 4, FloatArray(16) { if (it < 4) 0.5f else 0.1f }) } }

        val found = unsure.detect(disc, DetectedKind.SKY) as DetectionResult.Found

        assertTrue(MaskStore.load(found.detection.raster)!!.coverage().all { it < 0.6f })
    }

    @Test
    fun saysWhenThePictureHasNoSky() = runBlocking {
        // A scrap of sky, 0.25 at most: below what counts, where a subject that faint would have been stretched to a whole one.
        val detector = MaskDetector { Answering { Segmentation(4, 4, FloatArray(16) { if (it == 0) 0.25f else 0.01f }) } }

        assertEquals(DetectionResult.NothingFound, detector.detect(disc, DetectedKind.SKY))
        assertTrue(MaskStore.kept().isEmpty())
    }

    @Test
    fun forgetsOutlinesNothingUsesADayAfterTheyWereMade() = runBlocking {
        val now = System.currentTimeMillis()
        val dayAgo = now - 25 * 60 * 60 * 1000L
        fun keep(id: String, made: Long) {
            MaskStore.save(id, MaskRaster.of(2, 2, FloatArray(4)))
            MaskStore.fileOf(id)!!.setLastModified(made)
        }
        val edited = "a".repeat(32)
        val versioned = "b".repeat(32)
        val unused = "c".repeat(32)
        val fresh = "d".repeat(32)
        listOf(edited, versioned, unused).forEach { keep(it, dayAgo) }
        keep(fresh, now)
        fun editUsing(id: String) =
            PhotoEdit(locals = listOf(LocalEdit(Mask(MaskShape.DETECTED, 0f, 0f, 0f, 0f, detection = Detection(DetectedKind.SUBJECT, id, "u2netp", 1))))).toJson()
        val dao = container.database.mediaDao()
        val photo = dao.insert(MediaItemEntity(path = "/x/a.jpg", fileName = "a.jpg", kind = MediaKind.PHOTO, sizeBytes = 1, capturedAt = 1, importedAt = 1, edit = editUsing(edited)))
        container.database.editVersionDao().insert(EditVersionEntity(mediaId = photo, name = "v", edit = editUsing(versioned), createdAt = 1))

        container.libraryEdits.forgetUnusedMasks(now)

        assertEquals(setOf(edited, versioned, fresh), MaskStore.kept().keys)
        assertEquals(1, container.database.editVersionDao().observeFor(photo).first().size)
    }
}
