package com.byeonggon.latent.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.enhance.Adjustment
import com.byeonggon.latent.ui.common.tabular
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.R

/** A slider for one of the settings: what it is called and how far it goes. */
internal enum class Control(
    @StringRes val label: Int,
    val setting: Adjustment,
    val limit: Float = 1f,
    val stepsPerUnit: Int = 100,
    /** Starts at zero instead of reaching as far below it as above. */
    val addsOnly: Boolean = false,
) {
    EXPOSURE(R.string.control_exposure, Adjustment.EXPOSURE, limit = 3f, stepsPerUnit = 20),
    CONTRAST(R.string.control_contrast, Adjustment.CONTRAST),
    HIGHLIGHTS(R.string.control_highlights, Adjustment.HIGHLIGHTS),
    SHADOWS(R.string.control_shadows, Adjustment.SHADOWS),
    WHITES(R.string.control_whites, Adjustment.WHITES),
    BLACKS(R.string.control_blacks, Adjustment.BLACKS),
    TEMPERATURE(R.string.control_temperature, Adjustment.TEMPERATURE),
    TINT(R.string.control_tint, Adjustment.TINT),
    VIBRANCE(R.string.control_vibrance, Adjustment.VIBRANCE),
    SATURATION(R.string.control_saturation, Adjustment.SATURATION),
    TEXTURE(R.string.control_texture, Adjustment.TEXTURE),
    CLARITY(R.string.control_clarity, Adjustment.CLARITY),
    DEHAZE(R.string.control_dehaze, Adjustment.DEHAZE),
    VIGNETTE(R.string.control_vignette, Adjustment.VIGNETTE),
    GRAIN(R.string.control_grain, Adjustment.GRAIN, addsOnly = true),
    GLOW(R.string.control_glow, Adjustment.GLOW, addsOnly = true),
    SHARPENING(R.string.control_sharpening, Adjustment.SHARPENING, addsOnly = true),
    NOISE_REDUCTION(R.string.control_noise_reduction, Adjustment.NOISE_REDUCTION, addsOnly = true),
    COLOR_NOISE_REDUCTION(R.string.control_color_noise_reduction, Adjustment.COLOR_NOISE_REDUCTION, addsOnly = true);

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
 * A slider under its name and value. Tapping the value puts it back to [untouched], or, where
 * [parseEntry] says how to read a typed number, opens a box to type one in and a double tap puts it back.
 * [onDone] is called when the slider is let go of. [hues] gives the track the colours of the rainbow.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    parseEntry: ((String) -> Float?)? = null,
) {
    var typing by remember { mutableStateOf(false) }
    if (typing && parseEntry != null) {
        ValueEntryDialog(
            label = label,
            initial = valueText.removePrefix("+").removeSuffix("°"),
            parse = parseEntry,
            onEntered = { entered ->
                onChange(entered)
                onDone()
            },
            onReset = {
                onChange(untouched)
                onDone()
            },
            onDismiss = { typing = false },
        )
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium.tabular,
                textAlign = TextAlign.End,
                color = if (value == untouched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                // Room kept for the longest value, so its digits and sign can change without the label moving.
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .let { base ->
                        if (parseEntry == null) {
                            base.clickable(enabled = enabled && value != untouched, onClickLabel = stringResource(R.string.reset_setting, label)) {
                                onChange(untouched)
                                onDone()
                            }
                        } else {
                            // A tap types a value in; two taps put the setting back.
                            base.combinedClickable(
                                enabled = enabled,
                                onClickLabel = stringResource(R.string.type_value, label),
                                onClick = { typing = true },
                                onDoubleClick = {
                                    if (value != untouched) {
                                        onChange(untouched)
                                        onDone()
                                    }
                                },
                            )
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = label },
            thumb = {
                Box(
                    Modifier
                        .size(18.dp)
                        .shadow(2.dp, CircleShape)
                        .background(if (enabled) Color.White else MaterialTheme.colorScheme.outline, CircleShape),
                )
            },
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
    }
}

/** A track that is filled between where a setting changes nothing and where the slider stands, both from 0 to 1. */
@Composable
private fun SettingTrack(from: Float, to: Float, enabled: Boolean, hues: Boolean) {
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.35f else 0.2f)
    val filled = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f)
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val middle = size.height / 2
        val line = 2.dp.toPx()
        if (hues) {
            val rainbow = Brush.horizontalGradient(List(13) { Color.hsv(it * 30f % 360f, 0.85f, 0.95f) })
            drawLine(rainbow, Offset(0f, middle), Offset(size.width, middle), strokeWidth = line * 2, cap = StrokeCap.Round, alpha = if (enabled) 1f else 0.4f)
            return@Canvas
        }
        drawLine(rest, Offset(0f, middle), Offset(size.width, middle), strokeWidth = line, cap = StrokeCap.Round)
        // A mark where the setting changes nothing, unless that is where the track starts.
        if (from > 0f) drawLine(rest, Offset(size.width * from, 0f), Offset(size.width * from, size.height), strokeWidth = 1.dp.toPx())
        drawLine(filled, Offset(size.width * from, middle), Offset(size.width * to, middle), strokeWidth = line)
    }
}

/** A box to type a setting's value into, in the units its slider shows: [initial] is how it reads now. */
@Composable
private fun ValueEntryDialog(
    label: String,
    initial: String,
    parse: (String) -> Float?,
    onEntered: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val entered = parse(text.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = entered == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { entered?.let { onEntered(it); onDismiss() } }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                // Number keypads often have no minus sign.
                TextButton(onClick = { text = text.flipSign() }) { Text("±") }
            }
        },
        confirmButton = {
            TextButton(enabled = entered != null, onClick = { entered?.let(onEntered); onDismiss() }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onReset(); onDismiss() }) { Text(stringResource(R.string.reset_value)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private fun TextFieldValue.flipSign(): TextFieldValue {
    val flipped = if (text.startsWith("-")) text.removePrefix("-") else "-$text"
    return copy(text = flipped, selection = TextRange(flipped.length))
}

/** Reads a number typed in the units of a setting's slider, in the setting's own units, kept within [range]. */
internal fun readEntry(text: String, unit: Float, range: ClosedFloatingPointRange<Float>): Float? =
    text.trim().replace(',', '.').replace('−', '-').removePrefix("+").toFloatOrNull()?.takeIf { it.isFinite() }
        ?.let { (it * unit).coerceIn(range) }
