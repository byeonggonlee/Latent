package com.byeonggon.latent.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A library shorter than this many grid items is scrolled by hand quickly enough. */
private const val LONG_ENOUGH = 60

private val HANDLE_HEIGHT = 56.dp
private val HANDLE_GRIP = 28.dp

/**
 * A handle at the right edge that follows the grid as it scrolls and can be dragged to travel through
 * a long library at once, with the month of what it passes over beside it. [labelAt] names the place
 * of the grid item at an index, such as "October 2026". The handle only shows while it is in use.
 */
@Composable
fun FastScroller(state: LazyGridState, labelAt: (Int) -> String?, modifier: Modifier = Modifier) {
    val total = state.layoutInfo.totalItemsCount
    if (total < LONG_ENOUGH) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var recentlyMoved by remember { mutableStateOf(false) }
    // The handle fades out a moment after the grid stops.
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            recentlyMoved = true
        } else {
            delay(1_500)
            recentlyMoved = false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val track = with(LocalDensity.current) { (maxHeight - HANDLE_HEIGHT).toPx() }.coerceAtLeast(1f)
        val following = state.firstVisibleItemIndex.toFloat() / (total - 1).coerceAtLeast(1)
        val fraction = if (dragging) dragFraction else following.coerceIn(0f, 1f)
        val shown = dragging || recentlyMoved

        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (fraction * track).roundToInt()) },
        ) {
            Box(contentAlignment = Alignment.CenterEnd) {
                if (dragging) {
                    labelAt((fraction * (total - 1)).roundToInt())?.let { label ->
                        Surface(
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(end = HANDLE_GRIP + 8.dp).semantics { invisibleToUser() },
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
                Box(
                    Modifier
                        .testTag(FAST_SCROLLER_HANDLE)
                        .semantics { invisibleToUser() }
                        .width(HANDLE_GRIP)
                        .height(HANDLE_HEIGHT)
                        .pointerInput(total, track) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragFraction = (state.firstVisibleItemIndex.toFloat() / (total - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, amount ->
                                change.consume()
                                dragFraction = (dragFraction + amount / track).coerceIn(0f, 1f)
                                val target = (dragFraction * (total - 1)).roundToInt()
                                scope.launch { state.scrollToItem(target) }
                            }
                        },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .height(HANDLE_HEIGHT - 16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else 0.7f)),
                    )
                }
            }
        }
    }
}

const val FAST_SCROLLER_HANDLE = "fast-scroller-handle"
