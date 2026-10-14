package com.byeonggon.latent.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * How the app's window is shaped, which decides where things go: a phone held upright, or anything
 * wider, such as a phone on its side, an unfolded foldable, a tablet or a laptop's window.
 */
class WindowLayout(val widthDp: Int, val heightDp: Int) {
    /** Wide enough for navigation down the side instead of along the bottom, which costs a wide window little. */
    val sideNavigation: Boolean get() = widthDp >= SIDE_NAVIGATION_WIDTH

    /**
     * Controls and information go beside the picture instead of under it: when the window is at least
     * about as wide as it is high, a panel underneath would leave the picture a narrow strip.
     */
    val sidePanel: Boolean get() = widthDp >= SIDE_PANEL_WIDTH && widthDp * 10 >= heightDp * 9

    private companion object {
        const val SIDE_NAVIGATION_WIDTH = 600
        const val SIDE_PANEL_WIDTH = 560
    }
}

/** The shape of the window right now; it changes as the window is resized, folded or turned. */
@Composable
fun rememberWindowLayout(): WindowLayout {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) { WindowLayout(size.width.toDp().value.toInt(), size.height.toDp().value.toInt()) }
}

/** Lays out [content] no wider than a comfortable reading width, centred in a wider window. */
@Composable
fun ReadableWidth(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxWidth(), content = content)
    }
}

/** Width of a side panel next to the picture. */
val SIDE_PANEL_WIDTH = 380.dp
