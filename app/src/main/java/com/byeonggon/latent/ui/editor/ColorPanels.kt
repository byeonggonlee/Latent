package com.byeonggon.latent.ui.editor

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
import com.byeonggon.latent.enhance.ColorGrading
import com.byeonggon.latent.enhance.ColorMixer
import com.byeonggon.latent.enhance.MixerBand
import com.byeonggon.latent.enhance.Tint
import kotlin.math.roundToInt
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.R

private val MixerBand.label: String
    @Composable get() = stringResource(
        when (this) {
            MixerBand.RED -> R.string.color_red
            MixerBand.ORANGE -> R.string.color_orange
            MixerBand.YELLOW -> R.string.color_yellow
            MixerBand.GREEN -> R.string.color_green
            MixerBand.AQUA -> R.string.color_aqua
            MixerBand.BLUE -> R.string.color_blue
            MixerBand.PURPLE -> R.string.color_purple
            MixerBand.MAGENTA -> R.string.color_magenta
        },
    )

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
                val name = entry.label
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
                        .semantics { contentDescription = name },
                )
            }
        }
        SettingSlider(
            label = stringResource(R.string.band_hue, band.label),
            value = mixer.hue[index],
            range = -1f..1f,
            valueText = describeBand(mixer.hue[index]),
            enabled = enabled,
            onChange = { onChange(mixer.copy(hue = with(mixer.hue, it))) },
            onDone = onDone,
        )
        SettingSlider(
            label = stringResource(R.string.band_saturation, band.label),
            value = mixer.saturation[index],
            range = -1f..1f,
            valueText = describeBand(mixer.saturation[index]),
            enabled = enabled,
            onChange = { onChange(mixer.copy(saturation = with(mixer.saturation, it))) },
            onDone = onDone,
        )
        SettingSlider(
            label = stringResource(R.string.band_luminance, band.label),
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
private enum class GradingRange(@StringRes val label: Int, val get: (ColorGrading) -> Tint, val set: (ColorGrading, Tint) -> ColorGrading) {
    SHADOWS(R.string.range_shadows, { it.shadows }, { grading, tint -> grading.copy(shadows = tint) }),
    MIDTONES(R.string.range_midtones, { it.midtones }, { grading, tint -> grading.copy(midtones = tint) }),
    HIGHLIGHTS(R.string.range_highlights, { it.highlights }, { grading, tint -> grading.copy(highlights = tint) }),
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
                FilterChip(selected = range == entry, onClick = { range = entry }, label = { Text(stringResource(entry.label)) })
            }
        }
        SettingSlider(
            label = stringResource(R.string.grading_hue),
            value = tint.hue,
            range = 0f..360f,
            valueText = "${tint.hue.roundToInt()}°",
            enabled = enabled,
            onChange = { onChange(range.set(grading, tint.copy(hue = it.roundToInt().toFloat()))) },
            onDone = onDone,
            hues = true,
        )
        SettingSlider(
            label = stringResource(R.string.grading_amount),
            value = tint.amount,
            range = 0f..1f,
            valueText = (tint.amount * 100).roundToInt().toString(),
            enabled = enabled,
            onChange = { onChange(range.set(grading, tint.copy(amount = (it * 100).roundToInt() / 100f))) },
            onDone = onDone,
        )
        SettingSlider(
            label = stringResource(R.string.grading_balance),
            value = grading.balance,
            range = -1f..1f,
            valueText = describeBand(grading.balance),
            enabled = enabled,
            onChange = { onChange(grading.copy(balance = snapped(it, 1f, 100))) },
            onDone = onDone,
        )
    }
}
