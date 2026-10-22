package com.byeonggon.latent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.db.FilterEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.util.zip.ZipFile

/** The backup part of the settings: what it says, and that its buttons lead to the system's file dialogs and back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupSettingsTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer

    @get:Rule(order = 0)
    val files = TemporaryFolder()

    @get:Rule(order = 1)
    val environment = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
            grantLibraryAccess()
        }

        override fun after() = runBlocking { container.settings.update { AppSettings() } }
    }

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openSettings() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("보관함 메뉴").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("보관함 메뉴").performClick()
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("백업과 복원").performScrollTo().assertIsDisplayed()
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** What the system's file dialog was asked for, answered with [file] as the person's choice. */
    private fun answerFileDialog(file: File): Intent {
        val asked = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("No file dialog was opened", asked)
        shadowOf(compose.activity).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        return asked.intent
    }

    @Test
    fun saysWhatTheFileHoldsAndThatItIsToBeKeptPrivate() {
        openSettings()
        compose.onNodeWithText("파일 하나에 담아", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("위치 기록이 들어 있습니다", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("백업…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("복원…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backsUpIntoTheFileThePersonChoseUnderADatedName() {
        openSettings()
        val chosen = File(files.root, "picked/latent.zip").apply { parentFile!!.mkdirs() }

        compose.onNodeWithText("백업…").performScrollTo().performClick()
        val asked = answerFileDialog(chosen)
        waitForText("백업을 저장했습니다")

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, asked.action)
        assertEquals("application/zip", asked.type)
        assertTrue(asked.getStringExtra(Intent.EXTRA_TITLE)!!, asked.getStringExtra(Intent.EXTRA_TITLE)!!.matches(Regex("latent-backup-\\d{8}-\\d{4}\\.zip")))
        ZipFile(chosen).use { assertNotNull(it.getEntry("manifest.json")) }
    }

    @Test
    fun restoresOnlyOnceThePersonHasSeenWhatItWillDo() {
        val backup = File(files.root, "backup.zip")
        runBlocking {
            val filters = container.database.filterDao()
            val id = filters.insert(FilterEntity(name = "Mine", look = "{\"exposure\":0.5}", createdAt = 1))
            backup.outputStream().use { container.backupWriter.write(it) }
            filters.delete(id)
        }
        openSettings()

        compose.onNodeWithText("복원…").performScrollTo().performClick()
        val asked = answerFileDialog(backup)
        waitForText("이 백업을 복원할까요?")

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, asked.action)
        assertTrue(runBlocking { container.database.backupDao().filters() }.isEmpty())
        compose.onNodeWithText("내 필터 1", substring = true).assertIsDisplayed()

        compose.onNodeWithText("복원").performClick()
        waitForText("복원했습니다")
        assertEquals(listOf("Mine"), runBlocking { container.database.backupDao().filters() }.map { it.name })
    }

    @Test
    fun saysSoWhenTheBackupCannotBeWritten() {
        // A place that cannot hold a file: its folder is a file.
        val blocked = File(files.root, "blocked").apply { writeText("a file, not a folder") }
        openSettings()

        compose.onNodeWithText("백업…").performScrollTo().performClick()
        answerFileDialog(File(blocked, "latent.zip"))

        waitForText("백업을 저장하지 못했습니다")
        assertEquals(0, compose.onAllNodesWithText("백업을 저장했습니다", substring = true).fetchSemanticsNodes().size)
        // Both buttons are back for another try.
        compose.onNodeWithText("백업…").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("복원…").performScrollTo().assertIsEnabled()
    }

    @Test
    fun saysSoWhenTheChosenFileIsNoBackup() {
        val other = File(files.root, "notes.zip").apply { writeText("not a zip") }
        openSettings()

        compose.onNodeWithText("복원…").performScrollTo().performClick()
        answerFileDialog(other)

        waitForText("Latent 백업 파일이 아닙니다")
    }
}
