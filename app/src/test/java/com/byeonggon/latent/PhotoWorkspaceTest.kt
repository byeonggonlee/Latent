package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import com.byeonggon.latent.data.ThemeMode
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
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

/**
 * The photo workspace, dark in either theme: the system bars over it keep readable icons, and a photo the editor
 * cannot read is explained there rather than left as an empty screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoWorkspaceTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private lateinit var folder: File

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            runBlocking { container.settings.update { it.copy(theme = ThemeMode.LIGHT) } }
            folder = File(container.storage.root, "2026/2026-10-27").apply { mkdirs() }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun add(name: String, kind: MediaKind, readable: Boolean) {
        val file = File(folder, name)
        if (readable) {
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } else {
            file.writeBytes(ByteArray(64) { it.toByte() })
        }
        val item = MediaItemEntity(path = file.absolutePath, fileName = name, kind = kind, sizeBytes = file.length(), capturedAt = 1, importedAt = 1)
        runBlocking { container.database.mediaDao().insert(item) }
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun open(name: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(name).performClick()
    }

    private fun lightStatusBarIcons(): Boolean {
        val window = compose.activity.window
        return WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
    }

    @Test
    fun theSystemBarsGetLightIconsOverTheWorkspaceInTheLightTheme() {
        add("PHOTO.JPG", MediaKind.PHOTO, readable = true)
        open("PHOTO.JPG")
        waitForText("Edit")
        compose.waitForIdle()
        assertFalse("light icons over the viewer", lightStatusBarIcons())

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertTrue("dark icons back on the light library", lightStatusBarIcons())
    }

    @Test
    fun aRawFileTheEditorCannotDevelopIsExplainedWithAWayOut() {
        add("BROKEN.ARW", MediaKind.RAW, readable = false)
        open("BROKEN.ARW")
        waitForText("Edit")
        compose.onNodeWithText("Edit").performClick()

        waitForText("Could not open the photo.")
        compose.onNodeWithText("Latent cannot develop this raw file yet. The viewer still shows the picture the camera stored inside it.")
            .assertIsDisplayed()
        // Still there once a message would have gone, with the way back.
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText("Could not open the photo.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        waitForText("Edit")
    }

    @Test
    fun anotherFileTheEditorCannotReadSaysItMayBeDamaged() {
        add("BROKEN.JPG", MediaKind.PHOTO, readable = false)
        open("BROKEN.JPG")
        waitForText("Edit")
        compose.onNodeWithText("Edit").performClick()

        waitForText("The file may be damaged, or no longer where the library expects it.")
    }
}
