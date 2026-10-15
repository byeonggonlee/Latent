package com.byeonggon.latent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.R
import com.byeonggon.latent.data.TimeZoneChoice
import com.byeonggon.latent.data.TimeZoneChoices

/** Picking the time zone the camera's clock is set to, from every zone there is, narrowed by typing. [chosen] null is the phone's own. */
@Composable
internal fun CameraZoneDialog(chosen: String?, onChoose: (String?) -> Unit, onDismiss: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val all = remember(locale) { TimeZoneChoices.all(locale) }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, all) { TimeZoneChoices.search(all, query, locale) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.camera_zone)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.camera_zone_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    if (query.isBlank()) {
                        item(key = "phone") { ZoneRow(stringResource(R.string.camera_zone_phone), null, chosen == null) { onChoose(null) } }
                    }
                    items(shown, key = TimeZoneChoice::id) { zone ->
                        ZoneRow(zone.city, zone.offsetLabel + " · " + zone.id.replace('_', ' '), chosen == zone.id) { onChoose(zone.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun ZoneRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
