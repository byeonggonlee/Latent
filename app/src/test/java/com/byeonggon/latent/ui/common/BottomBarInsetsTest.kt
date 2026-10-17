package com.byeonggon.latent.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomBarInsetsTest {
    private val density = Density(1f)

    @Test
    fun aBarAlongTheBottomKeepsClearOfTheSidesAndTheBottomButNotTheStatusBar() {
        // A status bar 48 high at the top, a gesture bar 30 high at the bottom, a camera cutout 20 wide on the left.
        val all = WindowInsets(left = 20, top = 48, right = 0, bottom = 30)

        val forBar = all.forBottomBar()

        assertEquals(0, forBar.getTop(density))
        assertEquals(30, forBar.getBottom(density))
        assertEquals(20, forBar.getLeft(density, LayoutDirection.Ltr))
    }
}
