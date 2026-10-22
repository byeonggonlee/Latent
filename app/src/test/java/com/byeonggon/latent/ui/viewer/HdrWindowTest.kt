package com.byeonggon.latent.ui.viewer

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The viewer's window turns to HDR while an HDR photo is in front, and back to ordinary pictures when another photo comes
 * in front or the viewer closes. Whether a photo decodes as HDR only a phone shows: Robolectric's decoder keeps no gain map.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HdrWindowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var showing by mutableStateOf(false)
    private var viewerOpen by mutableStateOf(true)

    private val colorMode get() = compose.activity.window.colorMode

    private fun settle() = compose.waitForIdle()

    @Test
    fun showsHdrOnlyWhileAnHdrPhotoIsInFront() {
        compose.setContent { if (viewerOpen) HdrWindow(showing) }
        settle()
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, colorMode)

        showing = true
        settle()
        assertEquals(ActivityInfo.COLOR_MODE_HDR, colorMode)

        // Swiped on to an ordinary photo.
        showing = false
        settle()
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, colorMode)

        showing = true
        settle()
        assertEquals(ActivityInfo.COLOR_MODE_HDR, colorMode)

        // The viewer closes with the HDR photo still in front: the library is not left in HDR.
        viewerOpen = false
        settle()
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, colorMode)
    }
}
