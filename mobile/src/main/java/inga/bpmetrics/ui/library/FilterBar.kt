package inga.bpmetrics.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.components.PersonSwatch

/**
 * The whole filter, as one bar.
 *
 * A search field and a row of chips, where a person, a tag, a collection, a venue, a date range and
 * a rate band are all just chips. It replaced a dialog with a section per dimension, which made
 * people think in the app's schema rather than in their own question — "Kyle at Coachella in the
 * rain" was spread across three collapsed sections, and once the dialog closed it was invisible.
 *
 * The active filter now reads as a sentence, and removing a term is one tap on the term rather than
 * reopening a dialog and hunting for which section it came from.
 *
 * @param options What each dimension can be narrowed to. Supplied rather than fetched so this stays
 *   a rendering of state — the same reason [FilterChips] is pure.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterBar(
    query: String,
    chips: List<FilterChip>,
    options: FilterOptions,
    onQueryChange: (String) -> Unit,
    onRemoveChip: (FilterChip) -> Unit,
    onAdd: (FilterDimension, String) -> Unit,
    onClearAll: () -> Unit,
    /** Filters someone kept. Pinned here because a view you have to find is barely a view. */
    savedViews: List<inga.bpmetrics.library.SavedViewEntity> = emptyList(),
    activeViewId: Long? = null,
    onApplyView: (inga.bpmetrics.library.SavedViewEntity) -> Unit = {},
    onSaveView: (String) -> Unit = {},
    onDeleteView: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var naming by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<inga.bpmetrics.library.SavedViewEntity?>(null) }
    var adding by remember { mutableStateOf<FilterDimension?>(null) }
    var choosingDimension by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search recordings, events, places…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    androidx.compose.material3.IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            chips.forEach { chip ->
                InputChip(
                    selected = true,
                    onClick = { onRemoveChip(chip) },
                    label = { Text(chip.label) },
                    leadingIcon = chip.colorArgb?.let { { PersonSwatch(it, size = 14) } },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove ${chip.label}",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            AssistChip(
                onClick = { choosingDimension = true },
                label = { Text(if (chips.isEmpty()) "Add filter" else "Add") },
                leadingIcon = {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )

            // Only once there is something to clear. A permanent "clear" beside an empty filter is
            // an action that does nothing, sitting where a useful one could be.
            if (chips.isNotEmpty() || query.isNotBlank()) {
                AssistChip(onClick = onClearAll, label = { Text("Clear") })
                // Offered only when there is a question worth keeping, and not when it is already
                // one of the saved ones — saving a view twice under two names is a mess nobody
                // asked for.
                if (activeViewId == null) {
                    AssistChip(onClick = { naming = true }, label = { Text("Save view") })
                }
            }
        }

        if (savedViews.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                savedViews.forEach { view ->
                    androidx.compose.material3.FilterChip(
                        selected = view.viewId == activeViewId,
                        onClick = { onApplyView(view) },
                        label = { Text(view.displayName) },
                        modifier = Modifier.padding(end = 0.dp)
                    )
                }
                AssistChip(
                    onClick = { managing = savedViews.firstOrNull() },
                    label = { Text("Manage") }
                )
            }
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save this view") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        placeholder = { Text("Kyle at festivals, anything over 180…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Saves the question, not the answer — recordings added later show up in it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { onSaveView(name.trim()); naming = false }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } }
        )
    }

    if (managing != null) {
        AlertDialog(
            onDismissRequest = { managing = null },
            title = { Text("Saved views") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    savedViews.forEach { view ->
                        DropdownMenuItem(
                            text = { Text(view.displayName) },
                            trailingIcon = {
                                androidx.compose.material3.IconButton(onClick = {
                                    onDeleteView(view.viewId)
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Delete")
                                }
                            },
                            onClick = { onApplyView(view); managing = null }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { managing = null }) { Text("Done") }
            }
        )
    }

    if (choosingDimension) {
        AlertDialog(
            onDismissRequest = { choosingDimension = false },
            title = { Text("Narrow by") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    FilterDimension.entries.forEach { dimension ->
                        val available = options.countFor(dimension)
                        DropdownMenuItem(
                            enabled = available > 0,
                            text = {
                                Column {
                                    Text(dimension.label)
                                    // Says why a row is unavailable rather than leaving it greyed
                                    // with no explanation — "no venues yet" is actionable.
                                    if (available == 0) {
                                        Text(
                                            "Nothing to choose from yet",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {
                                choosingDimension = false
                                adding = dimension
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosingDimension = false }) { Text("Cancel") }
            }
        )
    }

    adding?.let { dimension ->
        ValuePickerDialog(
            dimension = dimension,
            options = options,
            onDismiss = { adding = null },
            onPick = { id ->
                onAdd(dimension, id)
                adding = null
            }
        )
    }
}

/**
 * What each dimension can be narrowed to, already resolved to names.
 *
 * One shape rather than seven parameters, so adding a dimension is one entry here and one branch in
 * [FilterChips] rather than a change threaded through every call site.
 */
data class FilterOptions(
    val people: List<Pair<String, String>> = emptyList(),
    val tags: List<Pair<String, String>> = emptyList(),
    val events: List<Pair<String, String>> = emptyList(),
    val collections: List<Pair<String, String>> = emptyList(),
    val locations: List<Pair<String, String>> = emptyList(),
    val watches: List<Pair<String, String>> = emptyList()
) {
    fun forDimension(dimension: FilterDimension): List<Pair<String, String>> = when (dimension) {
        FilterDimension.PERSON -> people
        FilterDimension.TAG -> tags
        FilterDimension.EVENT -> events
        FilterDimension.COLLECTION -> collections
        FilterDimension.LOCATION -> locations
        FilterDimension.WATCH -> watches
        // Neither is chosen from a list — they are ranges, and get their own editors.
        FilterDimension.DATE, FilterDimension.RATE -> emptyList()
    }

    fun countFor(dimension: FilterDimension): Int = when (dimension) {
        FilterDimension.DATE, FilterDimension.RATE -> 1
        else -> forDimension(dimension).size
    }
}

@Composable
private fun ValuePickerDialog(
    dimension: FilterDimension,
    options: FilterOptions,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val values = options.forDimension(dimension)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dimension.label) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (values.isEmpty()) {
                    Text(
                        "Nothing to choose from yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                values.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onPick(id) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
