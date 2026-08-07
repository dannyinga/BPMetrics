package inga.bpmetrics.ui.export

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.EventGroupEntity
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.displayName
import inga.bpmetrics.ui.components.PersonSwatch
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getDurationString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString

/** The kinds of thing an export can be taken from, as a tab row. */
private enum class SourceKind(val label: String) {
    EVENTS("Events"),
    GROUPS("Groups"),
    RECORDINGS("Recordings")
}

/**
 * Step 1 — what is being exported.
 *
 * An event or a group rather than a hand-picked set of recordings, wherever possible: the source
 * names the export, and it decides which clips step 2 has to offer. Picking recordings by hand
 * still works, for the session that was never filed.
 */
@Composable
fun SourceStep(
    events: List<EventEntity>,
    groups: List<EventGroupEntity>,
    recordings: List<BpmRecord>,
    peopleById: Map<Long, PersonEntity>,
    selected: ExportSource,
    onSelect: (ExportSource) -> Unit
) {
    var kind by remember { mutableStateOf(SourceKind.EVENTS) }

    Column(Modifier.fillMaxSize()) {
        SecondaryScrollableTabRow(
            selectedTabIndex = kind.ordinal,
            containerColor = Color.Transparent,
            edgePadding = 12.dp
        ) {
            SourceKind.entries.forEach { entry ->
                Tab(
                    selected = kind == entry,
                    onClick = { kind = entry },
                    text = { Text(entry.label) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (kind) {
                SourceKind.EVENTS -> items(events, key = { "event-${it.eventId}" }) { event ->
                    val count = recordings.count { it.metadata.eventId == event.eventId }
                    SourceRow(
                        title = event.displayName,
                        subtitle = "$count recording${if (count == 1) "" else "s"}",
                        isSelected = selected == ExportSource.Event(event.eventId),
                        onClick = { onSelect(ExportSource.Event(event.eventId)) }
                    )
                }

                SourceKind.GROUPS -> items(groups, key = { "group-${it.groupId}" }) { group ->
                    val eventCount = events.count { it.groupId == group.groupId }
                    SourceRow(
                        title = group.displayName,
                        subtitle = "$eventCount event${if (eventCount == 1) "" else "s"}",
                        isSelected = selected == ExportSource.Group(group.groupId),
                        onClick = { onSelect(ExportSource.Group(group.groupId)) }
                    )
                }

                SourceKind.RECORDINGS -> items(
                    recordings,
                    key = { "record-${it.metadata.recordId}" }
                ) { record ->
                    val wearer = record.metadata.personId?.let { peopleById[it] }
                    val chosen = (selected as? ExportSource.Recordings)
                        ?.recordIds
                        ?.contains(record.metadata.recordId) == true
                    SourceRow(
                        title = record.displayName(wearer?.displayName),
                        subtitle = "${getDateString(record.metadata.date)} · " +
                            getDurationString(record.metadata.durationMs),
                        accent = wearer?.colorArgb?.let { Color(it) },
                        isSelected = chosen,
                        onClick = {
                            // Multi-select, because "these three that happened together" is a
                            // legitimate source and picking one at a time would not express it.
                            val current = (selected as? ExportSource.Recordings)?.recordIds
                                .orEmpty()
                            val next = if (chosen) {
                                current - record.metadata.recordId
                            } else {
                                current + record.metadata.recordId
                            }
                            onSelect(
                                if (next.isEmpty()) ExportSource.None
                                else ExportSource.Recordings(next)
                            )
                        }
                    )
                }
            }

            val isEmpty = when (kind) {
                SourceKind.EVENTS -> events.isEmpty()
                SourceKind.GROUPS -> groups.isEmpty()
                SourceKind.RECORDINGS -> recordings.isEmpty()
            }
            if (isEmpty) {
                item {
                    Text(
                        when (kind) {
                            SourceKind.EVENTS -> "No events yet. File some recordings into one in " +
                                "the Library, or pick them directly under Recordings."
                            SourceKind.GROUPS -> "No groups yet. Group some events in the Library."
                            SourceKind.RECORDINGS -> "No recordings yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    accent: Color? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            )
        } else {
            CardDefaults.cardColors()
        },
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            accent?.let {
                Box(Modifier.size(10.dp).clip(CircleShape).background(it))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Step 2 — which clips, and whose curves go on each.
 *
 * The video is the unit, not the event. An event is a concert; during it you filmed six things,
 * and each is its own export with its own overlay. Every clip lists the people who were recording
 * while *it* was filming, so a clip shot after someone's watch stopped does not offer their curve.
 */
@Composable
fun ContentsStep(
    clips: List<ClipSelection>,
    records: List<BpmRecord>,
    peopleById: Map<Long, PersonEntity>,
    loading: Boolean,
    hasNoClips: Boolean,
    onToggleClip: (android.net.Uri) -> Unit,
    onToggleRecord: (android.net.Uri, Long) -> Unit
) {
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (hasNoClips) {
        // Not an error. VideoExporter already renders against a solid background when no overlay
        // is given, which is the right output for a session nobody filmed.
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "No video from this time",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Nothing on this phone was filmed while these recordings were running. The export " +
                    "will draw the curves on a plain background instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val recordsById = remember(records) { records.associateBy { it.metadata.recordId } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val ticked = clips.count { it.selected }
            Text(
                "$ticked of ${clips.size} clip${if (clips.size == 1) "" else "s"} · " +
                    "one export each",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(clips, key = { it.clip.uri.toString() }) { selection ->
            ClipCard(
                selection = selection,
                // Only people who were actually recording during this clip. Offering the rest
                // would let someone tick a curve that has no data for the footage.
                candidates = records.filter { selection.clip.overlaps(it) },
                recordsById = recordsById,
                peopleById = peopleById,
                onToggleClip = { onToggleClip(selection.clip.uri) },
                onToggleRecord = { onToggleRecord(selection.clip.uri, it) }
            )
        }
    }
}

@Composable
private fun ClipCard(
    selection: ClipSelection,
    candidates: List<BpmRecord>,
    recordsById: Map<Long, BpmRecord>,
    peopleById: Map<Long, PersonEntity>,
    onToggleClip: () -> Unit,
    onToggleRecord: (Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selection.selected) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selection.selected, onCheckedChange = { onToggleClip() })
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        getTimeString(selection.clip.startedAtMs),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${getDurationString(selection.clip.durationMs)} · " +
                            selection.clip.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            if (candidates.isEmpty()) {
                Text(
                    "Nobody was recording during this clip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                )
                return@Column
            }

            Spacer(Modifier.height(6.dp))
            inga.bpmetrics.ui.components.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                candidates.forEach { record ->
                    val id = record.metadata.recordId
                    val person = record.metadata.personId?.let { peopleById[it] }
                    FilterChip(
                        selected = id in selection.recordIds,
                        enabled = selection.selected,
                        onClick = { onToggleRecord(id) },
                        leadingIcon = person?.let { { PersonSwatch(it.colorArgb, size = 12) } },
                        label = {
                            Text(
                                person?.displayName
                                    ?: recordsById[id]?.metadata?.wearerName?.takeIf { it.isNotBlank() }
                                    ?: "Unknown"
                            )
                        }
                    )
                }
            }
        }
    }
}
