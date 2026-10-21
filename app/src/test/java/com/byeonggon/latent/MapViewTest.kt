package com.byeonggon.latent

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.map.MapPoint
import com.byeonggon.latent.map.MapTiles
import com.byeonggon.latent.map.OnlineAccess
import com.byeonggon.latent.ui.map.MapViewModel
import com.byeonggon.latent.ui.map.PhotoMap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.URL

/** The map with its photos: markers for places, with the number of photos taken at each, and zoom buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val application = RuntimeEnvironment.getApplication()
    private val settings = SettingsRepository(application)

    @After
    fun restoreSettings() = runBlocking { settings.update { AppSettings() } }

    @Test
    fun showsAMarkerForEachPlaceWithItsNumberOfPhotos() {
        runBlocking { settings.update { it.copy(onlineMaps = true) } }
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val requested = ArrayList<URL>()
        val tiles = MapTiles(application, OnlineAccess(settings, "0.1.0") { url: URL -> synchronized(requested) { requested += url }; png })
        val points = listOf(
            MapPoint(1, 37.5665, 126.9780), MapPoint(2, 37.5666, 126.9785), MapPoint(3, 37.5667, 126.9790),
            MapPoint(4, 37.4979, 127.0276),
        )

        val model = MapViewModel(application)
        compose.setContent { PhotoMap(points, tiles, model, onOpen = {}) }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("여기에서 찍은 사진 3장").assertIsDisplayed()
        compose.onNodeWithContentDescription("확대").assertIsDisplayed()
        compose.onNodeWithContentDescription("축소").performClick()
        compose.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
        // The tiles it asked for are the ones of OpenStreetMap, nothing else.
        Thread.sleep(300)
        assertTrue(synchronized(requested) { requested.isNotEmpty() && requested.all { it.host == "tile.openstreetmap.org" } })

        // The credit leads to the page that says what may be done with the map.
        compose.onNodeWithText("© OpenStreetMap contributors").performClick()
        val opened = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://www.openstreetmap.org/copyright", opened.dataString)
    }
}
