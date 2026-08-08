package inga.bpmetrics.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import inga.bpmetrics.ui.components.FlowRow
import inga.bpmetrics.ui.components.ExpandableSection
import inga.bpmetrics.ui.components.PersonSwatch
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
    repository: inga.bpmetrics.library.LibraryRepository,
    availablePeople: List<inga.bpmetrics.library.PersonEntity> = emptyList(),
    availableWatches: List<inga.bpmetrics.library.WatchEntity> = emptyList(),
    availableEvents: List<inga.bpmetrics.library.EventEntity> = emptyList(),
    availableGroups: List<inga.bpmetrics.library.EventGroupEntity> = emptyList()
) {
    var dateRange by remember { mutableStateOf(currentFilter.dateRange) }
    var selectedTagIds by remember { mutableStateOf(currentFilter.selectedTagIds) }
    var minBpm by remember { mutableStateOf(currentFilter.minBpm.toString()) }
    var maxBpm by remember { mutableStateOf(currentFilter.maxBpm?.toString() ?: "") }
    var selectedPersonIds by remember { mutableStateOf(currentFilter.selectedPersonIds) }
    var selectedWatchIds by remember { mutableStateOf(currentFilter.selectedWatchIds) }
    var selectedEventIds by remember { mutableStateOf(currentFilter.selectedEventIds) }
    var selectedGroupIds by remember { mutableStateOf(currentFilter.selectedGroupIds) }

    // Room hands back a new Flow per call and collection is keyed on the instance, so building
    // this inline restarted the query on every recomposition.
    val categoriesFlow = remember(repository) { repository.getAllCategories() }
    val categories by categoriesFlow.collectAsState(initial = emptyList())

    // Internal state for pickers
    var showDatePickerForStart by remember { mutableStateOf(false) }
    var showTimePickerForStart by remember { mutableStateOf(false) }
    var showDatePickerForEnd by remember { mutableStateOf(false) }
    var showTimePickerForEnd by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter recordings") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    FilterSection("When", dateRange != null) {
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
                }

                item {
                    FilterSection(
                        "Average heart rate",
                        minBpm.toIntOrNull()?.let { it > 0 } == true || maxBpm.isNotBlank()
                    ) {
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
                }

                if (availablePeople.isNotEmpty()) {
                    item {
                        FilterSection("Wearer", selectedPersonIds.isNotEmpty(), selectedPersonIds.size) {
                        Text(
                            "Who was wearing the watch at the time.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availablePeople.forEach { person ->
                                FilterChip(
                                    selected = person.personId in selectedPersonIds,
                                    onClick = {
                                        selectedPersonIds = if (person.personId in selectedPersonIds) {
                                            selectedPersonIds - person.personId
                                        } else {
                                            selectedPersonIds + person.personId
                                        }
                                    },
                                    leadingIcon = { PersonSwatch(person.colorArgb, size = 12) },
                                    label = { Text(person.displayName) }
                                )
                            }
                        }
                    }
                    }
                }

                if (availableWatches.isNotEmpty()) {
                    item {
                        FilterSection("Watch", selectedWatchIds.isNotEmpty(), selectedWatchIds.size) {
                        Text(
                            "The device itself, whoever was wearing it.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableWatches.forEach { watch ->
                                FilterChip(
                                    selected = watch.watchId in selectedWatchIds,
                                    onClick = {
                                        selectedWatchIds = if (watch.watchId in selectedWatchIds) {
                                            selectedWatchIds - watch.watchId
                                        } else {
                                            selectedWatchIds + watch.watchId
                                        }
                                    },
                                    label = { Text(watch.displayName) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Watch,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                    }
                }

                if (availableEvents.isNotEmpty()) {
                    item {
                        FilterSection("Event", selectedEventIds.isNotEmpty(), selectedEventIds.size) {
                        Text(
                            "The occasion a recording is filed under.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableEvents.forEach { event ->
                                FilterChip(
                                    selected = event.eventId in selectedEventIds,
                                    onClick = {
                                        selectedEventIds = if (event.eventId in selectedEventIds) {
                                            selectedEventIds - event.eventId
                                        } else {
                                            selectedEventIds + event.eventId
                                        }
                                    },
                                    label = { Text(event.displayName) }
                                )
                            }
                        }
                    }
                }
                    }

                if (availableGroups.isNotEmpty()) {
                    item {
                        FilterSection("Collection", selectedGroupIds.isNotEmpty(), selectedGroupIds.size) {
                        Text(
                            "Matches everything inside it, however deeply nested.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableGroups.forEach { group ->
                                FilterChip(
                                    selected = group.groupId in selectedGroupIds,
                                    onClick = {
                                        selectedGroupIds = if (group.groupId in selectedGroupIds) {
                                            selectedGroupIds - group.groupId
                                        } else {
                                            selectedGroupIds + group.groupId
                                        }
                                    },
                                    label = { Text(group.displayName) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                    }

                item {
                    Text("Tags", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Includes tags a recording inherits from its event or any collection above it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                items(categories, key = { it.categoryId }) { category ->
                    // Same reason as above, and it matters more here: this runs per category
                    // inside a lazy list, so an inline flow re-queried tags for every category on
                    // every recomposition.
                    val tagsFlow = remember(repository, category.categoryId) {
                        repository.getTagsByCategory(category.categoryId)
                    }
                    val tags by tagsFlow.collectAsState(initial = emptyList())
                    val categoryTagIds = remember(tags) { tags.map { it.tagId }.toSet() }
                    val selectedInCategory = remember(categoryTagIds, selectedTagIds) {
                        categoryTagIds.intersect(selectedTagIds)
                    }

                    val toggleState = when {
                        categoryTagIds.isEmpty() -> ToggleableState.Off
                        selectedInCategory.size == categoryTagIds.size -> ToggleableState.On
                        selectedInCategory.isEmpty() -> ToggleableState.Off
                        else -> ToggleableState.Indeterminate
                    }

                    val hasSelection = selectedInCategory.isNotEmpty()
                    var isExpanded by remember { mutableStateOf(hasSelection) }

                    ExpandableSection(
                        title = category.name,
                        isExpanded = isExpanded,
                        onToggle = { isExpanded = !isExpanded },
                        leadingContent = {
                            TriStateCheckbox(
                                state = toggleState,
                                onClick = {
                                    selectedTagIds = if (toggleState == ToggleableState.On) {
                                        selectedTagIds - categoryTagIds
                                    } else {
                                        selectedTagIds + categoryTagIds
                                    }
                                }
                            )
                        }
                    ) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    LibraryViewModel.FilterState(
                        dateRange = dateRange,
                        selectedTagIds = selectedTagIds,
                        minBpm = minBpm.toDoubleOrNull() ?: 0.0,
                        maxBpm = maxBpm.toDoubleOrNull(),
                        selectedPersonIds = selectedPersonIds,
                        selectedWatchIds = selectedWatchIds,
                        selectedEventIds = selectedEventIds,
                        selectedGroupIds = selectedGroupIds
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

/**
 * Attributes a hand-picked set of recordings to one person.
 *
 * Picking them out of the library is the only way to describe "these ones" — a batch that arrived
 * before its watch had anyone assigned is not a category any filter expresses. Everyone is listed
 * rather than hidden behind a dropdown, because with a handful of friends the whole set fits and
 * choosing becomes one tap.
 *
 * This overwrites whatever the chosen recordings were attributed to, so the count is stated plainly
 * and nothing happens until a name is tapped.
 */
@Composable
fun BulkWearerDialog(
    recordCount: Int,
    people: List<inga.bpmetrics.library.PersonEntity>,
    onDismiss: () -> Unit,
    onAssign: (Long?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set wearer") },
        text = {
            Column {
                Text(
                    text = if (recordCount == 1) {
                        "Attribute this recording to:"
                    } else {
                        "Attribute these $recordCount recordings to:"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "This replaces whoever they are attributed to now. It does not affect any " +
                        "other recording, or who is wearing the watch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                if (people.isEmpty()) {
                    Text(
                        "No people yet. Add someone in the People section first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(people) { person ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAssign(person.personId) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PersonSwatch(person.colorArgb, size = 18)
                                Spacer(Modifier.width(12.dp))
                                Text(person.displayName, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        item {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAssign(null) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Nobody",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        // No confirm button: tapping a name is the action, so a second press would only be a
        // chance to change the answer after having already given it.
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * One collapsible band of the filter dialog.
 *
 * The dialog had six sections all open at once inside a 500dp box, so finding any one of them meant
 * scrolling past the other five — and the tag categories, which were already collapsible, were
 * buried at the bottom under all of it.
 *
 * Open when it is doing something, closed when it is not. That is the same rule the tag categories
 * already used, and it means the dialog opens showing exactly the filters that are active.
 *
 * @param active whether this section is currently narrowing anything.
 * @param count how many things it has selected, shown on the header so it need not be opened to see.
 */
@Composable
private fun FilterSection(
    title: String,
    active: Boolean,
    count: Int = 0,
    content: @Composable () -> Unit
) {
    var expanded by remember(active) { mutableStateOf(active) }

    ExpandableSection(
        title = title,
        isExpanded = expanded,
        onToggle = { expanded = !expanded },
        titleStyle = MaterialTheme.typography.titleSmall,
        leadingContent = if (active) {
            {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        } else null
    ) {
        Column {
            if (count > 0) {
                Text(
                    "$count selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}
