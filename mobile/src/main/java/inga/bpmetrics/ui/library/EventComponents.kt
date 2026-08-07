package inga.bpmetrics.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.EventSuggestion
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.TimeSpan
import inga.bpmetrics.ui.components.PersonSwatch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A row of colour dots for who took part, with a count when there are more than fit.
 *
 * Dots rather than names: an event with six people would spend the whole row on text, and the
 * colours are already how a recording is identified everywhere else in the app.
 */
@Composable
fun PersonDots(people: List<PersonEntity>, max: Int = 6) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        people.take(max).forEach { PersonSwatch(it.colorArgb, size = 14) }
        if (people.size > max) {
            Text(
                "+${people.size - max}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Renders a span the way someone would say it out loud.
 *
 * "14 Mar, 19:40 – 21:05" when it is one day, and both dates when it straddles midnight. The year
 * is dropped for the current year, which is nearly every row.
 */
fun formatSpan(span: TimeSpan?): String {
    if (span == null) return "No recordings yet"

    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val startCal = Calendar.getInstance().apply { timeInMillis = span.startMs }
    val endCal = Calendar.getInstance().apply { timeInMillis = span.endMs }

    val datePattern = if (startCal.get(Calendar.YEAR) == thisYear) "d MMM" else "d MMM yyyy"
    val date = SimpleDateFormat(datePattern, Locale.getDefault())
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())

    val sameDay = startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR) &&
        startCal.get(Calendar.DAY_OF_YEAR) == endCal.get(Calendar.DAY_OF_YEAR)

    return if (sameDay) {
        "${date.format(Date(span.startMs))}, ${time.format(Date(span.startMs))} – " +
            time.format(Date(span.endMs))
    } else {
        "${date.format(Date(span.startMs))} – ${date.format(Date(span.endMs))}"
    }
}

private fun countLabel(count: Int, noun: String) =
    "$count $noun${if (count == 1) "" else "s"}"

/**
 * An event in the events list.
 *
 * Tapping the card opens the event's page; the chevron expands it in place. Both exist because they
 * answer different questions — "what happened here" wants the analysis, and "is this recording
 * already filed" wants a peek without losing your place in the list.
 */
@Composable
fun EventCard(
    summary: EventSummary,
    groupName: String?,
    expanded: Boolean,
    onOpen: () -> Unit,
    onToggleExpand: () -> Unit,
    onRename: () -> Unit,
    onMoveToGroup: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        summary.event.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        formatSpan(summary.span),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            countLabel(summary.recordCount, "recording"),
                            style = MaterialTheme.typography.labelMedium
                        )
                        groupName?.let {
                            Text(
                                "  ·  $it",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        PersonDots(summary.people)
                    }
                }

                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Show recordings"
                    )
                }
                EventOverflow(
                    onRename = onRename,
                    onMoveToGroup = onMoveToGroup,
                    onDelete = onDelete,
                    deleteLabel = "Delete event"
                )
            }

            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (summary.records.isEmpty()) {
                        Text(
                            "Nothing filed here yet. Press and hold recordings in the Recordings " +
                                "view, then choose Add to event.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * A group in the groups list.
 *
 * Tapping the card opens the group's aggregate analysis; the chevron expands to its events. Same
 * split as [EventCard], for the same reason.
 */
@Composable
fun GroupCard(
    summary: GroupSummary,
    expanded: Boolean,
    onOpen: () -> Unit,
    onToggleExpand: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    /** Files this collection inside another. Null at the depth cap, where it cannot go deeper. */
    onMoveToCollection: (() -> Unit)? = null,
    /** How deep this sits, so the tree reads as a tree rather than a flat list. */
    depth: Int = 1,
    content: @Composable () -> Unit
) {
    Card(
        // Indented by depth, which is the whole point of nesting being visible: a day inside a
        // festival should look like it is inside it.
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ((depth - 1) * 14).dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        summary.group.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        formatSpan(summary.span),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                // Named first when there are any, because it is what explains the
                                // shape of everything after it: "2 collections · 6 events" reads
                                // as a festival of two days, and "6 events" alone does not.
                                if (summary.nestedCollectionCount > 0) {
                                    append(
                                        countLabel(summary.nestedCollectionCount, "collection")
                                    )
                                    append(" · ")
                                }
                                append(countLabel(summary.eventCount, "event"))
                                append(" · ")
                                append(countLabel(summary.recordCount, "recording"))
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.width(10.dp))
                        PersonDots(summary.people)
                    }
                }

                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Show events"
                    )
                }
                EventOverflow(
                    onRename = onRename,
                    onMoveToGroup = onMoveToCollection,
                    onDelete = onDelete,
                    deleteLabel = "Delete collection"
                )
            }

            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (summary.events.isEmpty()) {
                        Text(
                            // A collection holding only other collections is not empty, and
                            // saying so would contradict the count on the row above it. Its
                            // children are cards of their own, listed beneath this one.
                            if (summary.nestedCollectionCount > 0) {
                                "No events directly in this collection — they are in the " +
                                    "${countLabel(summary.nestedCollectionCount, "collection")} " +
                                    "below."
                            } else {
                                "Nothing in this collection yet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun EventOverflow(
    onRename: () -> Unit,
    onMoveToGroup: (() -> Unit)?,
    onDelete: () -> Unit,
    deleteLabel: String
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { open = false; onRename() }
            )
            onMoveToGroup?.let { move ->
                DropdownMenuItem(
                    text = { Text("Move…") },
                    onClick = { open = false; move() }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete() }
            )
        }
    }
}

