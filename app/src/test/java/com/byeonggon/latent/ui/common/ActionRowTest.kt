package com.byeonggon.latent.ui.common

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A bar of actions shows what fits and puts the rest under More, so that none is lost off the edge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActionRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val clicked = mutableListOf<String>()
    private val actions = listOf("Share", "Delete", "Compare", "Rating", "Flags", "Add to album", "Paste edit")
        .map { label -> BarAction(label) { clicked += label } }

    @Test
    fun aNarrowBarKeepsTheFirstActionsAndPutsTheRestUnderMore() {
        compose.setContent { ActionRow(actions, Modifier.width(260.dp)) }

        compose.onNodeWithText("Share").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Paste edit").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Paste edit").assertIsDisplayed().performClick()
        assertEquals(listOf("Paste edit"), clicked)
    }

    @Test
    fun aBarWithRoomShowsEveryActionAndNoMore() {
        val few = actions.take(3)
        compose.setContent { ActionRow(few, Modifier.width(380.dp)) }

        few.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
        assertEquals(0, compose.onAllNodesWithContentDescription("More").fetchSemanticsNodes().size)
    }
}
