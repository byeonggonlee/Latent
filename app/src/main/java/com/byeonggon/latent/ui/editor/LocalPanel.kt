package com.byeonggon.latent.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.byeonggon.latent.enhance.MixerBand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.enhance.DetectedKind
import com.byeonggon.latent.enhance.LocalEdit
import com.byeonggon.latent.enhance.Mask
import com.byeonggon.latent.enhance.MaskShape
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.byeonggon.latent.R

/** The settings a part of the picture can have of its own. */
private val LOCAL_CONTROLS = listOf(
    Control.EXPOSURE,
    Control.CONTRAST,
    Control.HIGHLIGHTS,
    Control.SHADOWS,
    Control.TEMPERATURE,
    Control.TINT,
    Control.SATURATION,
    Control.CLARITY,
    Control.DEHAZE,
)

/** A mask as it is first put on the picture: across the upper part, or around the middle. */
private fun newMask(shape: MaskShape) = when (shape) {
    MaskShape.LINEAR -> Mask(shape, startX = 0.5f, startY = 0.25f, endX = 0.5f, endY = 0.6f)
    MaskShape.RADIAL -> Mask(shape, startX = 0.5f, startY = 0.5f, endX = 0.75f, endY = 0.8f)
    MaskShape.BRUSH -> Mask(shape, startX = 0f, startY = 0f, endX = 0f, endY = 0f, feather = 0.5f)
    MaskShape.EVERYWHERE -> Mask(shape, startX = 0.5f, startY = 0.5f, endX = 0.5f, endY = 0.5f)
    // Made by the view model once the model has found something; nothing to place by hand.
    MaskShape.DETECTED -> Mask(shape, startX = 0f, startY = 0f, endX = 0f, endY = 0f, feather = 0f)
}

/** The masks a model finds, as the panel offers them: what is found, whether the mask is the rest, and its button. */
private val DETECTED_MASKS = listOf(
    Triple(DetectedKind.SUBJECT, false, R.string.add_subject),
    Triple(DetectedKind.SUBJECT, true, R.string.add_background),
    Triple(DetectedKind.SKY, false, R.string.add_sky),
)

/** The blue of a sky, which is what a colour range is first put on. */
private const val FIRST_HUE = 215f

@Composable
private fun nameOf(local: LocalEdit, index: Int) = stringResource(
    when (local.mask.shape) {
        MaskShape.LINEAR -> R.string.mask_linear
        MaskShape.RADIAL -> R.string.mask_radial
        MaskShape.BRUSH -> R.string.mask_brush
        MaskShape.EVERYWHERE -> R.string.mask_colour
        MaskShape.DETECTED -> when {
            local.mask.detection?.kind == DetectedKind.SKY -> if (local.mask.inverted) R.string.mask_not_sky else R.string.mask_sky
            local.mask.inverted -> R.string.mask_background
            else -> R.string.mask_subject
        }
    },
    index + 1,
)

/**
 * What can be done about masks that a model finds: the kinds it can find here ([available]), what it is
 * looking for right now ([busy], null while nothing), which outlines are lost ([lost], by their ids), and how to ask
 * for a subject or sky mask, inverted for the background, or to find the selected mask's outline again.
 */
internal class Detecting(
    val available: Set<DetectedKind> = emptySet(),
    val busy: DetectedKind? = null,
    val lost: Set<String> = emptySet(),
    val onAdd: (kind: DetectedKind, inverted: Boolean) -> Unit = { _, _ -> },
    val onFindAgain: () -> Unit = {},
)

/**
 * Settings for parts of the picture: the masks to choose from and to add to, and for the [selected]
 * one what it does to the part it covers. The masks themselves are moved on the picture.
 */
