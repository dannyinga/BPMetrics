package inga.bpmetrics.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import java.util.Calendar
import java.util.TimeZone

/**
 * A dialog allowing the user to configure filtering criteria for BPM records.
 *
 * @param currentFilter The current filter state.
 * @param onDismiss Callback to dismiss the dialog.
 * @param onApply Callback when the user applies the new filter.
 * @param repository The repository to fetch categories and tags from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFilterDialog(
    currentFilter: LibraryViewModel.FilterState,
    onDismiss: () -> Unit,
    onApply: (LibraryViewModel.FilterState) -> Unit,
    repository: inga.bpmetrics.library.LibraryRepository
) {
    var dateRange by remember { mutableStateOf(currentFilter.dateRange) }
    var selectedTagIds by remember { mutableStateOf(currentFilter.selectedTagIds) }
    var minBpm by remember { mutableStateOf(currentFilter.minBpm.toString()) }
    var maxBpm by remember { mutableStateOf(currentFilter.maxBpm?.toString() ?: "") }

    val categories by repository.getAllCategories().collectAsState(initial = emptyList())

    // Internal state for pickers
    var showDatePickerForStart by remember { mutableStateOf(false) }
    var showTimePickerForStart by remember { mutableStateOf(false) }
    var showDatePickerForEnd by remember { mutableStateOf(false) }
    var showTimePickerForEnd by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Records") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("Date Range", style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = dateRange == null,
                            onClick = { dateRange = null },
                            label = { Text("All Time") }
                        )
                        
                        // Last 24 Hours preset
                        FilterChip(
                            selected = false,
                            onClick = {
                                val cal = Calendar.getInstance()
                                val end = cal.timeInMillis
                                cal.add(Calendar.HOUR_OF_DAY, -24)
                                dateRange = cal.timeInMillis to end
                            },
                            label = { Text("24h") }
                        )

                        // Last 7 Days preset
                        FilterChip(
                            selected = false,
                            onClick = {
                                val cal = Calendar.getInstance()
                                val end = cal.timeInMillis
                                cal.add(Calendar.DAY_OF_YEAR, -7)
                                dateRange = cal.timeInMillis to end
                            },
                            label = { Text("7d") }
                        )

                        // Last Month preset
                        FilterChip(
                            selected = false,
                            onClick = {
                                val cal = Calendar.getInstance()
                                val end = cal.timeInMillis
                                cal.add(Calendar.MONTH, -1)
                                dateRange = cal.timeInMillis to end
                            },
                            label = { Text("1m") }
                        )

                        FilterChip(
                            selected = dateRange != null,
                            onClick = {
                                if (dateRange == null) {
                                    val now = Calendar.getInstance()
                                    val end = now.timeInMillis
                                    now.add(Calendar.DAY_OF_YEAR, -7)
                                    dateRange = Pair(now.timeInMillis, end)
                                }
                            },
                            label = { Text("Custom Range") }
                        )
                    }

                    if (dateRange != null) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Start Date/Time Row
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("From:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
                                OutlinedButton(
                                    onClick = { showDatePickerForStart = true },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = ButtonDefaults.TextButtonContentPadding
                                ) {
                                    Text(getDateString(dateRange!!.first), style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(
                                    onClick = { showTimePickerForStart = true },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = ButtonDefaults.TextButtonContentPadding
                                ) {
                                    Text(getTimeString(dateRange!!.first), style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            // End Date/Time Row
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("To:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
                                OutlinedButton(
                                    onClick = { showDatePickerForEnd = true },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = ButtonDefaults.TextButtonContentPadding
                                ) {
                                    Text(getDateString(dateRange!!.second), style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(
                                    onClick = { showTimePickerForEnd = true },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = ButtonDefaults.TextButtonContentPadding
                                ) {
                                    Text(getTimeString(dateRange!!.second), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                item {
                    Text("BPM Range (Avg)", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = minBpm,
                            onValueChange = { minBpm = it },
                            label = { Text("Min") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = maxBpm,
                            onValueChange = { maxBpm = it },
                            label = { Text("Max") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }

                item {
                    Text("Tags", style = MaterialTheme.typography.titleMedium)
                }

                items(categories) { category ->
                    Text(category.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    val tags by repository.getTagsByCategory(category.categoryId).collectAsState(initial = emptyList())
                    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.forEach { tag ->
                            val isSelected = selectedTagIds.contains(tag.tagId)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedTagIds = if (isSelected) selectedTagIds - tag.tagId else selectedTagIds + tag.tagId
                                },
                                label = { Text(tag.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    LibraryViewModel.FilterState(
                        dateRange = dateRange,
                        selectedTagIds = selectedTagIds,
                        minBpm = minBpm.toDoubleOrNull() ?: 0.0,
                        maxBpm = maxBpm.toDoubleOrNull()
                    ))
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    // Helper Dialogs for Date/Time Picking
    if (showDatePickerForStart) {
        // Correctly handle UTC mismatch by converting current local selection to UTC start-of-day for the picker
        val initialDateUtc = dateRange?.first?.toUtcStartOfDay()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDateUtc)
        DatePickerDialog(
            onDismissRequest = { showDatePickerForStart = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { newDate ->
                        val cal = Calendar.getInstance().apply { timeInMillis = dateRange?.first ?: System.currentTimeMillis() }
                        // picker returns UTC. Use UTC Calendar to extract components to avoid "yesterday" shift.
                        val newCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = newDate }
                        cal.set(newCal.get(Calendar.YEAR), newCal.get(Calendar.MONTH), newCal.get(Calendar.DAY_OF_MONTH))
                        dateRange = cal.timeInMillis to (dateRange?.second ?: System.currentTimeMillis())
                    }
                    showDatePickerForStart = false
                }) { Text("OK") }
            }
        ) { DatePicker(state = datePickerState) }
    }

    if (showTimePickerForStart) {
        val cal = Calendar.getInstance().apply { timeInMillis = dateRange?.first ?: System.currentTimeMillis() }
        val timePickerState = rememberTimePickerState(initialHour = cal.get(Calendar.HOUR_OF_DAY), initialMinute = cal.get(Calendar.MINUTE))
        AlertDialog(
            onDismissRequest = { showTimePickerForStart = false },
            confirmButton = {
                TextButton(onClick = {
                    cal.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                    cal.set(Calendar.MINUTE, timePickerState.minute)
                    dateRange = cal.timeInMillis to (dateRange?.second ?: System.currentTimeMillis())
                    showTimePickerForStart = false
                }) { Text("OK") }
            },
            title = { Text("Select Start Time") },
            text = { TimePicker(state = timePickerState) }
        )
    }

    if (showDatePickerForEnd) {
        val initialDateUtc = dateRange?.second?.toUtcStartOfDay()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDateUtc)
        DatePickerDialog(
            onDismissRequest = { showDatePickerForEnd = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { newDate ->
                        val cal = Calendar.getInstance().apply { timeInMillis = dateRange?.second ?: System.currentTimeMillis() }
                        val newCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = newDate }
                        cal.set(newCal.get(Calendar.YEAR), newCal.get(Calendar.MONTH), newCal.get(Calendar.DAY_OF_MONTH))
                        dateRange = (dateRange?.first ?: System.currentTimeMillis()) to cal.timeInMillis
                    }
                    showDatePickerForEnd = false
                }) { Text("OK") }
            }
        ) { DatePicker(state = datePickerState) }
    }

    if (showTimePickerForEnd) {
        val cal = Calendar.getInstance().apply { timeInMillis = dateRange?.second ?: System.currentTimeMillis() }
        val timePickerState = rememberTimePickerState(initialHour = cal.get(Calendar.HOUR_OF_DAY), initialMinute = cal.get(Calendar.MINUTE))
        AlertDialog(
            onDismissRequest = { showTimePickerForEnd = false },
            confirmButton = {
                TextButton(onClick = {
                    cal.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                    cal.set(Calendar.MINUTE, timePickerState.minute)
                    dateRange = (dateRange?.first ?: System.currentTimeMillis()) to cal.timeInMillis
                    showTimePickerForEnd = false
                }) { Text("OK") }
            },
            title = { Text("Select End Time") },
            text = { TimePicker(state = timePickerState) }
        )
    }
}

/**
 * Converts a local epoch timestamp to the UTC start-of-day equivalent.
 * This ensures the Material 3 DatePicker highlights the correct calendar day.
 */
private fun Long.toUtcStartOfDay(): Long {
    val localCal = Calendar.getInstance().apply { timeInMillis = this@toUtcStartOfDay }
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(localCal.get(Calendar.YEAR), localCal.get(Calendar.MONTH), localCal.get(Calendar.DAY_OF_MONTH))
    }
    return utcCal.timeInMillis
}

private fun Modifier.width(dp: Int): Modifier = this.then(Modifier.width(dp.dp))
