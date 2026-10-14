package com.byeonggon.latent.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.enhance.LocalEdit
import com.byeonggon.latent.enhance.Mask
import com.byeonggon.latent.enhance.MaskShape
import kotlin.math.roundToInt

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
}

private fun nameOf(local: LocalEdit, index: Int) = when (local.mask.shape) {
    MaskShape.LINEAR -> "선형 "
    MaskShape.RADIAL -> "원형 "
    MaskShape.BRUSH -> "브러시 "
} + (index + 1)

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
        for ((shape, label) in listOf(MaskShape.BRUSH to "+ 브러시", MaskShape.LINEAR to "+ 선형", MaskShape.RADIAL to "+ 원형")) {
            OutlinedButton(
                onClick = {
                    onChange(locals + LocalEdit(newMask(shape)))
                    onDone()
                    onSelect(locals.size)
                },
                enabled = enabled && locals.size < MOST_MASKS,
            ) { Text(label) }
        }
    }
    if (current == null) {
        Text(
            "사진의 일부분만 보정하려면 마스크를 추가하세요. 브러시는 칠한 곳에, 선형은 하늘처럼 한쪽 전체에, 원형은 인물이나 사물 주변에 씁니다.",
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
        FilterChip(
            selected = current.mask.inverted,
            onClick = {
                onChange(replaced(current.copy(mask = current.mask.copy(inverted = !current.mask.inverted))))
                onDone()
            },
            enabled = enabled,
            label = { Text("반전") },
        )
        TextButton(
            onClick = {
                onChange(locals.filterIndexed { index, _ -> index != selected })
                onDone()
                onSelect((selected - 1).coerceAtLeast(0))
            },
            enabled = enabled,
        ) { Text("마스크 삭제") }
    }
    if (current.mask.shape == MaskShape.BRUSH) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = !erasing, onClick = { onErasing(false) }, enabled = enabled, label = { Text("칠하기") })
            FilterChip(selected = erasing, onClick = { onErasing(true) }, enabled = enabled, label = { Text("지우개") })
        }
        SettingSlider(
            label = "브러시 크기",
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
            label = "경계 흐림",
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
        label = "어두운 쪽 제외",
        value = current.darkest,
        range = 0f..1f,
        valueText = (current.darkest * 100).roundToInt().toString(),
        enabled = enabled,
        onChange = { onChange(replaced(current.copy(darkest = ((it * 100).roundToInt() / 100f).coerceAtMost(current.brightest - 0.05f).coerceAtLeast(0f)))) },
        onDone = onDone,
    )
    SettingSlider(
        label = "밝은 쪽 제외",
        value = 1f - current.brightest,
        range = 0f..1f,
        valueText = ((1f - current.brightest) * 100).roundToInt().toString(),
        enabled = enabled,
        onChange = { onChange(replaced(current.copy(brightest = (1f - (it * 100).roundToInt() / 100f).coerceAtLeast(current.darkest + 0.05f).coerceAtMost(1f)))) },
        onDone = onDone,
    )
    LOCAL_CONTROLS.forEach { control ->
        val value = control.setting.get(current.adjustments)
        SettingSlider(
            label = control.label,
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
