package com.byeonggon.latent.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.enhance.CurvePoint
import com.byeonggon.latent.enhance.ToneCurve
import com.byeonggon.latent.enhance.ToneCurves
import kotlin.math.abs
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.R

/** What a finger can do to a tone curve: take hold of a point, move it, put a new one down, or take one off. */
internal object CurveEditing {
    const val MOST_POINTS = 10

    /** Points keep at least this much distance from their neighbours across, so the curve cannot fold back on itself. */
    const val GAP = 0.03f

    /** The point of [curve] nearest to ([x], [y]) that is no more than [reach] away across and up, or null. */
    fun nearest(curve: ToneCurve, x: Float, y: Float, reach: Float): Int? =
        curve.points.indices
            .filter { abs(curve.points[it].x - x) <= reach && abs(curve.points[it].y - y) <= reach }
            .minByOrNull { abs(curve.points[it].x - x) + abs(curve.points[it].y - y) }

    /** [curve] with one of its points moved to ([x], [y]), as far as its neighbours and the edges let it go. */
    fun moved(curve: ToneCurve, index: Int, x: Float, y: Float): ToneCurve {
        val points = curve.points
        val least = if (index == 0) 0f else points[index - 1].x + GAP
        val most = if (index == points.lastIndex) 1f else points[index + 1].x - GAP
        val moved = CurvePoint(x.coerceIn(least, most), y.coerceIn(0f, 1f))
        return ToneCurve(points.toMutableList().apply { this[index] = moved })
    }

    /** [curve] with a new point at ([x], [y]) and that point's place among the others; null if there is no room for one. */
    fun added(curve: ToneCurve, x: Float, y: Float): Pair<ToneCurve, Int>? {
        val points = curve.points
        if (points.size >= MOST_POINTS || points.any { abs(it.x - x) < GAP }) return null
        val index = points.indexOfFirst { it.x > x }.let { if (it < 0) points.size else it }
        val added = points.toMutableList().apply { add(index, CurvePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))) }
        return ToneCurve(added) to index
    }

    /** [curve] without one of its points. A curve needs two points, so the last two stay. */
    fun removed(curve: ToneCurve, index: Int): ToneCurve =
        if (curve.points.size <= 2) curve else ToneCurve(curve.points.filterIndexed { at, _ -> at != index })
}

/** Which of the [ToneCurves] is being edited. */
private enum class CurveChannel(@StringRes val label: Int, val color: Color, val get: (ToneCurves) -> ToneCurve, val set: (ToneCurves, ToneCurve) -> ToneCurves) {
    ALL(R.string.curve_all, Color.White, { it.all }, { curves, curve -> curves.copy(all = curve) }),
    RED(R.string.color_red, Color(0xFFE05050), { it.red }, { curves, curve -> curves.copy(red = curve) }),
    GREEN(R.string.color_green, Color(0xFF50C060), { it.green }, { curves, curve -> curves.copy(green = curve) }),
    BLUE(R.string.color_blue, Color(0xFF5080F0), { it.blue }, { curves, curve -> curves.copy(blue = curve) }),
}

/**
 * The tone curves, one at a time. Dragging a point moves it, touching the curve elsewhere puts a
 * new point there, and dragging a point well out of the top or bottom takes it off again.
 */
@Composable
internal fun CurveEditor(curves: ToneCurves, enabled: Boolean, onChange: (ToneCurves) -> Unit, onDone: () -> Unit) {
    var channel by rememberSaveable { mutableStateOf(CurveChannel.ALL) }
    val curve = channel.get(curves)
    Column {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CurveChannel.entries.forEach { entry ->
                FilterChip(selected = channel == entry, onClick = { channel = entry }, label = { Text(stringResource(entry.label)) })
            }
            TextButton(
                onClick = {
                    onChange(channel.set(curves, ToneCurve()))
                    onDone()
                },
                enabled = enabled && !curve.isStraight,
            ) { Text(stringResource(R.string.curve_reset)) }
        }
        CurveCanvas(
            curve = curve,
            color = channel.color,
            enabled = enabled,
            onChange = { onChange(channel.set(curves, it)) },
            onDone = onDone,
        )
    }
}

@Composable
private fun CurveCanvas(curve: ToneCurve, color: Color, enabled: Boolean, onChange: (ToneCurve) -> Unit, onDone: () -> Unit) {
    val curveLabel = stringResource(R.string.tone_curve)
    val current by rememberUpdatedState(curve)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val frame = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val line = if (color == Color.White) MaterialTheme.colorScheme.onSurface else color
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(164.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .semantics { contentDescription = curveLabel }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun across(position: Offset) = position.x / size.width
                    fun up(position: Offset) = 1f - position.y / size.height
                    val reach = REACH.toPx() / size.height
                    var held = CurveEditing.nearest(current, across(down.position), up(down.position), reach)
                    if (held == null) {
                        val added = CurveEditing.added(current, across(down.position), up(down.position)) ?: return@awaitEachGesture
                        change(added.first)
                        held = added.second
                    }
                    down.consume()
                    var position = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        position += pointer.positionChange()
                        pointer.consume()
                        change(CurveEditing.moved(current, held, across(position), up(position)))
                    }
                    // Let go of well above or below the graph, the point is taken off.
                    if (up(position) !in -TAKE_OFF..1f + TAKE_OFF) change(CurveEditing.removed(current, held))
                    done()
                }
            },
    ) {
        for (part in 0..4) {
            val x = size.width * part / 4
            val y = size.height * part / 4
            val stroke = if (part == 0 || part == 4) frame else grid
            drawLine(stroke, Offset(x, 0f), Offset(x, size.height))
            drawLine(stroke, Offset(0f, y), Offset(size.width, y))
        }
        drawLine(grid, Offset(0f, size.height), Offset(size.width, 0f))

        val table = curve.table(CURVE_STEPS + 1)
        val path = Path()
        for (step in 0..CURVE_STEPS) {
            val x = size.width * step / CURVE_STEPS
            val y = size.height * (1f - table[step])
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        for (point in curve.points) {
            val centre = Offset(size.width * point.x, size.height * (1f - point.y))
            drawCircle(line, radius = 6.dp.toPx(), center = centre)
            drawCircle(Color.Black.copy(alpha = 0.5f), radius = 6.dp.toPx(), center = centre, style = Stroke(1.dp.toPx()))
        }
    }
}

private val REACH = 28.dp
private const val TAKE_OFF = 0.25f
private const val CURVE_STEPS = 96
