package com.byeonggon.latent.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Picks a range of days on a calendar, both ends included; a single day tapped is a range of one day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatesDialog(title: String, initial: ClosedRange<LocalDate>?, onConfirm: (ClosedRange<LocalDate>) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.toPickerMillis(),
        initialSelectedEndDateMillis = initial?.endInclusive?.toPickerMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis?.toPickerDate() ?: return@TextButton
                    // Tapping a single day leaves the end of the range open.
                    onConfirm(start..(state.selectedEndDateMillis?.toPickerDate() ?: start))
                },
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DateRangePicker(
            state = state,
            title = { Text(title, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            showModeToggle = false,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "Oct 3 – Oct 9", or one date for a range of one day; with the years when they are not this one. */
fun ClosedRange<LocalDate>.label(today: LocalDate = LocalDate.now()): String {
    val format = localFormat(if (start.year == today.year && endInclusive.year == today.year) "MMMd" else "yMMMd")
    return if (start == endInclusive) start.format(format) else start.format(format) + " – " + endInclusive.format(format)
}

// The date picker works in UTC midnights, whatever the device's time zone.
private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toPickerDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
