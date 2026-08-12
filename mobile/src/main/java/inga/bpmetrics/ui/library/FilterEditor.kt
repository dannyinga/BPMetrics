package inga.bpmetrics.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.FilterState
import inga.bpmetrics.ui.components.PersonSwatch

/**
 * A filter, shown as its terms and edited by adding and removing them.
 *
 * Lifted out of [FilterBar] so a **rule** can be edited the same way the library's own filter is.
 * They are the same object — a [FilterState] — and a collection's rule was previously unreachable
 * except by narrowing the library and pressing Save, which is not editing a rule so much as
 * replacing it from somewhere else and hoping.
 *
 * Stateless about the filter itself: the caller owns it. That is what lets the same component drive
 * the library, where a change takes effect immediately, and a dialog, where it must not take effect
 * until Save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterEditor(
    /**
     * The whole filter, not just its chips.
     *
     * Ranges are the reason. A date or a rate band is not a term with an id that can be added and
     * removed like a person — it is two numbers — so an editor that only spoke in `(dimension, id)`
     * could not express one, which is why those two dimensions did nothing for as long as they
     * existed. Handing the state in and a new state out covers every dimension with one contract.
     */
    filter: FilterState,
    chips: List<FilterChip>,
    options: FilterOptions,
    onChange: (FilterState) -> Unit,
    /** Null where clearing is not on offer — the rule editor has Cancel instead. */
    onClearAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Whatever the caller wants beside the Add chip. */
    trailing: (@Composable () -> Unit)? = null
) {
    var choosingDimension by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<FilterDimension?>(null) }

    // One way back, used by every picker. Returning to the list of dimensions is almost always what
    // someone wants after opening the wrong one, and closing the whole thing to start again is a
    // strange thing to make them do.
    val goBack = { adding = null; choosingDimension = true }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chips.forEach { chip ->
            InputChip(
                selected = true,
                onClick = { onChange(FilterChips.without(filter, chip)) },
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

        // Only once there is something to clear. A permanent "clear" beside an empty filter is an
        // action that does nothing, sitting where a useful one could be.
        if (onClearAll != null && chips.isNotEmpty()) {
            AssistChip(onClick = onClearAll, label = { Text("Clear") })
        }

        trailing?.invoke()
    }

    if (choosingDimension) {
        DimensionPickerDialog(
            options = options,
            onDismiss = { choosingDimension = false },
            onPick = { choosingDimension = false; adding = it }
        )
    }

    openPicker(
        dimension = adding,
        filter = filter,
        options = options,
        onChange = onChange,
        onBack = goBack,
        onDismiss = { adding = null }
    )
}

/**
 * Whichever picker a dimension needs, or nothing when none is open.
 *
 * One dispatch, because there are two ways in: the library's bar, which goes through the grid
 * first, and a collection's rule, where every dimension already has a row and the grid would be a
 * step asking what the screen has just been told. Two copies of this `when` would be two chances
 * for a dimension to behave differently depending on where it was opened from.
 */
@Composable
private fun openPicker(
    dimension: FilterDimension?,
    filter: FilterState,
    options: FilterOptions,
    onChange: (FilterState) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    when (dimension) {
        null -> Unit

        FilterDimension.TAG -> TagPickerDialog(
            categories = options.tagCategories,
            selected = filter.selectedTagIds,
            onToggleTag = { id, on ->
                onChange(
                    filter.copy(
                        selectedTagIds = if (on) filter.selectedTagIds + id
                        else filter.selectedTagIds - id
                    )
                )
            },
            onToggleCategory = { category, on ->
                val ids = category.tags.map { it.first }
                onChange(
                    filter.copy(
                        selectedTagIds = if (on) filter.selectedTagIds + ids
                        else filter.selectedTagIds - ids.toSet()
                    )
                )
            },
            onBack = onBack,
            onDismiss = onDismiss
        )

        FilterDimension.DATE -> DateRangeDialog(
            initial = filter.dateRange,
            onBack = onBack,
            onDismiss = onDismiss,
            onConfirm = { onChange(filter.copy(dateRange = it)); onDismiss() }
        )

        FilterDimension.RATE -> RateRangeDialog(
            initialMin = filter.minBpm,
            initialMax = filter.maxBpm,
            onBack = onBack,
            onDismiss = onDismiss,
            onConfirm = { low, high ->
                onChange(filter.copy(minBpm = low, maxBpm = high))
                onDismiss()
            }
        )

        else -> ValuePickerDialog(
            dimension = dimension,
            options = options,
            onBack = onBack,
            onDismiss = onDismiss,
            // Stays open. Picking three people should not mean opening the same list three times.
            onPick = { id -> onChange(FilterChips.with(filter, dimension, id)) },
            isPicked = { id -> FilterChips.holds(filter, dimension, id) }
        )
    }
}

