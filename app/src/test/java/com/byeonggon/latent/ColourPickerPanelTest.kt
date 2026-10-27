package com.byeonggon.latent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.byeonggon.latent.enhance.LocalEdit
import com.byeonggon.latent.enhance.Mask
import com.byeonggon.latent.enhance.MaskShape
import com.byeonggon.latent.ui.editor.LocalPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-420dpi")
class ColourPickerPanelTest {
    @get:Rule val compose = createComposeRule()
    private fun panel(shape: MaskShape = MaskShape.LINEAR, visible: Boolean = true) {
        compose.setContent {
            var show by remember { mutableStateOf(visible) }
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LocalPanel(
                        locals = listOf(LocalEdit(Mask(shape, 0f, 0f, 1f, 1f), hue = 215f)), selected = 0, enabled = true,
                        onSelect = {}, onChange = {}, onDone = {}, showColourSwatches = show,
                        onShowColourSwatches = { show = it }, onPickColour = {},
                    )
                }
            }
        }
    }

    @Test fun hidingSwatchesKeepsThePickerCurrentHueAnyColourAndRange() {
        panel()
        compose.onNodeWithContentDescription("Red").assertExists()
        compose.onNodeWithText("Colour options").performScrollTo().performClick()
        compose.onNodeWithText("Show colour swatches").performClick()
        compose.onNodeWithContentDescription("Red").assertDoesNotExist()
        compose.onNodeWithText("Pick from photo").assertExists()
        compose.onNodeWithText("Hue: 215°").assertExists()
        compose.onNodeWithText("Any").assertIsEnabled()
        compose.onNodeWithContentDescription("Colour range").assertExists()
        compose.onNodeWithText("Colour options").performScrollTo().performClick()
        compose.onNodeWithText("Show colour swatches").performClick()
        compose.onNodeWithContentDescription("Red").assertExists()
    }

    @Test fun swatchMenuAnnouncesBothCheckedStatesOnItsSingleClickTarget() {
        panel()
        compose.onNodeWithText("Colour options").performScrollTo().performClick()
        compose.onNodeWithText("Show colour swatches")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performClick()
        compose.onNodeWithText("Colour options").performClick()
        compose.onNodeWithText("Show colour swatches")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test fun hidingSwatchesDoesNotEnableAnyColourForAWholePhotoColourMask() {
        panel(MaskShape.EVERYWHERE, visible = false)
        compose.onNodeWithText("Any").assertIsNotEnabled()
        compose.onNodeWithText("Pick from photo").assertExists()
        compose.onNodeWithText("Hue: 215°").assertExists()
    }
}
