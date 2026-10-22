package com.byeonggon.latent.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.byeonggon.latent.data.db.EditVersionEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The versions of an edit: listed by name, opened by a tap on one, each deleted by its own button, and none said so. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VersionsDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val warm = EditVersionEntity(id = 1, mediaId = 7, name = "Warm", edit = "{\"exposure\":0.5}", createdAt = 1)
    private val cold = EditVersionEntity(id = 2, mediaId = 7, name = "Cold", edit = "{\"temperature\":-0.5}", createdAt = 2)

    private val opened = ArrayList<EditVersionEntity>()
    private val deleted = ArrayList<EditVersionEntity>()
    private var dismissed = 0

    private fun show(versions: List<EditVersionEntity>) = compose.setContent {
        VersionsDialog(versions, onKeep = {}, onOpen = { opened += it }, onDelete = { deleted += it }, onDismiss = { dismissed++ })
    }

    @Test
    fun saysThereAreNoneYet() {
        show(emptyList())

        compose.onNodeWithText("No versions yet.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescription("Delete version").fetchSemanticsNodes().size)
    }

    @Test
    fun opensTheVersionTappedAndCloses() {
        show(listOf(warm, cold))
        assertEquals(0, compose.onAllNodesWithText("No versions yet.").fetchSemanticsNodes().size)

        compose.onNodeWithText("Cold").performClick()

        assertEquals(listOf(cold), opened)
        assertEquals(1, dismissed)
        assertEquals(emptyList<EditVersionEntity>(), deleted)
    }

    @Test
    fun deletesOnlyTheVersionWhoseButtonWasTapped() {
        show(listOf(warm, cold))

        compose.onAllNodesWithContentDescription("Delete version")[1].performClick()

        assertEquals(listOf(cold), deleted)
        assertEquals(emptyList<EditVersionEntity>(), opened)
        assertEquals(0, dismissed)
    }
}
