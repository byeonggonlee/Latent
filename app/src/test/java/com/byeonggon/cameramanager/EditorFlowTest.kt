package com.byeonggon.cameramanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.enhance.Adjustments
import com.byeonggon.cameramanager.enhance.Filters
import com.byeonggon.cameramanager.enhance.Framing
import com.byeonggon.cameramanager.enhance.MaskShape
import com.byeonggon.cameramanager.enhance.MixerBand
import com.byeonggon.cameramanager.enhance.PhotoEdit
import com.byeonggon.cameramanager.enhance.Tint
import com.byeonggon.cameramanager.raw.SyntheticArw
import com.byeonggon.cameramanager.ui.editor.CropLayout
import com.byeonggon.cameramanager.ui.editor.EditorViewModel
import com.byeonggon.cameramanager.ui.editor.MaskLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/** Edits photos through the real screens: open one from the library, move a slider, save, and take it back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorFlowTest {
    private lateinit var photo: File
    private lateinit var raw: File
    private var photoId = 0L
    private var rawId = 0L

    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val mediaDao get() = container.database.mediaDao()

    // Runs before the activity starts, so the library already holds the photos when it first shows.
    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            photo = File(folder, "DSC00001.JPG")
            val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
            for (y in 0 until 64) for (x in 0 until 96) {
                val level = 60 + x + y / 2
                bitmap.setPixel(x, y, (0xFF shl 24) or (level shl 16) or (level shl 8) or level)
            }
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            raw = File(folder, "DSC00002.ARW").apply { writeBytes(SyntheticArw.file(red = 600, green = 900, blue = 700)) }
            runBlocking {
                photoId = mediaDao.insert(item(photo, MediaKind.PHOTO, capturedAt = 2_000))
                rawId = mediaDao.insert(item(raw, MediaKind.RAW, capturedAt = 1_000))
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun item(file: File, kind: MediaKind, capturedAt: Long) = MediaItemEntity(
        path = file.absolutePath,
        fileName = file.name,
        kind = kind,
        sizeBytes = file.length(),
        capturedAt = capturedAt,
        importedAt = 3_000,
    )

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openEditor(fileName: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(fileName).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(fileName).performClick()
        openEditorFromViewer()
    }

    private fun openEditorFromViewer() {
        waitForText("보정")
        compose.onNodeWithText("보정").performClick()
        // The sliders come alive once the picture has been read and drawn.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("노출").fetchSemanticsNodes()
                .any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
        }
    }

    /** The filter called [name], scrolled into view: only the filters on screen exist at any one time. */
    private fun filter(name: String): SemanticsNodeInteraction {
        compose.onNodeWithContentDescription("필터 목록").performScrollToNode(hasText(name))
        return compose.onNodeWithText(name)
    }

    private fun slide(control: String, to: Float) =
        compose.onNodeWithContentDescription(control).performSemanticsAction(SemanticsActions.SetProgress) { it(to) }

    @Test
    fun editsAPhotoInPlaceAndTakesTheEditBackAgain() {
        val original = photo.readBytes()
        val backup = container.storage.originalOf(photo)

        openEditor("DSC00001.JPG")
        compose.onNodeWithText("저장").assertIsNotEnabled()
        slide("노출", to = 1f)
        compose.onNodeWithText("+1.00").assertIsDisplayed()
        compose.onNodeWithText("색상").performClick()
        slide("채도", to = -0.5f)
        compose.onNodeWithText("-50").assertIsDisplayed()
        compose.onNodeWithText("누르고 있으면 원본").assertIsDisplayed()
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        waitForText("원본으로 되돌리기")
        assertArrayEquals(original, backup.readBytes())
        assertFalse(photo.readBytes().contentEquals(original))
        val stored = runBlocking { mediaDao.byId(photoId)!! }
        assertEquals(PhotoEdit(Adjustments(exposure = 1f, saturation = -0.5f)), PhotoEdit.fromJson(stored.edit))
        assertEquals(photo.length(), stored.sizeBytes)

        compose.onNodeWithText("원본으로 되돌리기").performClick()
        compose.onNodeWithText("되돌리기").performClick()

        compose.waitUntil(timeoutMillis = 10_000) { !backup.exists() }
        assertArrayEquals(original, photo.readBytes())
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { mediaDao.byId(photoId)!!.edit } == null }
    }

    @Test
    fun reopensAnEditWhereItWasLeft() {
        openEditor("DSC00001.JPG")
        slide("대비", to = 0.4f)
        compose.onNodeWithText("저장").performClick()
        waitForText("원본으로 되돌리기")

        openEditorFromViewer()
        compose.onNodeWithText("+40").assertIsDisplayed()
        compose.onNodeWithText("저장").assertIsNotEnabled()

        // Putting everything back to zero and saving leaves the photo as the camera made it.
        compose.onNodeWithContentDescription("더 보기").performClick()
        compose.onNodeWithText("초기화").performClick()
        compose.onNodeWithText("저장").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { mediaDao.byId(photoId)!!.edit } == null }
        assertFalse(container.storage.originalOf(photo).exists())
    }

    @Test
    fun asksBeforeThrowingAwayUnsavedChanges() {
        openEditor("DSC00001.JPG")
        slide("노출", to = -1f)

        compose.onNodeWithContentDescription("닫기").performClick()
        compose.onNodeWithText("저장하지 않고 닫을까요?").assertIsDisplayed()
        compose.onNodeWithText("계속 보정").performClick()
        compose.onNodeWithText("-1.00").assertIsDisplayed()

        compose.onNodeWithContentDescription("닫기").performClick()
        compose.onNodeWithText("닫기").performClick()

        waitForText("보정")
        assertFalse(container.storage.originalOf(photo).exists())
        assertNull(runBlocking { mediaDao.byId(photoId)!!.edit })
    }

    @Test
    fun storesTheSettingsOfARawFileAndLeavesTheFileItselfAlone() {
        val original = raw.readBytes()

        openEditor("DSC00002.ARW")
        slide("어두운 영역", to = 0.5f)
        compose.onNodeWithText("자르기").performClick()
        compose.onNodeWithText("렌즈 보정").assertIsDisplayed()
        compose.onNodeWithText("저장").performClick()

        waitForText("원본으로 되돌리기")
        compose.onNodeWithText("JPEG로 저장").assertIsDisplayed()
        assertArrayEquals(original, raw.readBytes())
        val stored = PhotoEdit.fromJson(runBlocking { mediaDao.byId(rawId)!!.edit })
        assertEquals(PhotoEdit(Adjustments(shadows = 0.5f)), stored)
        assertTrue(stored!!.lensCorrection)
    }

    @Test
    fun cutsAndTurnsAPhoto() {
        openEditor("DSC00001.JPG")
        compose.onNodeWithText("자르기").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("자를 영역").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("1:1").performClick()
        compose.onNodeWithText("90° 회전").performClick()

        // Pull the bottom right corner of the kept square a little way towards its middle.
        val canvas = compose.onNodeWithContentDescription("자를 영역").fetchSemanticsNode().size
        val framing = Framing.largest(1, 0f, 1f, 96f, 64f)
        val margin = with(compose.density) { 20.dp.toPx() }
        val kept = CropLayout(Size(canvas.width.toFloat(), canvas.height.toFloat()), margin, framing, 96f, 64f).kept
        compose.onNodeWithContentDescription("자를 영역").performTouchInput {
            down(Offset(kept.right - 4f, kept.bottom - 4f))
            repeat(10) { moveBy(Offset(-kept.width / 40, -kept.height / 40)) }
            up()
        }
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        waitForText("원본으로 되돌리기")
        val stored = PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit })!!.framing
        assertEquals(1, stored.quarterTurns)
        // The square shrank from its corner and kept its shape: on its side the picture is 64 wide and 96 high.
        assertEquals(0f, stored.left, 1e-3f)
        assertTrue("kept ${stored.width} of the width", stored.width in 0.6f..0.9f)
        assertEquals(stored.width * 64, stored.height * 96, 0.5f)
        val saved = BitmapFactory.decodeFile(photo.path)
        assertEquals(saved.width, saved.height)
        assertTrue("${saved.width} pixels wide", saved.width in 38..58)
    }

    @Test
    fun givesTheTonesOfOnePhotoToAnother() {
        openEditor("DSC00001.JPG")
        slide("노출", to = 0.5f)
        compose.onNodeWithContentDescription("더 보기").performClick()
        compose.onNodeWithText("보정 값 붙여넣기").assertIsNotEnabled()
        compose.onNodeWithText("보정 값 복사").performClick()
        compose.onNodeWithText("저장").performClick()
        waitForText("원본으로 되돌리기")
        compose.onNodeWithContentDescription("닫기").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("DSC00002.ARW").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("DSC00002.ARW").performClick()
        waitForText("보정 값 붙여넣기")
        compose.onNodeWithText("보정 값 붙여넣기").performClick()

        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { mediaDao.byId(rawId)!!.edit } != null }
        assertEquals(PhotoEdit(Adjustments(exposure = 0.5f)), PhotoEdit.fromJson(runBlocking { mediaDao.byId(rawId)!!.edit }))
    }

    @Test
    fun showsTheOriginalWhileThePictureIsHeld() {
        openEditor("DSC00001.JPG")
        slide("노출", to = 1f)

        compose.onNodeWithContentDescription("DSC00001.JPG").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("원본").assertIsDisplayed()

        compose.onNodeWithContentDescription("DSC00001.JPG").performTouchInput { up() }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("원본").fetchSemanticsNodes().isEmpty() }
        // A short tap is no hold.
        compose.onNodeWithContentDescription("DSC00001.JPG").performClick()
        compose.onAllNodesWithText("원본").assertCountEquals(0)
    }

    @Test
    fun undoesAndRedoesOneChangeAtATime() {
        openEditor("DSC00001.JPG")
        compose.onNodeWithContentDescription("실행 취소").assertIsNotEnabled()
        slide("노출", to = 1f)
        slide("대비", to = 0.5f)

        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.onNodeWithText("+1.00").assertIsDisplayed()
        compose.onAllNodesWithText("+50").assertCountEquals(0)

        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.onAllNodesWithText("+1.00").assertCountEquals(0)
        compose.onNodeWithContentDescription("실행 취소").assertIsNotEnabled()
        compose.onNodeWithText("저장").assertIsNotEnabled()

        compose.onNodeWithContentDescription("다시 실행").performClick()
        compose.onNodeWithContentDescription("다시 실행").performClick()
        compose.onNodeWithText("+50").assertIsDisplayed()
        compose.onNodeWithContentDescription("다시 실행").assertIsNotEnabled()
    }

    @Test
    fun shapesTheToneCurveByDraggingAPointOnIt() {
        openEditor("DSC00001.JPG")
        compose.onNodeWithText("곡선").performClick()

        // Take hold of the middle of the curve and pull it up.
        compose.onNodeWithContentDescription("톤 곡선").performTouchInput {
            down(center)
            moveBy(Offset(0f, -height / 4f))
            up()
        }
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        waitForText("원본으로 되돌리기")
        val curve = PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit })!!.curves.all
        assertEquals(3, curve.points.size)
        assertEquals(0.5f, curve.points[1].x, 0.02f)
        // A quarter of the graph's height, or a little more: the graph is slightly smaller than the area it sits in.
        assertTrue("lifted to ${curve.points[1].y}", curve.points[1].y in 0.72f..0.82f)
    }

    @Test
    fun editsSingleColoursAndTurnsThePictureBlackAndWhite() {
        openEditor("DSC00001.JPG")
        compose.onNodeWithText("색상").performClick()
        compose.onNodeWithText("흑백").performClick()
        compose.onNodeWithText("색상 혼합").performClick()
        compose.onNodeWithContentDescription("파랑").performClick()
        slide("파랑 광도", to = -0.4f)
        compose.onNodeWithText("색상 등급").performClick()
        slide("색", to = 210f)
        slide("양", to = 0.5f)
        compose.onNodeWithText("저장").performClick()

        waitForText("원본으로 되돌리기")
        val stored = PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit })!!
        assertTrue(stored.monochrome)
        assertEquals(-0.4f, stored.mixer.luminance[MixerBand.BLUE.ordinal], 1e-6f)
        assertEquals(Tint(210f, 0.5f), stored.grading.shadows)
    }

    @Test
    fun showsAPartAtFullSizeForJudgingDetail() {
        openEditor("DSC00002.ARW")
        compose.onNodeWithText("세부").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("100% 보기").fetchSemanticsNodes().isNotEmpty()
        }
        slide("선명하게", to = 0.6f)
        compose.onNodeWithText("60").assertIsDisplayed()
        compose.onNodeWithContentDescription("히스토그램").assertIsDisplayed()
    }

    @Test
    fun givesAPhotoTheLookOfAFilterAndDosesIt() {
        val film = Filters.BUILT_IN.first { it.name == "필름" }

        openEditor("DSC00001.JPG")
        compose.onNodeWithText("필터").performClick()
        filter("필름").performClick()
        slide("강도", to = 0.5f)
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        waitForText("원본으로 되돌리기")
        assertEquals(PhotoEdit().withLookOf(film.look.lookAt(0.5f)), PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit }))
    }

    @Test
    fun keepsALookAsAFilterOfOnesOwn() {
        openEditor("DSC00001.JPG")
        slide("노출", to = 0.5f)
        compose.onNodeWithText("필터").performClick()
        compose.onNodeWithText("지금 보정을 내 필터로 저장").assertIsEnabled()
        // The name is asked for in a text field, whose blinking cursor never lets a test come to rest;
        // the name is handed over directly instead.
        compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java].saveFilter("저녁 빛") }

        // Taking every look away and choosing the new filter brings the look back.
        compose.onNodeWithText("없음").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { container.database.filterDao().observeAll().first() }.isNotEmpty() }
        filter("저녁 빛").performClick()
        compose.onNodeWithText("저장").performClick()
        waitForText("원본으로 되돌리기")
        assertEquals(PhotoEdit(Adjustments(exposure = 0.5f)), PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit }))

        // Held down, a filter of one's own can be deleted; the ones that come with the app cannot.
        openEditorFromViewer()
        compose.onNodeWithText("필터").performClick()
        filter("저녁 빛").performTouchInput { longClick() }
        compose.onNodeWithText("지우기").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { container.database.filterDao().observeAll().first() }.isEmpty() }
        filter("필름").performTouchInput { longClick() }
        compose.onAllNodesWithText("지우기").assertCountEquals(0)
    }

    @Test
    fun editsAPartOfThePictureUnderAMask() {
        openEditor("DSC00001.JPG")
        compose.onNodeWithText("부분").performScrollTo().performClick()
        compose.onNodeWithText("+ 선형").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("마스크 영역").fetchSemanticsNodes().isNotEmpty()
        }
        slide("노출", to = -1f)

        // Pull the point where the mask has faded out further down the picture.
        val canvas = compose.onNodeWithContentDescription("마스크 영역").fetchSemanticsNode().size
        val margin = with(compose.density) { 20.dp.toPx() }
        val layout = MaskLayout(Size(canvas.width.toFloat(), canvas.height.toFloat()), margin, 96f, 64f)
        val end = layout.onCanvas(0.5f, 0.6f)
        compose.onNodeWithContentDescription("마스크 영역").performTouchInput {
            down(end)
            repeat(10) { moveBy(Offset(0f, layout.picture.height / 50)) }
            up()
        }
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        waitForText("원본으로 되돌리기")
        val local = PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit })!!.locals.single()
        assertEquals(MaskShape.LINEAR, local.mask.shape)
        assertEquals(Adjustments(exposure = -1f), local.adjustments)
        assertEquals(0.25f, local.mask.startY, 1e-6f)
        assertTrue("faded out at ${local.mask.endY}", local.mask.endY in 0.7f..0.85f)
        // The top of the saved picture is darker than it was, the bottom is as it was.
        val saved = BitmapFactory.decodeFile(photo.path)
        val before = BitmapFactory.decodeFile(container.storage.originalOf(photo).path)
        assertTrue((saved.getPixel(48, 4) and 0xFF) < (before.getPixel(48, 4) and 0xFF) - 15)
        assertEquals((before.getPixel(48, 60) and 0xFF).toDouble(), (saved.getPixel(48, 60) and 0xFF).toDouble(), 3.0)
    }

    @Test
    fun mirrorsAndTiltsAPhoto() {
        openEditor("DSC00001.JPG")
        compose.onNodeWithText("자르기").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("자를 영역").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("좌우 반전").performClick()
        slide("수직 원근", to = 0.5f)
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        waitForText("원본으로 되돌리기")
        val stored = PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit })!!.framing
        assertTrue(stored.flipped)
        assertEquals(0.5f, stored.vertical, 1e-6f)
        // Tilting took the corners away, so less than the whole picture is kept, in the shape it had.
        assertTrue(stored.fits(96f, 64f))
        assertTrue("kept ${stored.width}", stored.width in 0.5f..0.95f)
        val saved = BitmapFactory.decodeFile(photo.path)
        assertEquals(1.5, saved.width.toDouble() / saved.height, 0.05)
        // The picture gets brighter from left to right; mirrored, its bright side is on the left.
        assertTrue((saved.getPixel(2, saved.height / 2) and 0xFF) > (saved.getPixel(saved.width - 3, saved.height / 2) and 0xFF) + 20)
    }

    @Test
    fun doesTheSameToSeveralPhotosAtOnce() {
        container.libraryEdits.copied.value = PhotoEdit(Adjustments(exposure = 0.5f))
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("DSC00001.JPG").fetchSemanticsNodes().isNotEmpty()
        }

        // Holding one photo starts the selection; after that a tap adds another.
        compose.onNodeWithContentDescription("DSC00001.JPG").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("DSC00002.ARW").performClick()
        waitForText("✕ 2장 선택")
        compose.onNodeWithText("보정 값 붙여넣기").performClick()

        waitForText("2장에 보정 값을 붙여넣었습니다.")
        assertTrue(container.storage.originalOf(photo).exists())
        assertEquals(PhotoEdit(Adjustments(exposure = 0.5f)), PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit }))
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("✕ 2장 선택").fetchSemanticsNodes().isEmpty() }
        assertEquals(PhotoEdit(Adjustments(exposure = 0.5f)), PhotoEdit.fromJson(runBlocking { mediaDao.byId(rawId)!!.edit }))

        compose.onNodeWithContentDescription("DSC00001.JPG").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("DSC00002.ARW").performClick()
        compose.onNodeWithText("별점").performClick()
        compose.onNodeWithText("★★★★").performClick()
        waitForText("2장에 별점을 매겼습니다.")
        assertEquals(listOf(4, 4), runBlocking { listOf(mediaDao.byId(rawId)!!.rating, mediaDao.byId(photoId)!!.rating) })

        // Only raw files are developed; the JPEG in the selection is left out.
        compose.onNodeWithContentDescription("DSC00001.JPG").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("DSC00002.ARW").performClick()
        compose.onNodeWithText("RAW를 JPEG로").performClick()
        waitForText("1장에 JPEG로 내보냈습니다.")
        assertEquals(3, runBlocking { mediaDao.all() }.size)
        val developed = runBlocking { mediaDao.all() }.single { it.fileName == "DSC00002_developed.jpg" }
        assertEquals(MediaKind.PHOTO, developed.kind)
        assertNull(developed.edit)
        assertTrue(File(developed.path).length() > 0)
    }

    @Test
    fun takesOutABlemishWhereThePictureIsTouched() {
        openEditor("DSC00001.JPG")
        compose.onNodeWithText("잡티").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("잡티 영역").fetchSemanticsNodes().isNotEmpty()
        }

        val canvas = compose.onNodeWithContentDescription("잡티 영역").fetchSemanticsNode().size
        val margin = with(compose.density) { 20.dp.toPx() }
        val layout = MaskLayout(Size(canvas.width.toFloat(), canvas.height.toFloat()), margin, 96f, 64f)
        compose.onNodeWithContentDescription("잡티 영역").performTouchInput { click(layout.onCanvas(0.4f, 0.5f)) }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("선택한 잡티 되돌리기").fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
        }
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        waitForText("원본으로 되돌리기")
        val spot = PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit })!!.spots.single()
        assertEquals(0.4f, spot.x, 0.02f)
        assertEquals(0.5f, spot.y, 0.02f)
        assertTrue(spot.fromX != spot.x || spot.fromY != spot.y)
    }

    @Test
    fun paintsAMaskWithABrush() {
        openEditor("DSC00001.JPG")
        compose.onNodeWithText("부분").performScrollTo().performClick()
        compose.onNodeWithText("+ 브러시").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("마스크 영역").fetchSemanticsNodes().isNotEmpty()
        }
        val canvas = compose.onNodeWithContentDescription("마스크 영역").fetchSemanticsNode().size
        val margin = with(compose.density) { 20.dp.toPx() }
        val layout = MaskLayout(Size(canvas.width.toFloat(), canvas.height.toFloat()), margin, 96f, 64f)
        compose.onNodeWithContentDescription("마스크 영역").performTouchInput {
            down(layout.onCanvas(0.1f, 0.2f))
            repeat(16) { moveBy(Offset(layout.picture.width / 20, 0f)) }
            up()
        }
        slide("노출", to = 1f)
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        waitForText("원본으로 되돌리기")
        val mask = PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit })!!.locals.single().mask
        assertEquals(MaskShape.BRUSH, mask.shape)
        val stroke = mask.strokes.single()
        assertTrue("${stroke.points.size / 2} points", stroke.points.size >= 6)
        assertEquals(0.2f, stroke.points[1], 0.03f)
        assertTrue(mask.weightAt(0.5f, 0.2f, 96f, 64f) > 0.9f)
        assertTrue(mask.weightAt(0.5f, 0.8f, 96f, 64f) < 0.01f)
    }

    @Test
    fun autoSetsTheSlidersForTheUserToTakeFurther() {
        openEditor("DSC00001.JPG")
        compose.onNodeWithText("자동").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("실행 취소").fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
        }
        compose.onNodeWithText("저장").assertIsEnabled().performClick()

        waitForText("원본으로 되돌리기")
        val stored = PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit })!!
        assertFalse(stored.autoEnhance)
        assertTrue(stored.adjustments != Adjustments())
    }
}
