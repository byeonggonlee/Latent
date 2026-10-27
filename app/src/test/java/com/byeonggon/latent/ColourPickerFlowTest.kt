package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.enhance.Framing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import com.byeonggon.latent.ui.editor.EditorScreen
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.LocalEdit
import com.byeonggon.latent.enhance.Mask
import com.byeonggon.latent.enhance.MaskShape
import com.byeonggon.latent.ui.editor.EditorView
import com.byeonggon.latent.ui.editor.EditorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColourPickerFlowTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private var photoId = 0L
    private var otherId = 0L
    private var previousSettings = com.byeonggon.latent.data.AppSettings()
    @get:Rule(order = 0) val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            previousSettings = runBlocking { container.settings.settings.first() }
            val folder = File(container.storage.root, "2026/2026-10-27").apply { mkdirs() }
            for (name in listOf("PICK.JPG", "OTHER.JPG")) {
                val file = File(folder, name)
                val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
                for (y in 0 until 64) for (x in 0 until 96) bitmap.setPixel(x, y, if (x < 48) 0xff4488cc.toInt() else 0xff888888.toInt())
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                bitmap.recycle()
                val item = MediaItemEntity(path = file.path, fileName = name, kind = MediaKind.PHOTO, sizeBytes = file.length(), capturedAt = 1, importedAt = 1)
                val id = runBlocking { container.database.mediaDao().insert(item) }
                if (name == "PICK.JPG") photoId = id else otherId = id
            }
        }
        override fun after() = runBlocking { container.settings.update { previousSettings } }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun open(): EditorViewModel {
        lateinit var model: EditorViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java]; model.open(photoId) }
        compose.waitUntil(10_000) { compose.waitForIdle(); model.state.value.preview != null && !model.state.value.loading }
        compose.runOnIdle {
            model.setLocals(listOf(LocalEdit(Mask(MaskShape.EVERYWHERE, 0f, 0f, 1f, 1f), Adjustments(exposure = 0.3f), hue = 0f)))
            model.commit()
            model.show(EditorView.WHOLE)
        }
        return model
    }

    private fun start(model: EditorViewModel) {
        compose.runOnIdle { model.beginColourPick(0) }
        compose.waitUntil(10_000) { compose.waitForIdle(); model.state.value.colourPicker?.preparing == false }
    }

    @Test fun applyingAColourIsOneUndoStepAndKeepsTheOtherMaskSettings() {
        val model = open()
        val before = model.state.value.edit
        start(model)
        assertNull(model.state.value.colourPicker!!.point)
        compose.runOnIdle { model.pickColour(0.25f, 0.5f) }
        val hue = model.state.value.colourPicker!!.hue!!
        assertEquals(before, model.state.value.edit)
        compose.runOnIdle { model.applyColourPick() }
        assertNull(model.state.value.colourPicker)
        assertEquals(before.copy(locals = listOf(before.locals[0].copy(hue = hue))), model.state.value.edit)
        compose.runOnIdle { model.undo() }
        assertEquals(before, model.state.value.edit)
    }

    @Test fun cancelAndAnUnchangedApplyDoNotAddHistory() {
        val model = open()
        val before = model.state.value.edit
        start(model)
        compose.runOnIdle { model.pickColour(0.25f, 0.5f); model.cancelColourPick() }
        assertEquals(before, model.state.value.edit)
        start(model)
        compose.runOnIdle { model.pickColour(0.25f, 0.5f); model.applyColourPick() }
        val picked = model.state.value.edit
        start(model)
        compose.runOnIdle { model.pickColour(0.25f, 0.5f); model.applyColourPick(); model.undo() }
        assertNotEquals(before, picked)
        assertEquals(before, model.state.value.edit)
    }

    @Test fun grayNeedsAValidSampleAndDoesNotDiscardTheLastValidCandidate() {
        val model = open()
        val before = model.state.value.edit
        start(model)
        compose.runOnIdle { model.pickColour(0.75f, 0.5f); model.applyColourPick() }
        assertEquals(before, model.state.value.edit)
        assertNotNull(model.state.value.colourPicker)
        assertTrue(model.state.value.colourPicker!!.invalid)
        assertNull(model.state.value.colourPicker!!.hue)
        compose.runOnIdle { model.pickColour(0.25f, 0.5f) }
        val hue = model.state.value.colourPicker!!.hue
        compose.runOnIdle { model.pickColour(0.75f, 0.5f) }
        assertEquals(hue, model.state.value.colourPicker!!.hue)
    }

    @Test fun closingOrSwitchingPhotosWhilePreparingCannotPublishTheOldPlane() {
        val model = open()
        compose.runOnIdle { model.beginColourPick(0); model.close(); model.open(otherId) }
        compose.waitUntil(10_000) { compose.waitForIdle(); model.state.value.item?.id == otherId && model.state.value.preview != null && !model.state.value.loading }
        assertNull(model.state.value.colourPicker)
        assertTrue(model.state.value.edit.locals.isEmpty())
    }

    @Test fun changingTheEditOrLeavingTheMaskViewCancelsTheDraft() {
        val model = open()
        start(model)
        compose.runOnIdle { model.adjust { it.copy(exposure = 0.5f) }; model.commit() }
        assertNull(model.state.value.colourPicker)
        start(model)
        compose.runOnIdle { model.show(EditorView.PICTURE) }
        assertNull(model.state.value.colourPicker)
    }

    @Test fun nonGestureMovementStartsAtTheCentreAndRemainsInsideThePhoto() {
        val model = open()
        start(model)
        compose.runOnIdle { model.moveColourCursor(-0.01f, 0f) }
        assertEquals(0.49f, model.state.value.colourPicker!!.point!!.first, 1e-5f)
        assertEquals(0.5f, model.state.value.colourPicker!!.point!!.second, 1e-5f)
        compose.runOnIdle { repeat(110) { model.moveColourCursor(-0.01f, 0.01f) } }
        assertEquals(0f to 1f, model.state.value.colourPicker!!.point)
    }
    @Test fun swatchVisibilityIsLocalPersistentAndDoesNotChangeTheEdit() {
        val model = open()
        val before = model.state.value.edit
        assertTrue(com.byeonggon.latent.data.AppSettings().showColourSwatches)
        runBlocking { container.settings.update { it.copy(jpegQuality = 90) } }
        compose.runOnIdle { model.setShowColourSwatches(false) }
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.showColourSwatches }
        val saved = runBlocking { com.byeonggon.latent.data.SettingsRepository(RuntimeEnvironment.getApplication()).settings.first() }
        assertFalse(saved.showColourSwatches)
        assertEquals(90, saved.jpegQuality)
        assertEquals(before, model.state.value.edit)
        val restored = com.byeonggon.latent.backup.BackupSettings(theme = com.byeonggon.latent.data.ThemeMode.DARK).applyTo(saved)
        assertFalse(restored.showColourSwatches)
        assertFalse(com.byeonggon.latent.backup.BackupSettings.toJson(saved).has("showColourSwatches"))
        compose.runOnIdle { model.close(); model.open(photoId) }
        compose.waitUntil(10_000) { compose.waitForIdle(); model.state.value.preview != null && !model.state.value.loading }
        assertFalse(model.state.value.showColourSwatches)
    }
    @Test fun theScreenPicksWithTouchAndAccessibleActionsAndBackCancelsOnlyTheDraft() {
        val model = open()
        var closed = false
        compose.runOnUiThread { compose.activity.setContent { EditorScreen(photoId, onClose = { closed = true }, viewModel = model) } }
        compose.onNodeWithText("Masks").performScrollTo().performClick()
        compose.onNodeWithText("Pick from photo").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); model.state.value.colourPicker?.preparing == false }
        val before = model.state.value.edit
        compose.onNodeWithText("Apply colour").assertIsNotEnabled()
        val canvas = compose.onNodeWithContentDescription("Photo colour picker")
        canvas.performTouchInput { click(Offset(1f, 1f)) }
        compose.onNodeWithText("Apply colour").assertIsNotEnabled()
        canvas.performTouchInput { down(Offset(width * 0.25f, height * 0.5f)) }
        compose.onNodeWithText("Apply colour").assertIsEnabled()
        canvas.performTouchInput { up() }
        assertEquals(before, model.state.value.edit)
        val initial = model.state.value.colourPicker!!.point!!
        canvas.performTouchInput { swipe(Offset(width * 0.25f, height * 0.5f), Offset(width * 0.35f, height * 0.5f), 300) }
        val point = model.state.value.colourPicker!!.point!!
        assertTrue(point.first > initial.first)
        val action = canvas.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Move sample left" }
        compose.runOnIdle { assertTrue(action.action()) }
        assertEquals(point.first - 0.01f, model.state.value.colourPicker!!.point!!.first, 1e-5f)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(point.first, model.state.value.colourPicker!!.point!!.first, 1e-5f)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertNull(model.state.value.colourPicker)
        assertFalse(closed)
        assertEquals(before, model.state.value.edit)
    }
    @Test fun dpadAloneCanSampleReachTheActionsApplyAndCancel() {
        val model = open()
        compose.runOnUiThread { compose.activity.setContent { EditorScreen(photoId, onClose = {}, viewModel = model) } }
        compose.onNodeWithText("Masks").performScrollTo().performClick()
        compose.onNodeWithText("Pick from photo").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); model.state.value.colourPicker?.preparing == false }
        val before = model.state.value.edit
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        assertNotNull("direction sampled", model.state.value.colourPicker!!.hue)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Apply colour").assertIsFocused()
        assertEquals(before, model.state.value.edit) // Moving focus must not also activate Apply on key-up.
        val point = model.state.value.colourPicker!!.point
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Cancel colour picking").assertIsFocused()
        assertEquals(point, model.state.value.colourPicker!!.point)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil { model.state.value.colourPicker == null }
        assertNotEquals(before, model.state.value.edit)
        compose.runOnIdle { model.undo() }
        assertEquals(before, model.state.value.edit)
        compose.onNodeWithText("Pick from photo").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); model.state.value.colourPicker?.preparing == false }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Cancel colour picking").assertIsFocused() // No valid sample means Apply is disabled.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertNull(model.state.value.colourPicker)
        assertEquals(before, model.state.value.edit)
    }

    @Test fun pickingShowsItsInputInsteadOfBeforeAfterComparison() {
        val model = open()
        compose.runOnIdle { model.setComparing(true) }
        start(model)
        assertFalse(model.state.value.comparing)
    }
    @Test fun pickingUsesTheUprightWholePhotoDespiteTheEditsFraming() {
        val file = File(container.storage.root, "2026/2026-10-27/PICK.JPG")
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val model = open()
        compose.runOnIdle { model.setFraming(Framing(quarterTurns = 1, left = 0.2f, angle = 3f)); model.commit() }
        start(model)
        assertEquals(64, model.state.value.colourPicker!!.preview!!.width)
        assertEquals(96, model.state.value.colourPicker!!.preview!!.height)
        compose.runOnIdle { model.pickColour(0.5f, 0.25f) }
        assertTrue(model.state.value.colourPicker!!.hue!! in 200f..220f)
        compose.runOnIdle { model.pickColour(0.5f, 0.75f) }
        assertTrue(model.state.value.colourPicker!!.invalid)
    }

    @Test
    @Config(qualifiers = "en-rUS-w891dp-h411dp-land-420dpi")
    fun largeTextInTheSidePanelKeepsApplyAndCancelReachable() {
        val model = open()
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    EditorScreen(photoId, onClose = {}, viewModel = model)
                }
            }
        }
        compose.onNodeWithText("Masks").performScrollTo().performClick()
        compose.onNodeWithText("Pick from photo").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); model.state.value.colourPicker?.preparing == false }
        compose.runOnIdle { model.pickColour(0.25f, 0.5f) }
        compose.onNodeWithText("Hue: 210°").assertIsDisplayed()
        compose.onNodeWithText("Apply colour").assertIsDisplayed()
        compose.onNodeWithText("Cancel colour picking").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Cancel colour picking").getBoundsInRoot().run { bottom - top >= 48.dp })
        compose.onNodeWithText("Cancel colour picking").performClick()
        assertNull(model.state.value.colourPicker)
    }
    @Test fun recreatingTheActivityRetainsTheSamePickerSessionAndNormalisedPoint() {
        val model = open()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("PICK.JPG").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("PICK.JPG").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Masks").performScrollTo().performClick()
        compose.onNodeWithText("Pick from photo").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); model.state.value.colourPicker?.preparing == false }
        compose.runOnIdle { model.pickColour(0.25f, 0.5f) }
        val before = model.state.value.colourPicker!!
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertSame(model, ViewModelProvider(compose.activity)[EditorViewModel::class.java])
        assertNotNull(model.state.value.colourPicker)
        assertEquals(before.point, model.state.value.colourPicker!!.point)
        assertEquals(before.hue, model.state.value.colourPicker!!.hue)
        assertSame(before.preview, model.state.value.colourPicker!!.preview)
    }
}
