package inga.bpmetrics.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.Cover
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.TimelineEntry
import inga.bpmetrics.library.TimelineRow

/**
 * The library, chronologically, at whatever depth is open.
 *
 * One list rather than three sections. A festival, its days, an artist, the recordings inside — and
 * a loose recording from the same afternoon sitting between them at the level it belongs to, rather
 * than exiled to "unfiled" at the bottom. The app always knew when that recording happened; the old
 * view just sorted by container before it sorted by time.
 *
 * The order is not decided here. [inga.bpmetrics.library.LibraryTimeline] produces the rows and is
 * the only thing that decides them, so "why is this above that" has one answer and one test.
 */
@Composable
fun TimelineList(
    rows: List<TimelineRow>,
    summaries: Map<Long, EventSummary>,
    recordsById: Map<Long, BpmRecord>,
    eventCovers: Map<Long, Cover>,
    peopleById: Map<Long, PersonEntity>,
    expandedIds: Set<Long>,
    onToggleExpand: (Long) -> Unit,
    onCreateEvent: () -> Unit,
    onOpenEvent: (Long) -> Unit,
    onEdit: (EventSummary) -> Unit,
    onMoveToGroup: (EventSummary) -> Unit,
    onDelete: (EventSummary) -> Unit,
    tile: @Composable (BpmRecord) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedButton(onClick = onCreateEvent, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.padding(end = 8.dp))
                Text("New event")
            }
        }

        if (rows.isEmpty()) {
            item {
                inga.bpmetrics.ui.components.BpmEmptySection(
                    "Nothing here yet",
                    "Recordings appear here in the order they happened. Give an event a time " +
                        "window and anything recorded inside it files itself."
                )
            }
        }

        // Keys are prefixed because record ids and event ids come from different tables and freely
        // collide. LazyColumn requires them unique across the whole list, so an event numbered 3
        // sitting above a recording numbered 3 would crash the screen.
        items(
            rows,
            key = { row ->
                when (val entry = row.entry) {
                    is TimelineEntry.Event -> "event-${entry.event.eventId}"
                    is TimelineEntry.Recording -> "record-${entry.record.recordId}"
                }
            }
        ) { row ->
            // Indentation is the only thing saying what is inside what, so it is applied here
            // rather than inside the cards — a recording and an event at the same depth have to
            // line up, and they are two different composables.
            Box(Modifier.padding(start = (row.depth.coerceAtLeast(0) * 14).dp)) {
                when (val entry = row.entry) {
                    is TimelineEntry.Event -> {
                        val summary = summaries[entry.event.eventId]
                        if (summary != null) {
                            EventCard(
                                summary = summary,
                                // Only when opening it would reveal something. A chevron that
                                // expands into nothing reads as a broken row.
                                expanded = entry.event.eventId in expandedIds,
                                expandable = row.hasChildren,
                                cover = eventCovers[entry.event.eventId],
                                groupName = null,
                                onOpen = { onOpenEvent(entry.event.eventId) },
                                onToggleExpand = { onToggleExpand(entry.event.eventId) },
                                onRename = { onEdit(summary) },
                                onMoveToGroup = { onMoveToGroup(summary) },
                                onDelete = { onDelete(summary) }
                            )
                        }
                    }

                    is TimelineEntry.Recording ->
                        recordsById[entry.record.recordId]?.let { tile(it) }
                }
            }
        }
    }
}
