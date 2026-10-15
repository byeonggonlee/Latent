package com.byeonggon.latent.map

import android.graphics.Bitmap
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapTilesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val settings = SettingsRepository(context)
    private val asked = ArrayList<String>()

    private val png: ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private val online = OnlineAccess(settings) { url: URL -> asked += url.toString(); png }

    @Before
    fun clean() {
        File(context.cacheDir, "map-tiles").deleteRecursively()
    }

    @After
    fun restoreSettings() = runBlocking { settings.update { AppSettings() } }

    @Test
    fun asksForNothingUntilOnlineMapsAreTurnedOn() = runBlocking {
        // Off is how the app starts out.
        assertEquals(false, settings.settings.first().onlineMaps)

        assertNull(online.get("https://tile.openstreetmap.org/1/1/1.png"))
        assertNull(MapTiles(context, online).tile(5, 3, 4))
        assertEquals(emptyList<String>(), asked)

        settings.update { it.copy(onlineMaps = true) }
        assertNotNull(MapTiles(context, online).tile(5, 3, 4))
        assertEquals(listOf("https://tile.openstreetmap.org/5/3/4.png"), asked)
    }

    @Test
    fun keepsTilesSoThatTheServerIsAskedOnlyOnce() = runBlocking {
        settings.update { it.copy(onlineMaps = true) }
        val tiles = MapTiles(context, online)

        tiles.tile(7, 10, 20)
        tiles.tile(7, 10, 20)
        // A tile is also on disk: a fresh loader, as after the app was closed, needs no second request.
        MapTiles(context, online).tile(7, 10, 20)

        assertEquals(1, asked.size)
    }

    @Test
    fun givesNothingForATileTheServerDoesNotHave() = runBlocking {
        settings.update { it.copy(onlineMaps = true) }
        val missing = MapTiles(context, OnlineAccess(settings) { _: URL -> null })

        assertNull(missing.tile(2, 1, 1))
    }
}
