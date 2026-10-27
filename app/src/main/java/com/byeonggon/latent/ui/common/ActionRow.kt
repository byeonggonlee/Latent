package com.byeonggon.latent.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.R

/** One thing a bar of actions offers. */
internal class BarAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * [actions] as text buttons in one row, in their order, as many as fit; the rest go into a More menu at the end,
 * so that none is out of sight off the edge of a narrow screen or behind large text. Only the buttons shown are
 * composed, so that a screen reader finds each action once, where it can be reached. A [lead] (the count of what
 * is selected, say) comes first and always shows, shortened if need be so that More keeps its room.
 */
@Composable
internal fun ActionRow(actions: List<BarAction>, modifier: Modifier = Modifier, lead: BarAction? = null) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier) {
        val (leadWidth, shown) = with(density) {
            val padding = ButtonDefaults.TextButtonContentPadding.let {
                it.calculateLeftPadding(direction) + it.calculateRightPadding(direction)
            }.roundToPx()
            val least = ButtonDefaults.MinWidth.roundToPx()
            fun widthOf(label: String) = maxOf(least, measurer.measure(label, style, maxLines = 1).size.width + padding)
            val gap = GAP.roundToPx()
            val more = MORE_WIDTH.roundToPx()
            val leadWidth = lead?.let { minOf(widthOf(it.label), constraints.maxWidth - more - gap).coerceAtLeast(0) }
            val room = constraints.maxWidth - (leadWidth?.plus(gap) ?: 0)
            val widths = actions.map { widthOf(it.label) }
            leadWidth to if (widths.sum() + gap * (widths.size - 1).coerceAtLeast(0) <= room) {
                actions.size
            } else {
                // Room is kept for More, after the last button that fits.
                var used = more
                widths.indexOfFirst { width -> (used + width + gap > room).also { used += width + gap } }
                    .let { if (it < 0) actions.size else it }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GAP), verticalAlignment = Alignment.CenterVertically) {
            if (lead != null && leadWidth != null) {
                TextButton(onClick = lead.onClick, enabled = lead.enabled, modifier = Modifier.widthIn(max = with(density) { leadWidth.toDp() })) {
                    Text(lead.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            actions.take(shown).forEach { action ->
                TextButton(onClick = action.onClick, enabled = action.enabled) { Text(action.label, maxLines = 1) }
            }
            if (shown < actions.size) MoreActions(actions.drop(shown))
        }
    }
}

@Composable
private fun MoreActions(actions: List<BarAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    enabled = action.enabled,
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

private val GAP = 4.dp
private val MORE_WIDTH = 48.dp
