package com.byeonggon.latent.ui.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.R
import com.byeonggon.latent.enhance.Adjustments
import kotlinx.coroutines.delay
import kotlin.math.abs

/** How far the finger travels up or down, in dp, to move on to the next setting. */
private const val STEP_DP = 40f

/** How long the list of settings stays up after the last move. */
private const val LIST_STAYS_MS = 1_200L

/**
 * Where a setting ends up when it is dragged by [dragged] pixels on a picture [width] pixels wide, from
 * where it stood at [start]; a drag across the whole width goes as far as the [range] reaches from zero.
 */
internal fun valueAfterDrag(start: Float, dragged: Float, width: Float, range: ClosedFloatingPointRange<Float>): Float {
    if (width <= 0f) return start
    return (start + dragged / width * range.endInclusive).coerceIn(range)
}

/**
 * Turns the finger's travel up or down into steps through a list of [size] settings. [carry] is the
 * travel left over from before; returns the new place in the list and the travel still left over.
 */
internal fun stepThrough(selected: Int, carry: Float, travel: Float, step: Float, size: Int): Pair<Int, Float> {
    var place = selected
    var left = carry + travel
    while (abs(left) >= step) {
        val direction = if (left > 0) 1 else -1
        left -= direction * step
        val next = place + direction
        if (next in 0 until size) place = next else left = 0f
    }
    return place to left
}

/**
 * Lets the picture be edited by swiping on it, as in Snapseed: up and down pick one of [controls],
 * left and right change it. [content] is the picture; pinching and holding it still work as before.
 * The settings list and the value show over the picture while it is being used.
 */
@Composable
internal fun GestureLayer(
    active: Boolean,
    controls: List<Control>,
    adjustments: Adjustments,
    enabled: Boolean,
    onAdjust: (Control, Float) -> Unit,
    onDone: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!active || controls.isEmpty()) {
        content()
        return
    }
    var selected by rememberSaveable(controls) { mutableIntStateOf(0) }
    var listUp by remember { mutableStateOf(false) }
    var used by rememberSaveable { mutableStateOf(false) }
    var lastMove by remember { mutableIntStateOf(0) }
    // The list goes away a moment after the last move.
    LaunchedEffect(lastMove) {
        if (lastMove > 0) {
            delay(LIST_STAYS_MS)
            listUp = false
        }
    }
    val current = rememberUpdatedState(controls to adjustments)
    val change = rememberUpdatedState(onAdjust)
    val done = rememberUpdatedState(onDone)
    val slop = LocalViewConfiguration.current.touchSlop
    val stepPx = with(LocalDensity.current) { STEP_DP.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(controls, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var travelled = Offset.Zero
                    var vertical: Boolean? = null
                    var carry = 0f
                    var startValue = 0f
                    var moved = false
                    do {
                        val event = awaitPointerEvent()
                        // A second finger is a pinch, and a picture zoomed in is panned: both are not for this.
                        if (event.changes.count { it.pressed } > 1) break
                        val touch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (touch.isConsumed) break
                        travelled += touch.positionChange()
                        if (vertical == null && travelled.getDistance() > slop * 2) {
                            vertical = abs(travelled.y) > abs(travelled.x)
                            val (list, values) = current.value
                            startValue = list[selected.coerceIn(list.indices)].setting.get(values)
                            used = true
                        }
                        when (vertical) {
                            true -> {
                                val (place, left) = stepThrough(selected, carry, touch.positionChange().y, stepPx, current.value.first.size)
                                carry = left
                                if (place != selected) selected = place
                                listUp = true
                                lastMove++
                                touch.consume()
                            }
                            false -> {
                                val control = current.value.first[selected.coerceIn(current.value.first.indices)]
                                change.value(control, valueAfterDrag(startValue, travelled.x, size.width.toFloat(), control.range))
                                moved = true
                                touch.consume()
                            }
                            null -> Unit
                        }
                    } while (event.changes.any { it.pressed })
                    if (moved) done.value()
                }
            },
    ) {
        content()
        val control = controls[selected.coerceIn(controls.indices)]
        GestureValue(control, adjustments, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        if (listUp) GestureList(controls, selected.coerceIn(controls.indices), adjustments, Modifier.align(Alignment.Center))
        if (!used) GestureHints(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
    }
}

/** The setting being changed, with how far it stands from untouched, above the picture. */
@Composable
private fun GestureValue(control: Control, adjustments: Adjustments, modifier: Modifier = Modifier) {
    val value = control.setting.get(adjustments)
    Surface(color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(control.label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                control.describe(value),
                style = MaterialTheme.typography.labelLarge,
                color = if (value == 0f) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.inversePrimary,
            )
        }
    }
}

/** Every setting of the tool with its value, the one being changed marked. */
@Composable
private fun GestureList(controls: List<Control>, selected: Int, adjustments: Adjustments, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.78f), contentColor = Color.White, shape = RoundedCornerShape(16.dp), modifier = modifier.width(196.dp)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            controls.forEachIndexed { index, control ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(if (index == selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(control.label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        control.describe(control.setting.get(adjustments)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (index == selected) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureHints(modifier: Modifier = Modifier) {
    // One above the other: side by side they do not fit a narrow phone in a language with longer words.
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (hint in listOf(R.string.gesture_hint_vertical, R.string.gesture_hint_horizontal)) {
            Surface(color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, shape = RoundedCornerShape(14.dp)) {
                Text(stringResource(hint), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

/** Under the picture while swiping edits it: the tool, with a way to its neighbours and back to the sliders. */
@Composable
internal fun GestureBar(
    tool: String,
    @DrawableRes icon: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSliders: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.gesture_previous_tool)) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(tool, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            }
            IconButton(onClick = onNext) { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.gesture_next_tool)) }
            IconButton(onClick = onSliders) { Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.gesture_show_sliders)) }
        }
    }
}
