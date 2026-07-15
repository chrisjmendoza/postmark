package com.plusorminustwo.postmark.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Bottom sheet wrapping Material3's [DateRangePicker]; reports the picked range as
 * [LocalDate]s (the picker's epoch millis are UTC-midnight day markers, hence the
 * UTC conversion — they are calendar days, not instants). Shared by ThreadScreen's
 * date-jump and the backup Export screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeBottomSheet(
    onSelect: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState = rememberDateRangePickerState()
    val sheetState  = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        DateRangePicker(
            state    = pickerState,
            headline = null,
            showModeToggle = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                onClick = {
                    val startMs = pickerState.selectedStartDateMillis ?: return@Button
                    val endMs   = pickerState.selectedEndDateMillis   ?: return@Button
                    val start = Instant.ofEpochMilli(startMs).atZone(ZoneOffset.UTC).toLocalDate()
                    val end   = Instant.ofEpochMilli(endMs).atZone(ZoneOffset.UTC).toLocalDate()
                    onSelect(start, end)
                },
                enabled = pickerState.selectedStartDateMillis != null &&
                          pickerState.selectedEndDateMillis   != null
            ) { Text("Select") }
        }
    }
}
