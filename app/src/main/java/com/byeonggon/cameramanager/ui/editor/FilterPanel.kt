package com.byeonggon.cameramanager.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.byeonggon.cameramanager.enhance.Filter
import kotlin.math.roundToInt

/**
 * The filters, each shown on the picture itself. Tapping one gives the photo its look, which can then
 * be dosed; holding one of the user's own offers to delete it. The look the photo has can be saved
 * as a new filter.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FilterPanel(
    state: EditorUiState,
    enabled: Boolean,
    onApply: (Filter?) -> Unit,
    onAmount: (Float) -> Unit,
    onDone: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (Filter) -> Unit,
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Filter?>(null) }

    LazyRow(
        modifier = Modifier.semantics { contentDescription = "필터 목록" },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "none") {
            FilterTile(
                name = "없음",
                selected = state.filter == null && !state.edit.changesColors,
                modifier = Modifier.combinedClickable(enabled = enabled, role = Role.Button, onClick = { onApply(null) }),
            ) {
                state.filterPreviews[EditorViewModel.NO_FILTER]
            }
        }
        items(state.filters, key = { it.id }) { filter ->
            FilterTile(
                name = filter.name,
                selected = state.filter?.id == filter.id,
                modifier = Modifier.combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onLongClick = if (filter.custom) ({ deleting = filter }) else null,
                    onClick = { onApply(filter) },
                ),
            ) {
                state.filterPreviews[filter.id]
            }
        }
    }
    if (state.filter != null) {
        SettingSlider(
            label = "강도",
            value = state.filterAmount,
            range = 0f..MOST,
            valueText = (state.filterAmount * 100).roundToInt().toString(),
            enabled = enabled,
            onChange = { onAmount((it * 20).roundToInt() / 20f) },
            onDone = onDone,
            untouched = 1f,
        )
    }
    TextButton(
        onClick = { naming = true },
        enabled = enabled && state.edit.changesColors,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text("지금 보정을 내 필터로 저장") }

    if (naming) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("내 필터로 저장") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MOST_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text("필터 이름") },
                    modifier = Modifier.semantics { contentDescription = "필터 이름" },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        naming = false
                        onSave(name)
                    },
                ) { Text("저장하기") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("취소") } },
        )
    }
    deleting?.let { filter ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("'${filter.name}' 필터를 지울까요?") },
            text = { Text("이 필터로 이미 보정한 사진은 그대로 남습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onDelete(filter)
                    },
                ) { Text("지우기") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

/** One filter: the picture with its look, and its name underneath. */
@Composable
private fun FilterTile(name: String, selected: Boolean, modifier: Modifier, picture: () -> android.graphics.Bitmap?) {
    Column(modifier = modifier.width(TILE), horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .size(TILE)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, shape),
        ) {
            picture()?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(TILE))
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private val TILE = 76.dp
private const val MOST = 1.5f
private const val MOST_NAME_LENGTH = 20
