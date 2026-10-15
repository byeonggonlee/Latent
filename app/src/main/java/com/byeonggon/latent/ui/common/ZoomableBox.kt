package com.byeonggon.latent.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val WHEEL_STEP = 0.15f

/** How far a [ZoomableBox] is zoomed, and where; held outside it so that keys and buttons can zoom as well. */
class ZoomState {
    /** 1 is the picture as it fits the screen. */
    var scale by mutableFloatStateOf(1f)
        internal set
    var offset by mutableStateOf(Offset.Zero)
        internal set

    val isZoomed: Boolean get() = scale > 1.001f

    /** Back to the picture as it fits the screen. */
    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** Zooms in for a [factor] above 1 and out for one below, about the middle of the picture. */
    fun zoomBy(factor: Float) {
        scale = (scale * factor).coerceIn(1f, MAX_ZOOM)
        if (scale == 1f) offset = Offset.Zero
    }
}

@Composable
fun rememberZoomState(resetKey: Any): ZoomState = remember(resetKey) { ZoomState() }

/**
 * Pinch to zoom, drag to pan, double-tap to toggle, and the mouse wheel. Resets when [resetKey] changes.
 * A finger held still on it calls [onHold] with true, and with false when it is lifted again. While it is
 * zoomed in, a small label says by how much, 100% being the picture as it fits the screen.
 */
@Composable
fun ZoomableBox(resetKey: Any, onHold: (Boolean) -> Unit = {}, state: ZoomState = rememberZoomState(resetKey), content: @Composable () -> Unit) {
    val hold = rememberUpdatedState(onHold)
    Box(Modifier.fillMaxSize()) {
        ZoomedContent(resetKey, hold, state, content)
        if (state.isZoomed) {
            Text(
                "${(state.scale * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ZoomedContent(resetKey: Any, hold: State<(Boolean) -> Unit>, state: ZoomState, content: @Composable () -> Unit) {
    var scale by state::scale
    var offset by state::offset
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(resetKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // One finger on a picture that fills the screen is not for this: it may be turning a page.
                        if (scale > 1f || event.changes.count { it.pressed } > 1) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            val pan = event.calculatePan()
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(resetKey) {
                // A mouse wheel or a touchpad zooms in and out, as it does in photo apps on a computer.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val turned = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                        if (turned == 0f) continue
                        scale = (scale * (1f - turned * WHEEL_STEP)).coerceIn(1f, MAX_ZOOM)
                        if (scale == 1f) offset = Offset.Zero
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                        offset = Offset.Zero
                    },
                    onLongPress = { hold.value(true) },
                    onPress = {
                        tryAwaitRelease()
                        hold.value(false)
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
