package inga.bpmetrics.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.TimeSpan
import inga.bpmetrics.ui.components.PersonSwatch
import inga.bpmetrics.ui.components.PersonAvatar
import inga.bpmetrics.ui.components.overCover
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
 * Who was there, as faces.
 *
 * Dots said *how many* and nothing else: six identical circles in six colours, and knowing which
 * colour is whose is something you either remember or you do not. A face answers "who" without
 * being read, which is the entire job of a card in a list.
 *
 * Overlapped slightly, as a group of people is drawn everywhere else. Someone with no photograph
 * still appears — their colour and initial — so a group is never partly missing.
 */
@Composable
fun PersonFaces(people: List<PersonEntity>, max: Int = 5, size: Dp = 26.dp) {
    Row(
        horizontalArrangement = Arrangement.spacedBy((-size / 4)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        people.take(max).forEach { person ->
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    // A ring in the card's own colour, so overlapping faces stay separate rather
                    // than merging into one shape.
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp)
            ) {
                PersonAvatar(person = person, size = size - 3.dp)
            }
        }
        if (people.size > max) {
            Spacer(Modifier.width(6.dp))
            Text(
                "+${people.size - max}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * How much there is: events and recordings, as plain text beside the faces.
 *
 * Small and quiet on purpose. This is the least interesting thing on the card — it says how big the
 * night was, not what happened during it — so it shares a line with the faces and stays out of the
 * way of the readings below.
 */
@Composable
private fun CardCounts(recordCount: Int, eventCount: Int?, hasCover: Boolean) {
    Text(
        buildString {
            eventCount?.let {
                append(countLabel(it, "event"))
                append("  ·  ")
            }
            append(countLabel(recordCount, "recording"))
        },
        style = MaterialTheme.typography.labelMedium.overCover(hasCover),
        color = if (hasCover) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    )
}

/**
 * The readings, on a line of their own.
 *
 * They shared a row with the faces and the counts, and at four items across that row the numbers
 * ran into their own labels and the whole line wrapped into a mess. They are also the most
 * interesting thing on the card — the one figure a heart rate app can show that no other app could
 * — so crowding them at the end of a row of counts had them last in line for space *and* last in
 * line for attention.
 *
 * A line to themselves costs nothing: there was room under the faces the whole time.
 */
@Composable
private fun CardVitals(peakBpm: Int?, avgBpm: Int?, hasCover: Boolean) {
    if (peakBpm == null && avgBpm == null) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        peakBpm?.let { StatPair("Peak", it, inga.bpmetrics.ui.theme.BpmHigh, hasCover) }
        avgBpm?.let { StatPair("Avg", it, inga.bpmetrics.ui.theme.BpmAvg, hasCover) }
    }
}

/**
 * A reading and what it is a reading of.
 *
 * Label first and small, then the number — so a row of these lines up on its labels and the figures
 * carry. Tabular figures, as everywhere else numbers appear, so 98 and 188 occupy the same width
 * and a column of cards does not jitter as it scrolls.
 */
@Composable
private fun StatPair(label: String, value: Int, tone: Color, hasCover: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.overCover(hasCover),
            color = if (hasCover) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleSmall.copy(
                fontFeatureSettings = inga.bpmetrics.ui.theme.MetricNumerals
            ).overCover(hasCover),
            fontWeight = FontWeight.Bold,
            color = tone
        )
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
    /** Its own picture or the one it inherits, resolved by the caller. */
    cover: inga.bpmetrics.library.Cover? = null,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            // Behind the header only, not the expanded list underneath. A picture behind a list of
            // nested rows competes with every one of them; behind the name it identifies the card.
            inga.bpmetrics.ui.components.CoverBackground(
                cover = cover,
                modifier = Modifier.fillMaxWidth(),
                scrim = inga.bpmetrics.ui.components.CoverScrim.TILE
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    // What. The name first and alone, so the eye lands on the thing that
                    // distinguishes this card from the one above it.
                    Text(
                        summary.event.displayName,
                        style = MaterialTheme.typography.titleMedium.overCover(cover != null),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    // When, and where it sits — one line rather than a date line and a separate
                    // collection line saying half a fact each.
                    Text(
                        buildString {
                            // Type first when there is one: "Concert · 14 Aug, 21:00–22:30" reads as
                            // what the thing was, then when. The span alone makes every card look
                            // the same at a glance.
                            summary.event.type?.takeIf { it.isNotBlank() }?.let { append("$it  ·  ") }
                            append(formatSpan(summary.span))
                            groupName?.let { append("  ·  $it") }
                        },
                        style = MaterialTheme.typography.bodySmall.overCover(cover != null),
                        color = if (cover != null) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(8.dp))

                    // Who, and how hard. Faces rather than dots, and the peak rather than only a
                    // count — a number nobody can get from a filename.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (summary.people.isNotEmpty()) {
                            PersonFaces(summary.people)
                            Spacer(Modifier.width(12.dp))
                        }
                        CardCounts(summary.recordCount, eventCount = null, hasCover = cover != null)
                    }

                    if (summary.peakBpm != null || summary.avgBpm != null) {
                        Spacer(Modifier.height(6.dp))
                        CardVitals(summary.peakBpm, summary.avgBpm, hasCover = cover != null)
                    }
                }

                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Show recordings"
                    )
                }
                EventOverflow(
                    renameLabel = "Edit",
                    onRename = onRename,
                    onMoveToGroup = onMoveToGroup,
                    onDelete = onDelete,
                    deleteLabel = "Delete event"
                )
            }
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
    /** Sets this collection's cover, which everything nested under it inherits. */
    onSetCover: (() -> Unit)? = null,
    onFrameCover: (() -> Unit)? = null,
    onRemoveCover: (() -> Unit)? = null,
    /** Its own picture or the one it inherits from a parent, resolved by the caller. */
    cover: inga.bpmetrics.library.Cover? = null,
    /**
     * How deep this sits, so the tree reads as a tree rather than a flat list.
     *
     * **Zero at the top level**, matching [inga.bpmetrics.library.EventTree.flatten]. The walk this
     * replaced counted from one, so subtracting one here was right then and made every top-level
     * collection ask for negative padding after the fold — which Compose throws on, taking the
     * whole screen down rather than merely looking wrong.
     */
    depth: Int = 0,
    content: @Composable () -> Unit
) {
    Card(
        // Indented by depth, which is the whole point of nesting being visible: a day inside a
        // festival should look like it is inside it.
        //
        // Floored at zero rather than trusted. An off-by-one in a caller should misdraw a card, not
        // crash the list — and this is a crash that reached a device once already.
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth.coerceAtLeast(0) * 14).dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column {
            inga.bpmetrics.ui.components.CoverBackground(
                cover = cover,
                modifier = Modifier.fillMaxWidth(),
                scrim = inga.bpmetrics.ui.components.CoverScrim.TILE
            ) {
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
                        style = MaterialTheme.typography.titleMedium.overCover(cover != null),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        formatSpan(summary.span),
                        style = MaterialTheme.typography.bodySmall.overCover(cover != null),
                        color = if (cover != null) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Nested collections named on their own line when there are any, because that
                    // is what explains the shape of everything after it: a festival of two days
                    // reads differently from six loose events.
                    if (summary.nestedCollectionCount > 0) {
                        Text(
                            countLabel(summary.nestedCollectionCount, "collection") + " inside",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (summary.people.isNotEmpty()) {
                            PersonFaces(summary.people)
                            Spacer(Modifier.width(12.dp))
                        }
                        CardCounts(summary.recordCount, summary.eventCount, hasCover = cover != null)
                    }

                    if (summary.peakBpm != null || summary.avgBpm != null) {
                        Spacer(Modifier.height(6.dp))
                        CardVitals(summary.peakBpm, summary.avgBpm, hasCover = cover != null)
                    }
                }

                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        // "Contents", not "events": this now reveals the collections nested inside
                        // as well, which is the whole point of collapsing one.
                        contentDescription = if (expanded) "Collapse" else "Show contents"
                    )
                }
                EventOverflow(
                    onRename = onRename,
                    onMoveToGroup = onMoveToCollection,
                    onDelete = onDelete,
                    deleteLabel = "Delete collection",
                    onSetCover = onSetCover,
                    onFrameCover = onFrameCover,
                    onRemoveCover = onRemoveCover,
                    // Its own, not an inherited one. "Remove cover" on a collection showing its
                    // parent's picture would appear to do nothing, because the parent's would still
                    // resolve — the menu offers only what this collection can actually change.
                    hasCover = summary.group.coverPath != null
                )
            }
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
    /** "Rename" for a collection, "Edit" for an event — the event dialog does rather more. */
    renameLabel: String = "Rename",
    onRename: () -> Unit,
    onMoveToGroup: (() -> Unit)?,
    onDelete: () -> Unit,
    deleteLabel: String,
    /**
     * Sets the picture for this event or collection, and so for everything under it.
     *
     * Optional rather than always present: this menu is shared, and a card with nowhere to put a
     * cover should not offer to set one.
     */
    onSetCover: (() -> Unit)? = null,
    onFrameCover: (() -> Unit)? = null,
    onRemoveCover: (() -> Unit)? = null,
    hasCover: Boolean = false
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(renameLabel) },
                onClick = { open = false; onRename() }
            )
            onMoveToGroup?.let { move ->
                DropdownMenuItem(
                    text = { Text("Move…") },
                    onClick = { open = false; move() }
                )
            }
            onSetCover?.let { set ->
                DropdownMenuItem(
                    text = { Text(if (hasCover) "Change cover…" else "Set cover…") },
                    onClick = { open = false; set() }
                )
            }
            if (hasCover) {
                onFrameCover?.let { frame ->
                    DropdownMenuItem(
                        text = { Text("Reframe cover") },
                        onClick = { open = false; frame() }
                    )
                }
                onRemoveCover?.let { remove ->
                    DropdownMenuItem(
                        text = { Text("Remove cover") },
                        onClick = { open = false; remove() }
                    )
                }
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
                                fontWeight = if (currentGroupId == group.group.eventId) {
                                    FontWeight.Bold
                                } else null
                            )
                        },
                        onClick = { onPick(group.group.eventId) }
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
    /**
     * The whole tree in reading order, each with its depth.
     *
     * Containers included. This offered only leaf events, so a recording belonging to the festival
     * rather than to any one set — the walk between stages, the queue — had nowhere to go but
     * unfiled. Anything that can hold recordings is offered, at the depth it sits.
     */
    rows: List<Pair<inga.bpmetrics.library.EventEntity, Int>>,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
    onCreateEvent: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $recordCount recording${if (recordCount == 1) "" else "s"} to…") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                DropdownMenuItem(
                    text = { Text("New event…") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = onCreateEvent
                )
                if (rows.isNotEmpty()) HorizontalDivider()
                rows.forEach { (event, depth) ->
                    DropdownMenuItem(
                        modifier = Modifier.padding(start = (depth.coerceAtLeast(0) * 14).dp),
                        text = {
                            Column {
                                Text(event.displayName)
                                val detail = listOfNotNull(
                                    event.type?.takeIf { it.isNotBlank() },
                                    event.windowStart?.let { start ->
                                        event.windowEnd?.let { formatSpan(TimeSpan(start, it)) }
                                    }
                                ).joinToString("  ·  ")
                                if (detail.isNotBlank()) {
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = { onPick(event.eventId) }
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
