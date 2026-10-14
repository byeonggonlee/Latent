package com.byeonggon.latent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.byeonggon.latent.data.db.SyncRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment

/** Launches the real activity and walks through every tab, to catch wiring mistakes that only show at runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSmokeTest {
    // Runs before the activity starts. Robolectric has no shared storage by default, which the
    // storage-permission check cannot cope with.
    @get:Rule(order = 0)
    val environment = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            // A file that was imported once and is no longer in the library.
            val records = RuntimeEnvironment.getApplication().appContainer.database.syncRecordDao()
            runBlocking { records.insert(SyncRecordEntity("camera|DSC00001.JPG|10|1", importedAt = 2)) }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun everyTabRenders() {
        // The library is filled from the database, off the test's own dispatcher.
        val emptyLibrary = "아직 가져온 사진이 없습니다"
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(emptyLibrary, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(emptyLibrary, substring = true).assertIsDisplayed()

        compose.onNodeWithText("카메라").performClick()
        compose.onNodeWithText("카메라 동기화").assertIsDisplayed()
        compose.onNodeWithText("연결된 카메라가 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("기간 선택").performScrollTo().performClick()
        compose.onNodeWithText("적용").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()

        compose.onNodeWithText("위치").performClick()
        compose.onNodeWithText("위치 기록").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("저장된 위치 0개", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun letsFilesDeletedFromThePhoneBeImportedAgain() {
        val notice = "폰에서 지운 파일 1개"
        compose.onNodeWithText("카메라").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(notice, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("다시 가져올 수 있게 하기").performScrollTo().performClick()
        compose.onNodeWithText("지우기").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(notice, substring = true).fetchSemanticsNodes().isEmpty()
        }
        val records = RuntimeEnvironment.getApplication().appContainer.database.syncRecordDao()
        assertEquals(emptyList<String>(), runBlocking { records.allKeys() })
    }
}
