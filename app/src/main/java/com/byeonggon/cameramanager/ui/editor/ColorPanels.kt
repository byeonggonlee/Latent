package com.byeonggon.cameramanager.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.byeonggon.cameramanager.enhance.ColorGrading
import com.byeonggon.cameramanager.enhance.ColorMixer
import com.byeonggon.cameramanager.enhance.MixerBand
import com.byeonggon.cameramanager.enhance.Tint
import kotlin.math.roundToInt

private val MixerBand.label: String
    get() = when (this) {
        MixerBand.RED -> "빨강"
        MixerBand.ORANGE -> "주황"
        MixerBand.YELLOW -> "노랑"
        MixerBand.GREEN -> "초록"
        MixerBand.AQUA -> "청록"
        MixerBand.BLUE -> "파랑"
        MixerBand.PURPLE -> "보라"
        MixerBand.MAGENTA -> "자홍"
    }

/** Hue, vividness and brightness of one colour at a time, picked from a row of swatches. */
@Composable
internal fun MixerPanel(mixer: ColorMixer, enabled: Boolean, onChange: (ColorMixer) -> Unit, onDone: () -> Unit) {
    var band by rememberSaveable { mutableStateOf(MixerBand.RED) }
    val index = band.ordinal
    fun with(values: List<Float>, value: Float) = values.toMutableList().apply { this[index] = snapped(value, 1f, 100) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MixerBand.entries.forEach { entry ->
                val touched = mixer.hue[entry.ordinal] != 0f || mixer.saturation[entry.ordinal] != 0f || mixer.luminance[entry.ordinal] != 0f
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.hsv(entry.hue, 0.75f, 0.9f))
                        .border(
                            width = if (band == entry) 3.dp else if (touched) 1.5.dp else 0.dp,
                            color = if (band == entry || touched) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable(role = Role.RadioButton) { band = entry }
                        .semantics { contentDescription = entry.label },
                )
            }
        }
        SettingSlider(
            label = "${band.label} 색조",
            value = mixer.hue[index],
            range = -1f..1f,
            valueText = describeBand(mixer.hue[index]),
            enabled = enabled,
            onChange = { onChange(mixer.copy(hue = with(mixer.hue, it))) },
            onDone = onDone,
        )
        SettingSlider(
            label = "${band.label} 채도",
            value = mixer.saturation[index],
            range = -1f..1f,
            valueText = describeBand(mixer.saturation[index]),
            enabled = enabled,
            onChange = { onChange(mixer.copy(saturation = with(mixer.saturation, it))) },
            onDone = onDone,
        )
        SettingSlider(
            label = "${band.label} 광도",
            value = mixer.luminance[index],
            range = -1f..1f,
            valueText = describeBand(mixer.luminance[index]),
            enabled = enabled,
            onChange = { onChange(mixer.copy(luminance = with(mixer.luminance, it))) },
            onDone = onDone,
        )
    }
}

private fun describeBand(value: Float) = if (value == 0f) "0" else signed(value * 100)

/** The tones a [ColorGrading] tints separately. */
private enum class GradingRange(val label: String, val get: (ColorGrading) -> Tint, val set: (ColorGrading, Tint) -> ColorGrading) {
    SHADOWS("어두운 영역", { it.shadows }, { grading, tint -> grading.copy(shadows = tint) }),
    MIDTONES("중간 영역", { it.midtones }, { grading, tint -> grading.copy(midtones = tint) }),
    HIGHLIGHTS("밝은 영역", { it.highlights }, { grading, tint -> grading.copy(highlights = tint) }),
}

/** A colour and how much of it for the dark, the middle or the bright tones, and where the three meet. */
@Composable
internal fun GradingPanel(grading: ColorGrading, enabled: Boolean, onChange: (ColorGrading) -> Unit, onDone: () -> Unit) {
    var range by rememberSaveable { mutableStateOf(GradingRange.SHADOWS) }
    val tint = range.get(grading)
    Column {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GradingRange.entries.forEach { entry ->
                FilterChip(selected = range == entry, onClick = { range = entry }, label = { Text(entry.label) })
            }
        }
        SettingSlider(
            label = "색",
            value = tint.hue,
            range = 0f..360f,
            valueText = "${tint.hue.roundToInt()}°",
            enabled = enabled,
            onChange = { onChange(range.set(grading, tint.copy(hue = it.roundToInt().toFloat()))) },
            onDone = onDone,
            hues = true,
        )
        SettingSlider(
            label = "양",
            value = tint.amount,
            range = 0f..1f,
            valueText = (tint.amount * 100).roundToInt().toString(),
            enabled = enabled,
            onChange = { onChange(range.set(grading, tint.copy(amount = (it * 100).roundToInt() / 100f))) },
            onDone = onDone,
        )
        SettingSlider(
            label = "균형",
            value = grading.balance,
            range = -1f..1f,
            valueText = describeBand(grading.balance),
            enabled = enabled,
            onChange = { onChange(grading.copy(balance = snapped(it, 1f, 100))) },
            onDone = onDone,
        )
    }
}
