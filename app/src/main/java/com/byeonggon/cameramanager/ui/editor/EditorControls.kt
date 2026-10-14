package com.byeonggon.cameramanager.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.byeonggon.cameramanager.enhance.Adjustment
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A slider for one of the settings: what it is called and how far it goes. */
internal enum class Control(
    val label: String,
    val setting: Adjustment,
    val limit: Float = 1f,
    val stepsPerUnit: Int = 100,
    /** Starts at zero instead of reaching as far below it as above. */
    val addsOnly: Boolean = false,
) {
    EXPOSURE("노출", Adjustment.EXPOSURE, limit = 3f, stepsPerUnit = 20),
    CONTRAST("대비", Adjustment.CONTRAST),
    HIGHLIGHTS("밝은 영역", Adjustment.HIGHLIGHTS),
    SHADOWS("어두운 영역", Adjustment.SHADOWS),
    WHITES("흰색 계열", Adjustment.WHITES),
    BLACKS("검정 계열", Adjustment.BLACKS),
    TEMPERATURE("색온도", Adjustment.TEMPERATURE),
    TINT("색조", Adjustment.TINT),
    VIBRANCE("생동감", Adjustment.VIBRANCE),
    SATURATION("채도", Adjustment.SATURATION),
    TEXTURE("텍스처", Adjustment.TEXTURE),
    CLARITY("부분 대비", Adjustment.CLARITY),
    DEHAZE("디헤이즈", Adjustment.DEHAZE),
    VIGNETTE("비네팅", Adjustment.VIGNETTE),
    GRAIN("그레인", Adjustment.GRAIN, addsOnly = true),
    GLOW("글로우", Adjustment.GLOW, addsOnly = true),
    SHARPENING("선명하게", Adjustment.SHARPENING, addsOnly = true),
    NOISE_REDUCTION("노이즈 감소", Adjustment.NOISE_REDUCTION, addsOnly = true),
    COLOR_NOISE_REDUCTION("색상 노이즈 감소", Adjustment.COLOR_NOISE_REDUCTION, addsOnly = true);

    val range: ClosedFloatingPointRange<Float> get() = (if (addsOnly) 0f else -limit)..limit

    /** What the slider's position means in the setting's own steps; close to zero counts as untouched. */
    fun valueAt(position: Float): Float = snapped(position, limit, stepsPerUnit)

    fun describe(value: Float): String = when {
        value == 0f -> "0"
        this == EXPOSURE -> String.format(Locale.US, "%+.2f", value)
        addsOnly -> (value * 100).roundToInt().toString()
        else -> signed(value * 100)
    }
}

/** [position] in steps of one in [stepsPerUnit]; within a few hundredths of [limit] around zero it is zero. */
internal fun snapped(position: Float, limit: Float, stepsPerUnit: Int): Float =
    if (abs(position) < limit * SNAP_TO_ZERO) 0f else (position * stepsPerUnit).roundToInt() / stepsPerUnit.toFloat()

internal fun signed(value: Float): String = String.format(Locale.US, "%+d", value.roundToInt())

private const val SNAP_TO_ZERO = 0.03f

/**
 * A slider with its name in front and its value behind. Tapping the value puts it back to [untouched].
 * [onDone] is called when the slider is let go of. [hues] gives the track the colours of the rainbow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    untouched: Float = 0f,
    hues: Boolean = false,
) {
    Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(92.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
            track = { state ->
                val span = range.endInclusive - range.start
                SettingTrack(
                    from = (untouched - range.start) / span,
                    to = (state.value - range.start) / span,
                    enabled = enabled,
                    hues = hues,
                )
            },
        )
        Text(
            valueText,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            color = if (value == untouched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .width(52.dp)
                .clickable(enabled = enabled && value != untouched, onClickLabel = "$label 되돌리기") {
                    onChange(untouched)
                    onDone()
                },
        )
    }
}

/** A track that is filled between where a setting changes nothing and where the slider stands, both from 0 to 1. */
@Composable
private fun SettingTrack(from: Float, to: Float, enabled: Boolean, hues: Boolean) {
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.4f else 0.2f)
    val filled = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f)
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val middle = size.height / 2
        if (hues) {
            val rainbow = Brush.horizontalGradient(List(13) { Color.hsv(it * 30f % 360f, 0.85f, 0.95f) })
            drawLine(rainbow, Offset(0f, middle), Offset(size.width, middle), strokeWidth = size.height, cap = StrokeCap.Round, alpha = if (enabled) 1f else 0.4f)
            return@Canvas
        }
        drawLine(rest, Offset(0f, middle), Offset(size.width, middle), strokeWidth = size.height, cap = StrokeCap.Round)
        drawLine(filled, Offset(size.width * from, middle), Offset(size.width * to, middle), strokeWidth = size.height)
        if (from > 0f) drawCircle(if (to == from) rest else filled, radius = size.height, center = Offset(size.width * from, middle))
    }
}
