package com.byeonggon.latent

import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.byeonggon.latent.data.LibraryStorage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment

/** The app asks for reading media first and the library folder second, and only that folder will do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StorageAccessTest {
    @get:Rule(order = 0)
    val environment = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val storage get() = RuntimeEnvironment.getApplication().appContainer.storage

    private fun treeOf(documentId: String) =
        DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", documentId)

    private fun waitFor(text: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun asksForMediaThenForTheFolder() {
        waitFor("사진·동영상 접근 허용")
        compose.onNodeWithText("사진 보관함 설정").assertIsDisplayed()

        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(*LibraryStorage.MEDIA_PERMISSIONS)
        // Coming back from the permission dialog.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("폴더 선택")
        assertFalse(storage.hasAccess())
    }

    @Test
    fun acceptsOnlyAFolderThatHoldsTheLibrary() {
        val folder = storage.folder
        assertFalse(folder.accept(treeOf("primary:Pictures")))
        assertFalse(folder.accept(treeOf("primary:DCIM/Camera")))
        assertFalse(folder.accept(treeOf("primary:DCIM/LatentOld")))
        // The installed app's library is not the debug build's, which these tests run.
        assertFalse(folder.accept(treeOf("primary:DCIM/Latent")))
        assertFalse(folder.isGranted())

        assertTrue(folder.accept(treeOf("primary:DCIM/Latent Debug")))
        assertTrue(folder.isGranted())
        val persisted = ApplicationProvider.getApplicationContext<LatentApp>().contentResolver.persistedUriPermissions
        assertTrue(persisted.single().isWritePermission)
        assertTrue(persisted.single().uri.toString().endsWith("primary%3ADCIM%2FLatent%20Debug"))
    }
}
