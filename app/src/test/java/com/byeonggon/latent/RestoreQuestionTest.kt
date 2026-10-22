package com.byeonggon.latent

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * What the settings say of a backup before anything of it is restored: the photos it leaves out, the ones whose own
 * rating it replaces, and online maps; and that nothing changes until "Restore" is pressed, or when the file is damaged
 * or newer than the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoreQuestionTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val container get() = application.appContainer
    private val mediaDao get() = container.database.mediaDao()
    private val ids = LongArray(2)
    private lateinit var backup: File

    @get:Rule(order = 0)
    val files = TemporaryFolder()

    // A backup of two photos, the first rated 3, made with online maps on; then here the first is rated 5, the second
    // is gone, and online maps are off.
    @get:Rule(order = 1)
    val environment = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-19").apply { mkdirs() }
            val photos = (1..2).map { number ->
                File(folder, "DSC0000$number.JPG").apply {
                    outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF203040.toInt() * number) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                }
            }
            backup = File(files.root, "backup.zip")
            runBlocking {
                photos.forEachIndexed { index, photo ->
                    ids[index] = mediaDao.insert(
                        MediaItemEntity(path = photo.path, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 5_000L + index, importedAt = 1, rating = 3),
                    )
                }
                container.settings.update { it.copy(onlineMaps = true) }
                backup.outputStream().use { container.backupWriter.write(it) }
                container.settings.update { it.copy(onlineMaps = false) }
                mediaDao.setRating(ids[0], 5)
                photos[1].delete()
                mediaDao.deleteByIds(listOf(ids[1]))
            }
        }

        override fun after() = runBlocking { container.settings.update { AppSettings() } }
    }

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    private val staged get() = File(application.cacheDir, "restore")

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openSettings() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("보관함 메뉴").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("보관함 메뉴").performClick()
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("백업과 복원").performScrollTo().assertIsDisplayed()
    }

    /** Opens "Restore…" and answers the system's file dialog with [file]. */
    private fun restoreFrom(file: File) {
        compose.onNodeWithText("복원…").performScrollTo().performClick()
        val asked = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("No file dialog was opened", asked)
        shadowOf(compose.activity).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
    }

    /** A copy of [backup] with the entry [name] changed by [change]. */
    private fun rewritten(name: String, change: (String) -> String): File {
        val copy = File(files.root, "changed-$name.zip")
        ZipFile(backup).use { zip ->
            ZipOutputStream(copy.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(if (entry.name == name) change(String(bytes)).toByteArray() else bytes)
                    out.closeEntry()
                }
            }
        }
        return copy
    }

    private fun rating() = runBlocking { mediaDao.byId(ids[0])!!.rating }

    private fun onlineMaps() = runBlocking { container.settings.settings.first().onlineMaps }

    @Test
    fun saysWhatTheRestoreLeavesOutAndReplacesAndChangesNothingWhenCancelled() {
        openSettings()
        restoreFrom(backup)
        waitForText("이 백업을 복원할까요?")

        compose.onNodeWithText("이 휴대전화에서 찾은 사진과 동영상: 2개 중 1개").assertIsDisplayed()
        compose.onNodeWithText("이 휴대전화에 없어서 빠지는 사진(1개): DSC00002.JPG").assertIsDisplayed()
        compose.onNodeWithText("이 휴대전화에서 따로 준 별점, 플래그, 보정, 위치가 백업의 것으로 바뀌는 사진: 1개").assertIsDisplayed()
        compose.onNodeWithText("백업에서는 온라인 지도가 켜져 있었지만", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("취소").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); !staged.exists() }
        assertEquals(0, compose.onAllNodesWithText("이 백업을 복원할까요?").fetchSemanticsNodes().size)
        assertEquals(5, rating())
        assertFalse(onlineMaps())

        // Asked again, and this time restored: the backup's rating comes back, online maps stay off.
        restoreFrom(backup)
        waitForText("이 백업을 복원할까요?")
        compose.onNodeWithText("복원").performClick()
        waitForText("복원했습니다")
        assertEquals(3, rating())
        assertFalse(onlineMaps())
        compose.waitUntil(timeoutMillis = 10_000) { !staged.exists() }
    }

    @Test
    fun takesNothingOverFromADamagedBackup() {
        val damaged = rewritten("media.jsonl") { it.replaceFirst("{", "{\"id\":") }
        openSettings()
        restoreFrom(damaged)

        waitForText("백업 파일이 손상되었거나 덜 만들어져서 아무것도 들어오지 않았습니다.")
        assertEquals(0, compose.onAllNodesWithText("이 백업을 복원할까요?").fetchSemanticsNodes().size)
        assertEquals(5, rating())
        compose.waitUntil(timeoutMillis = 10_000) { !staged.exists() }
    }

    @Test
    fun asksForANewerAppForABackupOfANewerFormat() {
        val newer = rewritten("manifest.json") { it.replace(Regex("\"formatVersion\"\\s*:\\s*1"), "\"formatVersion\":7") }
        openSettings()
        restoreFrom(newer)

        waitForText("더 새로운 버전의 Latent가 만든 백업입니다(형식 7, 이 버전은 1까지 읽음).")
        assertEquals(0, compose.onAllNodesWithText("이 백업을 복원할까요?").fetchSemanticsNodes().size)
        assertEquals(5, rating())
    }
}