@Composable
internal fun LocalPanel(
    locals: List<LocalEdit>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onChange: (List<LocalEdit>) -> Unit,
    onDone: () -> Unit,
    brushRadius: Float = DEFAULT_BRUSH_RADIUS,
    onBrushRadius: (Float) -> Unit = {},
    erasing: Boolean = false,
    onErasing: (Boolean) -> Unit = {},
    detecting: Detecting = Detecting(),
) {
    fun replaced(local: LocalEdit) = locals.toMutableList().apply { this[selected] = local }
    val current = locals.getOrNull(selected)

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        locals.forEachIndexed { index, local ->
            FilterChip(selected = index == selected, onClick = { onSelect(index) }, enabled = enabled, label = { Text(nameOf(local, index)) })
        }
        for ((shape, label) in listOf(MaskShape.BRUSH to R.string.add_brush, MaskShape.LINEAR to R.string.add_linear, MaskShape.RADIAL to R.string.add_radial, MaskShape.EVERYWHERE to R.string.add_colour)) {
            OutlinedButton(
                onClick = {
                    onChange(locals + LocalEdit(newMask(shape), hue = FIRST_HUE.takeIf { shape == MaskShape.EVERYWHERE }))
                    onDone()
                    onSelect(locals.size)
                },
                enabled = enabled && locals.size < MOST_MASKS,
            ) { Text(stringResource(label)) }
        }
        for ((kind, inverted, label) in DETECTED_MASKS.filter { it.first in detecting.available }) {
            OutlinedButton(
                onClick = { detecting.onAdd(kind, inverted) },
                enabled = enabled && detecting.busy == null && locals.size < MOST_MASKS,
            ) { Text(stringResource(label)) }
        }
    }
    if (detecting.busy != null) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(if (detecting.busy == DetectedKind.SKY) R.string.finding_sky else R.string.finding_subject), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (current == null) {
        Text(
            stringResource(R.string.masks_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole picture has no other side to swap to.
        if (current.mask.shape != MaskShape.EVERYWHERE) {
            FilterChip(
                selected = current.mask.inverted,
                onClick = {
                    onChange(replaced(current.copy(mask = current.mask.copy(inverted = !current.mask.inverted))))
                    onDone()
                },
                enabled = enabled,
                label = { Text(stringResource(R.string.invert)) },
            )
        }
        TextButton(
            onClick = {
                onChange(locals.filterIndexed { index, _ -> index != selected })
                onDone()
                onSelect((selected - 1).coerceAtLeast(0))
            },
            enabled = enabled,
        ) { Text(stringResource(R.string.delete_mask)) }
    }
    val outline = current.mask.detection?.raster
    if (current.mask.shape == MaskShape.DETECTED && (outline == null || outline in detecting.lost)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.mask_outline_lost),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            if (current.mask.detection?.kind in detecting.available) {
                TextButton(onClick = detecting.onFindAgain, enabled = enabled && detecting.busy == null) { Text(stringResource(R.string.detect_again)) }
            }
        }
    }
    // A detected mask is touched up with the brush, which adds to it or takes from it.
    if (current.mask.shape == MaskShape.BRUSH || current.mask.shape == MaskShape.DETECTED) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = !erasing, onClick = { onErasing(false) }, enabled = enabled, label = { Text(stringResource(R.string.paint)) })
            FilterChip(selected = erasing, onClick = { onErasing(true) }, enabled = enabled, label = { Text(stringResource(R.string.erase)) })
        }
        SettingSlider(
            label = stringResource(R.string.brush_size),
            value = brushRadius,
            range = 0.005f..0.15f,
            valueText = (brushRadius * 1000).roundToInt().toString(),
            enabled = enabled,
            onChange = { onBrushRadius((it * 1000).roundToInt() / 1000f) },
            onDone = {},
            untouched = DEFAULT_BRUSH_RADIUS,
        )
    }
    if (current.mask.shape != MaskShape.LINEAR) {
        SettingSlider(
            label = stringResource(R.string.feather),
            value = current.mask.feather,
            range = 0f..1f,
            valueText = (current.mask.feather * 100).roundToInt().toString(),
            enabled = enabled,
            onChange = { onChange(replaced(current.copy(mask = current.mask.copy(feather = (it * 100).roundToInt() / 100f)))) },
            onDone = onDone,
            untouched = DEFAULT_FEATHER,
        )
    }
    // The tones within the mask that the edit applies to: dragging these in keeps it off the darkest or brightest parts.
    SettingSlider(
        label = stringResource(R.string.leave_out_darks),
        value = current.darkest,
        range = 0f..1f,
        valueText = (current.darkest * 100).roundToInt().toString(),
        enabled = enabled,
        onChange = { onChange(replaced(current.copy(darkest = ((it * 100).roundToInt() / 100f).coerceAtMost(current.brightest - 0.05f).coerceAtLeast(0f)))) },
        onDone = onDone,
    )
    SettingSlider(
        label = stringResource(R.string.leave_out_lights),
        value = 1f - current.brightest,
        range = 0f..1f,
        valueText = ((1f - current.brightest) * 100).roundToInt().toString(),
        enabled = enabled,
        onChange = { onChange(replaced(current.copy(brightest = (1f - (it * 100).roundToInt() / 100f).coerceAtLeast(current.darkest + 0.05f).coerceAtMost(1f)))) },
        onDone = onDone,
    )
    // The colour within the mask that the edit applies to: a sky's blue, the green of leaves.
    Text(
        stringResource(R.string.colour_only),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = current.hue == null,
            onClick = {
                onChange(replaced(current.copy(hue = null)))
                onDone()
            },
            enabled = enabled && current.mask.shape != MaskShape.EVERYWHERE,
            label = { Text(stringResource(R.string.colour_any)) },
        )
        MixerBand.entries.forEach { band ->
            val name = band.label
            val chosen = current.hue?.let { kotlin.math.abs(it - band.hue) < 1f } == true
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.hsv(band.hue, 0.75f, 0.9f))
                    .border(
                        width = if (chosen) 3.dp else 0.dp,
                        color = if (chosen) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable(enabled = enabled, role = Role.RadioButton) {
                        onChange(replaced(current.copy(hue = band.hue)))
                        onDone()
                    }
                    .semantics { contentDescription = name },
            )
        }
    }
    if (current.hue != null) {
        SettingSlider(
            label = stringResource(R.string.colour_spread),
            value = current.hueSpread,
            range = 5f..90f,
            valueText = "${current.hueSpread.roundToInt()}°",
            enabled = enabled,
            onChange = { onChange(replaced(current.copy(hueSpread = it.roundToInt().toFloat()))) },
            onDone = onDone,
            untouched = LocalEdit.DEFAULT_HUE_SPREAD,
        )
    }
    LOCAL_CONTROLS.forEach { control ->
        val value = control.setting.get(current.adjustments)
        SettingSlider(
            label = stringResource(control.label),
            value = value,
            range = control.range,
            valueText = control.describe(value),
            enabled = enabled,
            onChange = { position -> onChange(replaced(current.copy(adjustments = control.setting.set(current.adjustments, control.valueAt(position))))) },
            onDone = onDone,
        )
    }
}

private const val MOST_MASKS = 6
private const val DEFAULT_FEATHER = 0.5f
