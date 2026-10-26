package com.byeonggon.latent

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.byeonggon.latent.ui.common.LatentDark
import com.byeonggon.latent.ui.common.LatentLight
import com.byeonggon.latent.ui.common.OverPhoto
import com.byeonggon.latent.ui.common.PictureBackground
import com.byeonggon.latent.ui.common.WorkspaceColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The colours of docs/UI_GUIDE.md §3 keep text and controls readable, and the greys keep their hue out of the photos. */
class PaletteTest {

    /** WCAG's contrast ratio of two opaque colours, from their relative luminance in sRGB. */
    private fun contrast(a: Color, b: Color): Double {
        fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun assertContrast(name: String, foreground: Color, background: Color, least: Double) {
        val ratio = contrast(foreground, background)
        assertTrue("$name is %.2f:1, needs %.1f:1".format(ratio, least), ratio >= least)
    }

    /** Text 4.5:1 and the boundaries of controls 3:1, on the surfaces they are drawn on. */
    private fun assertReadable(scheme: ColorScheme, name: String) {
        listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh).forEach { background ->
            assertContrast("$name onSurface", scheme.onSurface, background, 4.5)
            assertContrast("$name onSurfaceVariant", scheme.onSurfaceVariant, background, 4.5)
            assertContrast("$name primary", scheme.primary, background, 4.5)
            assertContrast("$name error", scheme.error, background, 4.5)
            assertContrast("$name outline", scheme.outline, background, 3.0)
        }
        assertContrast("$name onPrimary", scheme.onPrimary, scheme.primary, 4.5)
        assertContrast("$name onPrimaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer, 4.5)
        assertContrast("$name onSecondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer, 4.5)
        assertContrast("$name onError", scheme.onError, scheme.error, 4.5)
    }

    @Test
    fun lightThemeIsReadable() = assertReadable(LatentLight, "light")

    @Test
    fun darkThemeIsReadable() = assertReadable(LatentDark, "dark")

    @Test
    fun workspaceIsReadable() {
        assertReadable(WorkspaceColors, "workspace")
        assertContrast("workspace outline on the surround", WorkspaceColors.outline, PictureBackground, 3.0)
    }

    @Test
    fun theFiguresInTheGuideHold() {
        // A sample of the ratios printed in UI_GUIDE §3, to one decimal.
        assertEquals(16.1, contrast(LatentLight.onSurface, LatentLight.surface), 0.05)
        assertEquals(5.6, contrast(LatentLight.primary, LatentLight.surface), 0.05)
        assertEquals(14.9, contrast(LatentDark.onSurface, LatentDark.surface), 0.05)
        assertEquals(10.5, contrast(LatentDark.primary, LatentDark.surface), 0.05)
        assertEquals(7.1, contrast(WorkspaceColors.onSurfaceVariant, WorkspaceColors.surface), 0.05)
        assertEquals(7.7, contrast(LatentDark.onError, LatentDark.error), 0.05)
    }

    @Test
    fun whiteOnTheScrimIsReadableOverAnyPhoto() {
        // Over a pure white sky and over black, the extremes of what a photo can be under a badge.
        listOf(Color.White, Color.Black).forEach { photo ->
            assertContrast("over-photo text", OverPhoto.Content, OverPhoto.Scrim.compositeOver(photo), 4.5)
        }
        assertContrast("a failure's icon", OverPhoto.Content, OverPhoto.Failure, 4.5)
    }

    @Test
    fun greysHaveNoHue() {
        val greys = listOf(LatentLight, LatentDark, WorkspaceColors).flatMap { scheme ->
            listOf(
                scheme.background, scheme.surface, scheme.onSurface, scheme.surfaceVariant, scheme.onSurfaceVariant,
                scheme.outline, scheme.outlineVariant, scheme.surfaceContainerLowest, scheme.surfaceContainerLow,
                scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest,
            )
        } + PictureBackground
        greys.forEach { grey ->
            assertTrue("$grey is not a neutral grey", grey.red == grey.green && grey.green == grey.blue)
        }
    }
}