/**
 * Names a new event or group, or renames an existing one.
 *
 * One dialog for both because they differ only in wording — and because a rename that looks
 * different from a create is a rename people hesitate over.
 */
@Composable
fun NameDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "Save",
    supporting: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                supporting?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) }
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Chooses which group an event belongs to, including none.
 *
 * @param groups Every group, offered by name.
 * @param currentGroupId Marked so the current answer is visible rather than remembered.
 */
@Composable
fun GroupPickerDialog(
    eventName: String,
    groups: List<GroupSummary>,
    currentGroupId: Long?,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
    onCreateGroup: () -> Unit,
    /** What "nowhere" is called here: no collection for an event, top level for a collection. */
    topLevelLabel: String = "No collection"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move $eventName") },
        text = {
            Column {
                DropdownMenuItem(
                    text = {
                        Text(
                            topLevelLabel,
                            fontWeight = if (currentGroupId == null) FontWeight.Bold else null
                        )
                    },
                    onClick = { onPick(null) }
                )
                groups.forEach { group ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                group.group.displayName,
                                fontWeight = if (currentGroupId == group.group.groupId) {
                                    FontWeight.Bold
                                } else null
                            )
                        },
                        onClick = { onPick(group.group.groupId) }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("New collection…") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = onCreateGroup
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Files the selected recordings under an event, existing or new.
 *
 * Mirrors [BulkWearerDialog]: the same gesture — select, then say what they have in common.
 */
@Composable
fun AddToEventDialog(
    recordCount: Int,
    events: List<EventSummary>,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
    onCreateEvent: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $recordCount recording${if (recordCount == 1) "" else "s"} to…") },
        text = {
            Column {
                DropdownMenuItem(
                    text = { Text("New event…") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = onCreateEvent
                )
                if (events.isNotEmpty()) HorizontalDivider()
                events.forEach { summary ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(summary.event.displayName)
                                Text(
                                    formatSpan(summary.span),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = { onPick(summary.event.eventId) }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Remove from event") },
                    onClick = { onPick(null) }
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Offers to turn a cluster of unfiled recordings into an event.
 *
 * Deliberately a card in the list rather than a prompt: it is a shortcut, and a shortcut that
 * interrupts is worse than the work it saves.
 */
@Composable
fun SuggestionCard(
    suggestion: EventSuggestion,
    people: List<PersonEntity>,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onDismissForever: () -> Unit
) {
    var showConfirmDismiss by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "These ${suggestion.size} recordings ran together",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                formatSpan(suggestion.span),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            PersonDots(people)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onAccept) { Text("Create event") }
                // "Not now" hides it until relaunch; "Dismiss" means never again. Both exist
                // because they answer different questions — "not yet" and "no".
                TextButton(onClick = onDismiss) { Text("Not now") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showConfirmDismiss = true }) {
                    Text(
                        "Dismiss",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Confirmed because there is no undo. The recordings stay exactly where they are; only the
    // offer to group them goes away, which the wording has to make clear or "dismiss" reads as
    // something that might delete them.
    if (showConfirmDismiss) {
        AlertDialog(
            onDismissRequest = { showConfirmDismiss = false },
            title = { Text("Stop suggesting these?") },
            text = {
                Text(
                    "These ${suggestion.size} recordings will not be suggested as an event again. " +
                        "They stay in your library and can still be filed by hand at any time."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDismiss = false
                    onDismissForever()
                }) { Text("Stop suggesting") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDismiss = false }) { Text("Cancel") }
            }
        )
    }
}

/** A one-line row for an event nested inside an expanded group. */
@Composable
fun NestedEventRow(summary: EventSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(summary.event.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${formatSpan(summary.span)} · ${countLabel(summary.recordCount, "recording")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PersonDots(summary.people, max = 4)
    }
}