/**
 * A collection's rule, edited on the collection.
 *
 * The rule *is* what makes a collection living: it is re-asked every time the set is read, so a
 * recording made tomorrow that matches is in it tomorrow, with nobody adding anything. That has
 * been true in the model since §8; what was missing was any way to see or change the question from
 * the thing it belongs to.
 *
 * Held locally and applied on Save, so backing out of a half-built rule leaves the collection as it
 * was — which matters more here than on the library bar, where a filter is a view and changing your
 * mind costs nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionRuleDialog(
    initial: FilterState,
    options: FilterOptions,
    /** The terms of an arbitrary filter, resolved to names. See `LibraryViewModel.chipsOf`. */
    chipsOf: (FilterState) -> List<FilterChip>,
    onDismiss: () -> Unit,
    onConfirm: (FilterState) -> Unit
) {
    var rule by remember { mutableStateOf(initial) }
    var opening by remember { mutableStateOf<FilterDimension?>(null) }
    val chips = chipsOf(rule)

    // Whichever dimension was tapped, opened straight from its own row. The library's bar goes
    // through the grid first because it is one small "Add" chip with nine possible meanings; here
    // every dimension already has a row of its own, so the grid would be a step that asks a
    // question the screen has just been answered.
    openPicker(
        dimension = opening,
        filter = rule,
        options = options,
        onChange = { rule = it },
        onBack = { opening = null },
        onDismiss = { opening = null }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What this collects") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Anything matching all of these is in the collection, and stays in it as the " +
                        "library grows. Recordings you added by hand are kept as well.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                // Every dimension, on one screen. It was the library's own bar: one "Add" chip
                // opening a grid, then a picker, then closing — and then the whole walk again for
                // the second term. A rule is a *form*, filled in once and revisited, not a series
                // of questions asked one at a time.
                FilterDimension.entries.forEach { dimension ->
                    val mine = chips.filter { it.dimension == dimension }
                    val available = options.countFor(dimension)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            iconFor(dimension),
                            contentDescription = null,
                            tint = if (mine.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            dimension.label,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            enabled = available > 0,
                            onClick = { opening = dimension }
                        ) {
                            Text(if (mine.isEmpty()) "Any" else "Change")
                        }
                    }

                    if (mine.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            mine.forEach { chip ->
                                InputChip(
                                    selected = true,
                                    onClick = { rule = FilterChips.without(rule, chip) },
                                    label = { Text(chip.label) },
                                    leadingIcon = chip.colorArgb?.let {
                                        { PersonSwatch(it, size = 14) }
                                    },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove ${chip.label}",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                if (chips.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        // Saving an empty rule would mean "everything", which is a collection of
                        // the whole library and almost certainly not what was meant.
                        "A rule with no terms matches everything. Narrow at least one.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chips.isNotEmpty(),
                onClick = { onConfirm(rule) }
            ) { Text("Save rule") }
        },
        dismissButton = {
            Row {
                if (chips.isNotEmpty()) {
                    TextButton(onClick = { rule = FilterState() }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Case-insensitive contains, with a blank query matching everything. */
private fun matches(label: String, query: String): Boolean =
    query.isBlank() || label.contains(query.trim(), ignoreCase = true)

/**
 * One thing that can be picked, drawn as the thing it is.
 *
 * A tick on the left rather than a chip, because these rows carry a picture and a second line and
 * do not fit a chip — and because a column of rows nests, which is how an event picker shows that
 * Subtronics is inside Day 1.
 */
@Composable
private fun PickerRow(
    picked: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    detail: String?,
    indent: Int = 0,
    /** Open, shut, or null where there is nothing inside to reveal. */
    expanded: Boolean? = null,
    onToggleExpand: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        color = if (picked) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent.coerceAtMost(4) * 16).dp, top = 2.dp, bottom = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading()
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (picked) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            // Separate from picking the row: narrowing to a festival and looking inside it are
            // different intentions, and one tap cannot be both.
            expanded?.let { open ->
                androidx.compose.material3.IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (open) "Collapse" else "Show what is inside",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** The glyph for each way of narrowing, so the picker is scannable rather than a list of words. */
private fun iconFor(dimension: FilterDimension): ImageVector = when (dimension) {
    FilterDimension.PERSON -> Icons.Default.Person
    FilterDimension.TAG -> Icons.Default.Sell
    FilterDimension.EVENT -> Icons.Default.Event
    FilterDimension.EVENT_TYPE -> Icons.Default.Category
    FilterDimension.COLLECTION -> Icons.Default.Bookmarks
    FilterDimension.LOCATION -> Icons.Default.Place
    FilterDimension.WATCH -> Icons.Default.Watch
    FilterDimension.DATE -> Icons.Default.CalendarMonth
    FilterDimension.RATE -> Icons.Default.MonitorHeart
}

/**
 * Which way to narrow, as tiles rather than a list of words.
 *
 * Nine rows of plain text is a menu read top to bottom every time, and what is being chosen between
 * is *kinds of thing* — people, places, occasions — which is precisely what an icon is good at.
 * Each tile also says how many values it can offer, so a dead end is visible before it is tapped
 * rather than showing up as a greyed row with no explanation.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DimensionPickerDialog(
    options: FilterOptions,
    onDismiss: () -> Unit,
    onPick: (FilterDimension) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // No heading. The dialog is nine labelled tiles and a Cancel button; "Narrow by" above them
        // is a line of text saying what is already obvious from what is under it.
        text = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                // Centred. Tiles ragged against the left edge leave a block of empty space on the
                // right of every row, which reads as something missing.
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterDimension.entries.forEach { dimension ->
                    val available = options.countFor(dimension)
                    val usable = available > 0
                    Surface(
                        onClick = { onPick(dimension) },
                        enabled = usable,
                        shape = MaterialTheme.shapes.medium,
                        color = if (usable) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        },
                        modifier = Modifier.width(104.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                iconFor(dimension),
                                contentDescription = null,
                                tint = if (usable) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                dimension.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (usable) 1f else 0.5f
                                ),
                                maxLines = 2,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                when {
                                    !usable -> "None yet"
                                    dimension == FilterDimension.DATE ||
                                        dimension == FilterDimension.RATE -> "Range"
                                    else -> "$available"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (usable) 1f else 0.6f
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The values of one dimension, as chips, and it stays open.
 *
 * Both changes answer the same complaint. It was a scrolling column of plain rows that closed on
 * the first tap, so narrowing to three people meant opening the same dialog three times and reading
 * the same list three times. The values are short — a name, a venue, a kind of event — which is
 * what chips are for, and a chip can show that it has already been picked.
 *
 * The search box appears only once the list is long enough to be worth searching. Below that it is
 * a text field occupying the space the answer is in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ValuePickerDialog(
    dimension: FilterDimension,
    options: FilterOptions,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    isPicked: (String) -> Boolean
) {
    val values = options.forDimension(dimension)
    var query by remember { mutableStateOf("") }
    // Events only. Collapsed to start, because a library of forty opens as a wall otherwise.
    var openEvents by remember { mutableStateOf(emptySet<Long>()) }
    var newestFirst by remember { mutableStateOf(true) }

    val shown = remember(values, query) {
        if (query.isBlank()) values
        else values.filter { it.second.contains(query.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PickerTitle(dimension, onBack) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // Newest first, reversible — the same control the export's picker has, for the same
                // reason: a list of occasions is read in time order or it is not read at all, and
                // which end you start from depends on what you are looking for.
                if (dimension == FilterDimension.EVENT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (newestFirst) "Newest first" else "Oldest first",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.IconButton(
                            onClick = { newestFirst = !newestFirst }
                        ) {
                            Icon(
                                Icons.Default.SwapVert,
                                contentDescription = "Reverse the order",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (values.size > 8 || dimension == FilterDimension.EVENT) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                ) {
                    if (shown.isEmpty()) {
                        Text(
                            if (values.isEmpty()) "Nothing to choose from yet."
                            else "Nothing matches that.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // People, events and collections are things with faces and pictures, and a
                    // chip reduces them to grey text — this was the one screen in the app where
                    // they were anonymous, and picking the right "Day 1" out of four is a great
                    // deal easier with the picture that is on it.
                    when (dimension) {
                        FilterDimension.PERSON -> options.peopleEntities
                            .filter { matches(it.displayName, query) }
                            .forEach { person ->
                                val id = person.personId.toString()
                                PickerRow(
                                    picked = isPicked(id),
                                    onClick = { onPick(id) },
                                    leading = {
                                        inga.bpmetrics.ui.components.PersonAvatar(
                                            person = person,
                                            size = 34.dp
                                        )
                                    },
                                    title = person.displayName,
                                    detail = null
                                )
                            }

                        // Collapsed, and searching opens everything: a query is a way of saying
                        // "wherever it is", and hiding a match inside a shut festival would be
                        // answering it with silence.
                        FilterDimension.EVENT -> {
                            val searching = query.isNotBlank()
                            inga.bpmetrics.library.EventTree
                                .flatten(
                                    options.eventTree,
                                    expanded = if (searching) null else openEvents,
                                    newestFirst = newestFirst,
                                    startBy = options.eventStarts
                                )
                                .filter { matches(it.event.displayName, query) }
                                .forEach { node ->
                                    val event = node.event
                                    val id = event.eventId.toString()
                                    PickerRow(
                                        picked = isPicked(id),
                                        onClick = { onPick(id) },
                                        indent = node.depth,
                                        leading = {
                                            inga.bpmetrics.ui.components.CoverThumbnail(
                                                event.ownCover,
                                                placeholder = Icons.Default.Event
                                            )
                                        },
                                        title = event.displayName,
                                        detail = event.type?.takeIf { t -> t.isNotBlank() },
                                        expanded = if (!node.hasChildren || searching) null
                                            else event.eventId in openEvents,
                                        onToggleExpand = {
                                            openEvents = if (event.eventId in openEvents) {
                                                openEvents - event.eventId
                                            } else {
                                                openEvents + event.eventId
                                            }
                                        }
                                    )
                                }
                        }

                        FilterDimension.COLLECTION -> options.collectionEntities
                            .filter { matches(it.displayName, query) }
                            .forEach { set ->
                                val id = set.collectionId.toString()
                                PickerRow(
                                    picked = isPicked(id),
                                    onClick = { onPick(id) },
                                    leading = {
                                        inga.bpmetrics.ui.components.CoverThumbnail(
                                            set.ownCover,
                                            placeholder = Icons.Default.Bookmarks
                                        )
                                    },
                                    title = set.displayName,
                                    detail = when {
                                        set.isFrozen -> "Frozen"
                                        set.isSmart -> "Living"
                                        else -> null
                                    }
                                )
                            }

                        // A venue, a watch, a kind of event: a short string and nothing to draw.
                        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            shown.forEach { (id, label) ->
                                val already = isPicked(id)
                                androidx.compose.material3.FilterChip(
                                    selected = already,
                                    onClick = { onPick(id) },
                                    label = { Text(label) },
                                    leadingIcon = if (!already) null else {
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/**
 * Tags, nested under their categories, ticked.
 *
 * A flat list of "Character › Hulk" strings is the right shape for a *chip* and the wrong shape for
 * choosing. The level people think at is the category — "any character", "either mode" — and a flat
 * list turns that into eight taps down a list of forty, with no way to see that you got all of
 * them. So the category has its own box: ticking it takes everything under it in one action, and it
 * shows as half-ticked when only some of its tags are in.
 */
@Composable
private fun TagPickerDialog(
    categories: List<TagCategoryOption>,
    selected: Set<Long>,
    onToggleTag: (Long, Boolean) -> Unit,
    onToggleCategory: (TagCategoryOption, Boolean) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PickerTitle(FilterDimension.TAG, onBack) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (categories.isEmpty()) {
                    Text(
                        "No tags yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                categories.forEach { category ->
                    val ids = category.tags.map { it.first }
                    val on = ids.count { it in selected }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TriStateCheckbox(
                            state = when (on) {
                                0 -> ToggleableState.Off
                                ids.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            },
                            // Partly ticked goes to fully ticked, which is what someone reaching
                            // for a half-filled box nearly always means.
                            onClick = { onToggleCategory(category, on < ids.size) }
                        )
                        Text(
                            category.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    category.tags.forEach { (id, name) ->
                        Row(
                            modifier = Modifier.padding(start = 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = id in selected,
                                onCheckedChange = { onToggleTag(id, it) }
                            )
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/**
 * A stretch of days.
 *
 * One of the two dimensions that has been in [FilterDimension] since the filter was built and has
 * never done anything: it was listed, it was tappable, and it opened a value picker over a list
 * that is empty by definition — a range is not chosen from a list. Material's own range picker,
 * because a date is the one field where rolling your own is guaranteed to be worse.
 *
 * The end is pushed to the end of its day. Picking the 5th twice means "the 5th", and a range that
 * ran midnight to midnight would exclude everything recorded on it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    initial: Pair<Long, Long>?,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Pair<Long, Long>?) -> Unit
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.first,
        initialSelectedEndDateMillis = initial?.second
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PickerTitle(FilterDimension.DATE, onBack) },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                DateRangePicker(state = state, showModeToggle = false)
            }
        },
        confirmButton = {
            val start = state.selectedStartDateMillis
            TextButton(
                enabled = start != null,
                onClick = {
                    // A single day is a legal range: the end falls back to the start.
                    val end = state.selectedEndDateMillis ?: start!!
                    onConfirm(start!! to end + DAY_MS - 1)
                }
            ) { Text("Apply") }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onConfirm(null) }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** The widest band worth offering. Above this is not a heart rate, it is a fault. */
private const val MAX_RATE = 220f

/**
 * A band of beats per minute, on the average.
 *
 * The other dimension that never worked. Matched against a recording's **average** — see
 * `LibraryFilter` — which is worth saying on the dialog, because "over 180" applied to peaks and
 * applied to averages are wildly different questions and the answer would otherwise look wrong.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RateRangeDialog(
    initialMin: Double,
    initialMax: Double?,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double?) -> Unit
) {
    var range by remember {
        mutableStateOf(initialMin.toFloat()..(initialMax?.toFloat() ?: MAX_RATE))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PickerTitle(FilterDimension.RATE, onBack) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Recordings whose average sits inside this band.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "${range.start.toInt()} – ${range.endInclusive.toInt()} bpm",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = 0f..MAX_RATE,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // The top of the scale means "no upper bound" rather than "at most 220": a
                    // stored ceiling of 220 would quietly exclude anything above it.
                    onConfirm(
                        range.start.toDouble(),
                        range.endInclusive.takeIf { it < MAX_RATE }?.toDouble()
                    )
                }
            ) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm(0.0, null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/**
 * A picker's heading: what is being chosen, and the way back to choosing something else.
 *
 * The back arrow is the point. Opening the wrong dimension used to mean closing the dialog and
 * starting again, which is a strange thing to ask of somebody who has taken one wrong tap.
 */
@Composable
private fun PickerTitle(dimension: FilterDimension, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(
            onClick = onBack,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to the list of filters",
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            iconFor(dimension),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(dimension.label)
    }
}
