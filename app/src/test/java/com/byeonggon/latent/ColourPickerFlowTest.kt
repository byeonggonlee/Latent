package com.byeonggon.latent

import android.graphics.Bitmap
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
    @get:Rule(order = 0) val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
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
}
