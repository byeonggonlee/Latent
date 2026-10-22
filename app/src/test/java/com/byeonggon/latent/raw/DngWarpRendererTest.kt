package com.byeonggon.latent.raw

import com.byeonggon.latent.enhance.Orientation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DngWarpRendererTest {
    private fun opcode(): ByteArray = ByteBuffer.allocate(88).apply {
        putInt(1); putInt(1); putInt(0x01030000); putInt(1); putInt(68)
        putInt(1)
        doubleArrayOf(0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.5).forEach { putDouble(it) }
    }.array()

    @Test
    fun rendererUsesTheToggleAndTurnsWarpedPixelsAfterDevelopment() {
        for (orientation in 1..8) {
            val image = Dng.read(SyntheticDng.file(samples = { x, y -> 100 + x * 10 + y },
                orientation = orientation, opcodes3 = opcode()))
            val plain = RawRenderer.proxy(image, cells = 1, lensCorrection = false)
            val corrected = RawRenderer.proxy(image, cells = 1, lensCorrection = true)
            val expected = RawDeveloper.developPreview(image, 1, lensCorrection = true)
                .oriented(Orientation.ofExif(orientation))
            assertArrayEquals(expected.red, corrected.red)
            assertArrayEquals(expected.green, corrected.green)
            assertArrayEquals(expected.blue, corrected.blue)
            assertFalse(plain.red.contentEquals(corrected.red))
        }
    }

    @Test
    fun linearDngAlsoCarriesTheWarpWithoutChangingSamplesOrAppliedCorrections() {
        val plain = Dng.decode(SyntheticDng.linear(192, 380, 264))
        val decoded = Dng.decode(SyntheticDng.linear(192, 380, 264, opcodes3 = opcode()))
        assertNotNull(decoded.legacyImage.dngWarp)
        assertArrayEquals(plain.legacyImage.sensor, decoded.legacyImage.sensor)
        assertTrue(plain.corrections == decoded.corrections)
        val corrected = RawRenderer.proxy(decoded.legacyImage, cells = 1, lensCorrection = true)
        val uncorrected = RawRenderer.proxy(decoded.legacyImage, cells = 1, lensCorrection = false)
        assertArrayEquals(uncorrected.red, corrected.red)
        assertArrayEquals(uncorrected.green, corrected.green)
        assertArrayEquals(uncorrected.blue, corrected.blue)
    }
}
