package com.byeonggon.latent.enhance

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Which photos the viewer shows as HDR, and the window it shows them in. The decoding itself, and what an HDR screen makes
 * of it, only a phone shows: Robolectric's ImageDecoder reads no files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HdrPicturesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun jpeg(width: Int = 64, height: Int = 48): File = folder.newFile("photo.jpg").apply {
        outputStream().use { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    /** [file] with an Ultra HDR gain map's XMP put in after its start, as a phone writes it. */
    private fun withGainMapXmp(file: File): File {
        val payload = "http://ns.adobe.com/xap/1.0/".toByteArray() + byteArrayOf(0) +
            """<x:xmpmeta><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/></x:xmpmeta>""".toByteArray()
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        val bytes = file.readBytes()
        return File(file.parentFile, "ultra-hdr.jpg").apply { writeBytes(bytes.copyOfRange(0, 2) + segment + bytes.copyOfRange(2, bytes.size)) }
    }

    @Test
    fun takesTheHlgAndPqTransfersForHdr() {
        assertTrue(HdrPictures.isHdrColorSpace(ColorSpace.get(ColorSpace.Named.BT2020_HLG)))
        assertTrue(HdrPictures.isHdrColorSpace(ColorSpace.get(ColorSpace.Named.BT2020_PQ)))
        assertFalse(HdrPictures.isHdrColorSpace(ColorSpace.get(ColorSpace.Named.SRGB)))
        assertFalse(HdrPictures.isHdrColorSpace(ColorSpace.get(ColorSpace.Named.DISPLAY_P3)))
        assertFalse(HdrPictures.isHdrColorSpace(ColorSpace.get(ColorSpace.Named.BT2020)))
        assertFalse(HdrPictures.isHdrColorSpace(null))
    }

    @Test
    fun tellsAnUltraHdrJpegFromAnOrdinaryOne() {
        val ordinary = jpeg()

        assertFalse(HdrPictures.isHdr(ordinary))
        assertTrue(HdrPictures.isHdr(withGainMapXmp(ordinary)))
    }

    @Test
    fun letsTheWindowShowHdrAndTakesItBack() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        HdrPictures.showHdr(activity, true)
        assertEquals(ActivityInfo.COLOR_MODE_HDR, activity.window.colorMode)

        HdrPictures.showHdr(activity, false)
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.window.colorMode)
    }
}
