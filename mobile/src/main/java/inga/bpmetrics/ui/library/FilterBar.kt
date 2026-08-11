package inga.bpmetrics.ui.library

import inga.bpmetrics.library.CollectionEntity
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.FilterState
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Insights
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
    /** The whole filter, because ranges cannot be expressed as chips. See [FilterEditor]. */
    filter: FilterState,
    chips: List<FilterChip>,
    options: FilterOptions,
    onQueryChange: (String) -> Unit,
    onChange: (FilterState) -> Unit,
    onClearAll: () -> Unit,
    /**
     * Analyses whatever the bar currently says.
     *
     * The fourth way to name a scope — §8.5 has a recording, an event, a collection and a
     * question, and this is the question. Without it the only live analysis reachable is
     * "everything", which is the one nobody needs a filter for.
     */
    onAnalyse: () -> Unit = {},
    /**
     * Selections someone pinned — collections and smart collections alike.
     *
     * Pinned here because a set you have to go and find is barely better than rebuilding the
     * filter, which is the whole reason saved views existed before they turned out to be the same
     * object as a collection.
     */
    savedViews: List<inga.bpmetrics.library.CollectionEntity> = emptyList(),
    activeViewId: Long? = null,
    onApplyView: (inga.bpmetrics.library.CollectionEntity) -> Unit = {},
    onSaveView: (String) -> Unit = {},
    onUnpinView: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var naming by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<inga.bpmetrics.library.CollectionEntity?>(null) }

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

        // Through the shared editor, which the collection rule dialog also uses — a filter and a
        // rule are the same object and were being edited by two different pieces of UI.
        FilterEditor(
            filter = filter,
            chips = chips,
            options = options,
            onChange = onChange,
            onClearAll = if (chips.isNotEmpty() || query.isNotBlank()) onClearAll else null,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            if (chips.isNotEmpty() || query.isNotBlank()) {
                // Offered only when there is a question worth keeping, and not when it is already
                // one of the saved ones — saving a view twice under two names is a mess nobody
                // asked for.
                if (activeViewId == null) {
                    AssistChip(onClick = { naming = true }, label = { Text("Save view") })
                }
                AssistChip(
                    onClick = onAnalyse,
                    label = { Text("Analyse") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Insights,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }

        if (savedViews.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                savedViews.forEach { view ->
                    androidx.compose.material3.FilterChip(
                        selected = view.collectionId == activeViewId,
                        onClick = { onApplyView(view) },
                        label = { Text(view.displayName) },
                        // A rule and a hand-made list read differently and behave differently —
                        // one keeps answering, the other stays as you left it — so the chip says
                        // which it is rather than leaving it to be discovered.
                        leadingIcon = if (!view.isSmart) null else {
                            {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "Smart collection",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
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
            title = { Text("Pinned") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    savedViews.forEach { view ->
                        DropdownMenuItem(
                            text = { Text(view.displayName) },
                            trailingIcon = {
                                // Unpins. Deleting a set someone assembled by hand from a row of
                                // chips is far too easy to do by accident; the collections screen
                                // is where deleting belongs.
                                androidx.compose.material3.IconButton(onClick = {
                                    onUnpinView(view.collectionId)
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Unpin")
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

}

/**
 * What each dimension can be narrowed to, already resolved to names.
 *
 * One shape rather than seven parameters, so adding a dimension is one entry here and one branch in
 * [FilterChips] rather than a change threaded through every call site.
 */
/** One tag category and everything in it. */
data class TagCategoryOption(
    val categoryId: Long,
    val name: String,
    /** Tag id to name, without the category prefix — the heading above already says it. */
    val tags: List<Pair<Long, String>>
)

data class FilterOptions(
    val people: List<Pair<String, String>> = emptyList(),
    val tags: List<Pair<String, String>> = emptyList(),
    val events: List<Pair<String, String>> = emptyList(),
    val collections: List<Pair<String, String>> = emptyList(),
    val locations: List<Pair<String, String>> = emptyList(),
    val watches: List<Pair<String, String>> = emptyList(),
    /** The event types in use. Value and label are the same string — a type *is* its name. */
    val eventTypes: List<Pair<String, String>> = emptyList(),
    /**
     * Tags with their categories intact, for the tag picker.
     *
     * [tags] is the same information flattened to "Character › Hulk" strings, which is right for a
     * chip and wrong for choosing: a category is the level people actually think at — "any
     * character" — and a flat list makes that eight taps down a list of forty.
     */
    val tagCategories: List<TagCategoryOption> = emptyList(),
    /**
     * The things themselves, for the pickers that should show them as they look everywhere else.
     *
     * A person is a face and a colour, an event is a cover and a place in a tree, a collection is a
     * cover and a name. Offering all three as a line of grey text made the filter the one screen in
     * the app where they are anonymous — and picking the right "Day 1" out of four is a great deal
     * easier with the picture that is on it.
     */
    val peopleEntities: List<PersonEntity> = emptyList(),
    /** The tree itself. The picker nests and collapses it; see `FilterEditor`. */
    val eventTree: List<EventEntity> = emptyList(),
    val collectionEntities: List<CollectionEntity> = emptyList()
) {
    fun forDimension(dimension: FilterDimension): List<Pair<String, String>> = when (dimension) {
        FilterDimension.PERSON -> people
        FilterDimension.TAG -> tags
        FilterDimension.EVENT -> events
        FilterDimension.COLLECTION -> collections
        FilterDimension.LOCATION -> locations
        FilterDimension.EVENT_TYPE -> eventTypes
        FilterDimension.WATCH -> watches
        // Neither is chosen from a list — they are ranges, and get their own editors.
        FilterDimension.DATE, FilterDimension.RATE -> emptyList()
    }

    fun countFor(dimension: FilterDimension): Int = when (dimension) {
        FilterDimension.DATE, FilterDimension.RATE -> 1
        else -> forDimension(dimension).size
    }
}

