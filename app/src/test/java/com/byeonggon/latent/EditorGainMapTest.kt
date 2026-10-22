package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.UltraHdr
import com.byeonggon.latent.raw.SyntheticArw
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
 * The editor says, as an Ultra HDR photo opens, that the edit is shown and saved without its gain map, and the saved edit
 * has none while the original kept aside does. Of any other photo it says nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorGainMapTest {
    private lateinit var ultraHdr: File
    private lateinit var ordinary: File
    private lateinit var raw: File

    private val container get() = RuntimeEnvironment.getApplication().appContainer

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-22").apply { mkdirs() }
            ordinary = File(folder, "DSC00001.JPG").apply { writeBytes(jpeg()) }
            ultraHdr = File(folder, "PXL_0002.JPG").apply { writeBytes(withGainMapXmp(jpeg())) }
            raw = File(folder, "DSC00003.ARW").apply { writeBytes(SyntheticArw.file(red = 600, green = 900, blue = 700)) }
            runBlocking {
                for ((file, kind, at) in listOf(Triple(ordinary, MediaKind.PHOTO, 3_000L), Triple(ultraHdr, MediaKind.PHOTO, 2_000L), Triple(raw, MediaKind.RAW, 1_000L))) {
                    container.database.mediaDao().insert(
                        MediaItemEntity(path = file.absolutePath, fileName = file.name, kind = kind, sizeBytes = file.length(), capturedAt = at, importedAt = 4_000),
                    )
                }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 96) {
            val level = 60 + x + y / 2
            bitmap.setPixel(x, y, (0xFF shl 24) or (level shl 16) or (level shl 8) or level)
        }
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    /** [jpeg] with an Ultra HDR gain map's XMP put in after its start, as a phone writes it. */
    private fun withGainMapXmp(jpeg: ByteArray): ByteArray {
        val payload = "http://ns.adobe.com/xap/1.0/".toByteArray() + byteArrayOf(0) +
            """<x:xmpmeta><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/></x:xmpmeta>""".toByteArray()
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun openEditor(fileName: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(fileName).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(fileName).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("편집").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("편집").performClick()
        // The sliders come alive once the picture has been read and drawn.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("노출").fetchSemanticsNodes()
                .any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
        }
    }

    private fun warning() = compose.onAllNodesWithText("HDR 사진입니다", substring = true)

    @Test
    fun saysAnUltraHdrPhotoIsEditedWithoutItsGainMapAndSavesItSo() {
        val original = ultraHdr.readBytes()
        assertTrue(UltraHdr.hasGainMap(ultraHdr))

        openEditor(ultraHdr.name)
        compose.waitUntil(timeoutMillis = 10_000) { warning().fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("노출").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("완료").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("원본으로 되돌리기").fetchSemanticsNodes().isNotEmpty() }

        // The edit has no gain map: one made for the original would brighten the wrong picture.
        assertFalse(UltraHdr.hasGainMap(ultraHdr))
        val kept = container.storage.originalOf(ultraHdr)
        assertArrayEquals(original, kept.readBytes())
        assertTrue(UltraHdr.hasGainMap(kept))
    }

    @Test
    fun saysNothingOfAnOrdinaryPhoto() {
        openEditor(ordinary.name)
        compose.waitForIdle()

        warning().assertCountEquals(0)
    }

    @Test
    fun saysNothingOfARawFile() {
        openEditor(raw.name)
        compose.waitForIdle()

        warning().assertCountEquals(0)
    }
}
