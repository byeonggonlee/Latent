package com.byeonggon.latent.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.R
import com.byeonggon.latent.data.db.NameCount
import java.text.NumberFormat

/** Searching the library: by name, camera, lens, ISO, focal length, when, with a place, edited. Every change takes effect at once. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FindSheet(state: LibraryUiState, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val query = state.query
    val options = state.findOptions
    var text by remember { mutableStateOf(query.text) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.find), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        text = ""
                        viewModel.clearQuery()
                    },
                    enabled = query.isActive,
                ) { Text(stringResource(R.string.find_clear)) }
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    viewModel.updateQuery { query -> query.copy(text = it) }
                },
                singleLine = true,
                label = { Text(stringResource(R.string.find_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (options.cameras.isNotEmpty()) {
                Group(R.string.find_camera) {
                    named(options.cameras, query.cameras) { chosen -> viewModel.updateQuery { it.copy(cameras = chosen) } }
                }
            }
            if (options.lenses.isNotEmpty()) {
                Group(R.string.find_lens) {
                    named(options.lenses, query.lenses) { chosen -> viewModel.updateQuery { it.copy(lenses = chosen) } }
                }
            }
            Group(R.string.find_iso) {
                IsoBand.entries.forEach { band ->
                    FilterChip(
                        selected = band in query.iso,
                        onClick = { viewModel.updateQuery { it.copy(iso = it.iso.toggled(band)) } },
                        label = { Text(stringResource(band.label)) },
                    )
                }
            }
            Group(R.string.find_focal) {
                FocalBand.entries.forEach { band ->
                    FilterChip(
                        selected = band in query.focal,
                        onClick = { viewModel.updateQuery { it.copy(focal = it.focal.toggled(band)) } },
                        label = { Text(stringResource(band.label)) },
                    )
                }
            }
            Group(R.string.find_when) {
                Period.entries.forEach { period ->
                    FilterChip(
                        selected = query.year == null && query.period == period,
                        onClick = { viewModel.updateQuery { it.copy(period = period, year = null) } },
                        label = { Text(stringResource(period.label)) },
                    )
                }
                options.years.forEach { year ->
                    FilterChip(
                        selected = query.year == year,
                        onClick = { viewModel.updateQuery { it.copy(year = if (it.year == year) null else year, period = Period.ANY) } },
                        label = { Text(year.toString()) },
                    )
                }
            }
            Group(R.string.find_place) {
                AskChips(query.located, R.string.find_place_yes, R.string.find_place_no) { viewModel.updateQuery { query -> query.copy(located = it) } }
            }
            Group(R.string.find_edited) {
                AskChips(query.edited, R.string.find_edited_yes, R.string.find_edited_no) { viewModel.updateQuery { query -> query.copy(edited = it) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: Int, chips: @Composable () -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}

/** Chips for names found in the library, with how many photos each has; any number of them can be chosen. */
@Composable
private fun named(options: List<NameCount>, chosen: Set<String>, onChange: (Set<String>) -> Unit) {
    val format = NumberFormat.getIntegerInstance()
    options.forEach { option ->
        FilterChip(
            selected = option.name in chosen,
            onClick = { onChange(chosen.toggled(option.name)) },
            label = { Text("${option.name} (${format.format(option.count)})") },
        )
    }
}

@Composable
private fun AskChips(chosen: Ask, yes: Int, no: Int, onChoose: (Ask) -> Unit) {
    FilterChip(selected = chosen == Ask.ANY, onClick = { onChoose(Ask.ANY) }, label = { Text(stringResource(R.string.find_any)) })
    FilterChip(selected = chosen == Ask.YES, onClick = { onChoose(if (chosen == Ask.YES) Ask.ANY else Ask.YES) }, label = { Text(stringResource(yes)) })
    FilterChip(selected = chosen == Ask.NO, onClick = { onChoose(if (chosen == Ask.NO) Ask.ANY else Ask.NO) }, label = { Text(stringResource(no)) })
}

private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value
