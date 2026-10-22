package com.byeonggon.latent.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.byeonggon.latent.enhance.DetectedKind
import com.byeonggon.latent.enhance.Detection
import com.byeonggon.latent.enhance.LocalEdit
import com.byeonggon.latent.enhance.Mask
import com.byeonggon.latent.enhance.MaskShape
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The buttons of the masks a model finds, in the panel of parts of the picture: offered only for the kinds the phone can
 * find, each asking for its own kind, held while one is being looked for, and "Find again" for an outline that is lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetectedMaskButtonsTest {
    @get:Rule
    val compose = createComposeRule()

    private val asked = ArrayList<Pair<DetectedKind, Boolean>>()
    private var foundAgain = 0

    private var detecting by mutableStateOf(Detecting())
    private var locals by mutableStateOf(emptyList<LocalEdit>())

    private fun detecting(available: Set<DetectedKind>, busy: DetectedKind? = null, lost: Set<String> = emptySet()) = Detecting(
        available = available,
        busy = busy,
        lost = lost,
        onAdd = { kind, inverted -> asked += kind to inverted },
        onFindAgain = { foundAgain++ },
    )

    private fun detected(kind: DetectedKind, raster: String) =
        LocalEdit(Mask(MaskShape.DETECTED, 0f, 0f, 0f, 0f, feather = 0f, detection = Detection(kind, raster, "model", 1)))

    private fun show(available: Set<DetectedKind>, busy: DetectedKind? = null) {
        detecting = detecting(available, busy)
        compose.setContent {
            Column {
                LocalPanel(locals = locals, selected = 0, enabled = true, onSelect = {}, onChange = { locals = it }, onDone = {}, detecting = detecting)
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun offersOnlyTheKindsThePhoneCanFind() {
        show(available = setOf(DetectedKind.SUBJECT))
        compose.onNodeWithText("+ Subject").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+ Background").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("+ Sky").assertCountEquals(0)
        // The masks drawn by hand are there whatever the models can do.
        compose.onNodeWithText("+ Linear").performScrollTo().assertIsDisplayed()

        detecting = detecting(available = setOf(DetectedKind.SKY))
        compose.waitForIdle()
        compose.onNodeWithText("+ Sky").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("+ Subject").assertCountEquals(0)
        compose.onAllNodesWithText("+ Background").assertCountEquals(0)

        detecting = detecting(available = emptySet())
        compose.waitForIdle()
        assertEquals(0, count("+ Sky") + count("+ Subject") + count("+ Background"))
        compose.onNodeWithText("+ Linear").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun asksForTheKindOfEachButton() {
        show(available = setOf(DetectedKind.SUBJECT, DetectedKind.SKY))

        compose.onNodeWithText("+ Subject").performScrollTo().performClick()
        compose.onNodeWithText("+ Background").performScrollTo().performClick()
        compose.onNodeWithText("+ Sky").performScrollTo().performClick()

        assertEquals(listOf(DetectedKind.SUBJECT to false, DetectedKind.SUBJECT to true, DetectedKind.SKY to false), asked)
        // Nothing is added until the model has found it.
        assertEquals(emptyList<LocalEdit>(), locals)
    }

    @Test
    fun saysWhatItIsLookingForAndHoldsTheOtherButtonsMeanwhile() {
        show(available = setOf(DetectedKind.SUBJECT, DetectedKind.SKY), busy = DetectedKind.SKY)
        compose.onNodeWithText("Finding the sky…").assertIsDisplayed()
        compose.onAllNodesWithText("Finding the subject…").assertCountEquals(0)
        for (button in listOf("+ Subject", "+ Background", "+ Sky")) compose.onNodeWithText(button).performScrollTo().assertIsNotEnabled()
        // A mask drawn by hand can still be added.
        compose.onNodeWithText("+ Radial").performScrollTo().assertIsEnabled()

        detecting = detecting(available = setOf(DetectedKind.SUBJECT, DetectedKind.SKY), busy = DetectedKind.SUBJECT)
        compose.waitForIdle()
        compose.onNodeWithText("Finding the subject…").assertIsDisplayed()
        compose.onAllNodesWithText("Finding the sky…").assertCountEquals(0)

        detecting = detecting(available = setOf(DetectedKind.SUBJECT, DetectedKind.SKY))
        compose.waitForIdle()
        assertEquals(0, count("Finding the subject…") + count("Finding the sky…"))
        for (button in listOf("+ Subject", "+ Background", "+ Sky")) compose.onNodeWithText(button).performScrollTo().assertIsEnabled()
    }

    @Test
    fun addsNoMoreThanSixMasks() {
        locals = List(6) { detected(DetectedKind.SUBJECT, "outline$it") }
        show(available = setOf(DetectedKind.SUBJECT, DetectedKind.SKY))

        for (button in listOf("+ Subject", "+ Background", "+ Sky", "+ Brush")) compose.onNodeWithText(button).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun findsALostOutlineAgainOnlyWhereItsKindCanBeFound() {
        locals = listOf(detected(DetectedKind.SKY, "sky-outline"))
        detecting = detecting(available = setOf(DetectedKind.SKY), lost = setOf("sky-outline"))
        compose.setContent {
            Column {
                LocalPanel(locals = locals, selected = 0, enabled = true, onSelect = {}, onChange = { locals = it }, onDone = {}, detecting = detecting)
            }
        }
        compose.onNodeWithText("The outline of this mask is missing, so it changes nothing.").assertIsDisplayed()
        compose.onNodeWithText("Find again").performClick()
        assertEquals(1, foundAgain)

        // While something is being looked for, it waits.
        detecting = detecting(available = setOf(DetectedKind.SKY), busy = DetectedKind.SKY, lost = setOf("sky-outline"))
        compose.waitForIdle()
        compose.onNodeWithText("Find again").assertIsNotEnabled()

        // Where the sky cannot be found, the outline is still said to be lost, with no way to find it.
        detecting = detecting(available = setOf(DetectedKind.SUBJECT), lost = setOf("sky-outline"))
        compose.waitForIdle()
        compose.onNodeWithText("The outline of this mask is missing, so it changes nothing.").assertIsDisplayed()
        compose.onAllNodesWithText("Find again").assertCountEquals(0)

        // An outline that is kept needs neither.
        detecting = detecting(available = setOf(DetectedKind.SKY))
        compose.waitForIdle()
        compose.onAllNodesWithText("The outline of this mask is missing, so it changes nothing.").assertCountEquals(0)
        compose.onAllNodesWithText("Find again").assertCountEquals(0)
    }
}
