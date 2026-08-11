package inga.bpmetrics.ui.export

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SwapVert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inga.bpmetrics.library.clock
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.displayName
import inga.bpmetrics.ui.components.PersonAvatar
import inga.bpmetrics.ui.components.overCover
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getDurationString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString

/** The kinds of thing an export can be taken from, as a tab row. */
private enum class SourceKind(val label: String) {
    EVENTS("Events"),
    GROUPS("Collections"),
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
    collections: List<inga.bpmetrics.library.CollectionEntity>,
    /* A picker lists names and dates; it draws no curve, so the summary form is what it takes. */
    recordings: List<BpmRecord>,
    peopleById: Map<Long, PersonEntity>,
    selected: ExportSource,
    onSelect: (ExportSource) -> Unit
) {
    var kind by remember { mutableStateOf(SourceKind.EVENTS) }

    // Collapsed, and empty is the whole point: a library of forty events opening as forty rows is
    // a wall, and the festivals anyone is choosing between are the six at the top of it.
    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    var newestFirst by remember { mutableStateOf(true) }

    // The tree, flattened with depths — the same walk the timeline and the event picker use, so
    // all three agree about what is inside what and about the order it happened in.
    // When each event happened, from its window or the earliest recording beneath it. Without this
    // the fallback is `createdAt` — the order things were typed in — and most events have no
    // window, so the list read as creation order however chronological it claimed to be.
    val startBy = remember(events, recordings) {
        inga.bpmetrics.library.EventTree.startsOf(events, recordings.map { it.metadata })
    }
    val eventRows = remember(events, expanded, newestFirst, startBy) {
        inga.bpmetrics.library.EventTree.flatten(events, expanded, newestFirst, startBy)
    }

    // The whole subtree, not the recordings filed directly on each event. A festival almost never
    // holds any itself — its days do — so counting directly reported every container as empty.
    val countBy = remember(events, recordings) {
        inga.bpmetrics.library.EventTree.recordCountsByEvent(events, recordings.map { it.metadata })
    }

    // Inherited, not each event's own. This list read `ownCover` and so showed a day inside a
    // photographed festival as a grey row, while the library one screen away showed the festival's
    // picture on all six days. Same walk as the library's now — see [CoverResolver.byEvent].
    val coverBy = remember(events) { inga.bpmetrics.library.CoverResolver.byEvent(events) }

    Column(Modifier.fillMaxSize()) {
        // Video or image is asked on Contents now, at the top of the step it actually decides.
        // Source is the same set of recordings either way.

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

        // Newest first, reversible. A list of occasions is read in time order or it is not read at
        // all — but which end you start from depends on whether you are looking for last night or
        // for the festival three years ago.
        if (kind == SourceKind.EVENTS) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (newestFirst) "Newest first" else "Oldest first",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { newestFirst = !newestFirst }) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Reverse the order")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (kind) {
                // Nested exactly as the timeline is, and carrying their covers. A flat list of
                // names asks you to read every row; the picture is how anyone finds the right
                // "Day 1" out of four, and the indent is what says which festival it belongs to.
                SourceKind.EVENTS -> items(
                    eventRows,
                    key = { "event-${it.event.eventId}" }
                ) { node ->
                    val event = node.event
                    val count = countBy[event.eventId] ?: 0
                    SourceRow(
                        title = event.displayName,
                        subtitle = buildString {
                            event.type?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
                            append("$count recording${if (count == 1) "" else "s"}")
                        },
                        cover = coverBy[event.eventId],
                        placeholder = Icons.Default.Event,
                        depth = node.depth,
                        // Only where opening it reveals something. A chevron that expands into
                        // nothing reads as a broken row.
                        expanded = if (!node.hasChildren) null
                            else event.eventId in expanded,
                        onToggleExpand = {
                            expanded = if (event.eventId in expanded) {
                                expanded - event.eventId
                            } else {
                                expanded + event.eventId
                            }
                        },
                        isSelected = selected == ExportSource.Event(event.eventId),
                        onClick = { onSelect(ExportSource.Event(event.eventId)) }
                    )
                }

                SourceKind.GROUPS -> items(
                    // Alphabetical. A set has no time of its own — it gathers things that did not
                    // happen together — so the only order that means anything is its name.
                    collections.sortedBy { it.displayName.lowercase() },
                    key = { "collection-${it.collectionId}" }
                ) { collection ->
                    SourceRow(
                        title = collection.displayName,
                        // Deliberately not a count here. What a set resolves to needs the tree
                        // walk, and doing it per row while scrolling — or worse, guessing it from
                        // the link table — is exactly how the picker came to promise one number
                        // and the export deliver another. What it *is* costs nothing and is worth
                        // knowing before exporting: a frozen set will never gain a recording.
                        subtitle = when {
                            collection.isFrozen -> "Frozen"
                            collection.isSmart -> "Living"
                            else -> "Collection"
                        },
                        cover = collection.ownCover,
                        placeholder = Icons.Default.Bookmarks,
                        isSelected = selected == ExportSource.Group(collection.collectionId),
                        onClick = { onSelect(ExportSource.Group(collection.collectionId)) }
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
                        subtitle = "${getDateString(record.metadata.date, record.clock)} · " +
                            getDurationString(record.metadata.durationMs),
                        person = wearer,
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
                SourceKind.GROUPS -> collections.isEmpty()
                SourceKind.RECORDINGS -> recordings.isEmpty()
            }
            if (isEmpty) {
                item {
                    Text(
                        when (kind) {
                            SourceKind.EVENTS -> "No events yet. File some recordings into one in " +
                                "the Library, or pick them directly under Recordings."
                            SourceKind.GROUPS -> "No collections yet. Gather some events in the Library."
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
    /** Whose recording this is, drawn as their face. Null for rows that are not one person's. */
    person: PersonEntity? = null,
    /** Its own picture. See [inga.bpmetrics.ui.components.CoverThumbnail]. */
    cover: inga.bpmetrics.library.Cover? = null,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** How deep in the tree, so the picker nests the way the timeline does. */
    depth: Int = 0,
    /** Open, shut, or null where there is nothing inside to reveal. */
    expanded: Boolean? = null,
    onToggleExpand: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth.coerceAtMost(4) * 16).dp)
            .clickable(onClick = onClick),
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
        // The picture behind the row, exactly as the library and the collections list draw it —
        // same component, same scrim, same text treatment over it. It was a 34dp thumbnail here,
        // which is a *reference* to a cover rather than the cover: the picker showed a stack of
        // grey rows with a stamp on each while the same events, one screen away, were the
        // photographs. Choosing what to export is choosing between things you recognise by sight.
        inga.bpmetrics.ui.components.CoverBackground(
            cover = cover,
            modifier = Modifier.fillMaxWidth(),
            scrim = inga.bpmetrics.ui.components.CoverScrim.TILE
        ) {
            Row(
                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A recording's identity is the person, so it gets the leading slot instead. It
                // previously got a 10dp dot of their colour, which said that two rows differed
                // without saying who either one was.
                if (person != null) {
                    PersonAvatar(person, size = 34.dp)
                    Spacer(Modifier.width(12.dp))
                } else if (cover == null && placeholder != null) {
                    // Only where there is no picture: the placeholder says what kind of thing the
                    // row is, which a cover says better and does not need repeating beside.
                    inga.bpmetrics.ui.components.CoverThumbnail(null, placeholder = placeholder)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall.overCover(cover != null),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall.overCover(cover != null),
                        color = if (cover != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                // Separate from choosing the row. Opening a festival to see its days and exporting
                // the festival are different intentions, and one tap cannot be both.
                expanded?.let { open ->
                    IconButton(onClick = onToggleExpand) {
                        Icon(
                            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (open) "Collapse" else "Show what is inside",
                            tint = if (cover != null) MaterialTheme.colorScheme.onSurface
                            else LocalContentColor.current
                        )
                    }
                }
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
    records: List<BpmRecordWithPoints>,
    peopleById: Map<Long, PersonEntity>,
    loading: Boolean,
    hasNoClips: Boolean,
    oldestFirst: Boolean,
    onToggleOrder: () -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onToggleClip: (android.net.Uri) -> Unit,
    onToggleRecord: (android.net.Uri, Long) -> Unit,
    /** What each clip's video will be called, keyed by uri. See `ExportUtilityViewModel`. */
    titles: Map<String, String> = emptyMap()
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

    val ticked = clips.count { it.selected }
    // Anything with nobody recording during it can never be ticked, so it must not count
    // toward "all selected" — otherwise the button would never settle on "Deselect all".
    val selectable = clips.count { it.recordIds.isNotEmpty() }

    Column(Modifier.fillMaxSize()) {
        // Outside the list rather than the first item in it. A day of filming is forty-odd clips,
        // and as an item this scrolled away after the first two — taking with it the running count
        // of what is ticked and the only way to clear the lot. Both are needed most at the bottom
        // of a long list, which is precisely where they used to be unavailable.
        // Tight under the video/image toggle. This row and that control are one band of settings
        // about the same list, and 16dp of its own on top of the toggle's own bottom padding read
        // as two separate sections with nothing between them.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)
        ) {
            // One line: the count, then the two controls. "Choose which clips to export" was an
            // instruction above a list of tickable clips, which is the list saying what it is —
            // and it cost the row that Select all now shares with the order toggle.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$ticked of ${clips.size} clip${if (clips.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (selectable > 1) {
                    TextButton(
                        onClick = { onSelectAll(ticked < selectable) },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(if (ticked < selectable) "Select all" else "Deselect all")
                    }
                }
                TextButton(
                    onClick = onToggleOrder,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        Icons.Default.SwapVert,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (oldestFirst) "Oldest first" else "Newest first")
                }
            }
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(clips, key = { it.clip.uri.toString() }) { selection ->
                ClipCard(
                    selection = selection,
                    title = titles[selection.clip.uri.toString()].orEmpty(),
                    // Only people who were actually recording during this clip. Offering the rest
                    // would let someone tick a curve that has no data for the footage.
                    clock = records.clock,
                    candidates = records.filter { selection.clip.overlaps(it) },
                    recordsById = recordsById,
                    peopleById = peopleById,
                    onToggleClip = { onToggleClip(selection.clip.uri) },
                    onToggleRecord = { onToggleRecord(selection.clip.uri, it) }
                )
            }
        }
    }
}

@Composable
private fun ClipCard(
    /**
     * The recordings' clock.
     *
     * A clip is a file with a device timestamp, but it is being lined up against a recording — so
     * showing the two in different zones would put a clip filmed "at 21:04" next to a set that says
     * 00:04, which is exactly the confusion venues exist to remove.
     */
    clock: java.time.ZoneId,
    selection: ClipSelection,
    candidates: List<BpmRecordWithPoints>,
    recordsById: Map<Long, BpmRecordWithPoints>,
    peopleById: Map<Long, PersonEntity>,
    onToggleClip: () -> Unit,
    onToggleRecord: (Long) -> Unit,
    /** What the video will be called. Empty where nothing was recorded during it. */
    title: String = ""
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

                // A name and a time do not say what the clip is. A frame does, at a glance.
                ClipThumbnail(selection.clip.uri)
                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    // What the file will be called, first. Four clips filmed twenty minutes apart
                    // are told apart by their name, not by their timestamp — and the name is the
                    // one thing the card was not showing.
                    Text(
                        title.takeIf { it.isNotBlank() }
                            ?: getTimeString(selection.clip.startedAtMs, clock),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        getTimeString(selection.clip.startedAtMs, clock) + " · " +
                            getDurationString(selection.clip.durationMs) + " · " +
                            selection.clip.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    selection.peakBpm?.let { peak ->
                        Text(
                            "peaked at $peak bpm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // What the heart rates did during this clip, which is the thing that decides whether
            // it is worth overlaying at all. Everyone climbing together is the clip to export.
            //
            // Drawn only with its scale, never without: the curve is a fraction of that range, so
            // showing one and not the other is showing a shape and withholding what it means.
            selection.bpmScale?.takeIf { selection.sparks.isNotEmpty() }?.let { scale ->
                Spacer(Modifier.height(8.dp))
                ClipSparkline(
                    sparks = selection.sparks,
                    scale = scale,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Tall enough for three labels to sit on their own lines without touching.
                        .height(56.dp)
                        .padding(start = 12.dp)
                )
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
                        // Their face, not their colour. The chip already carries the name, so a
                        // dot added nothing a reader could not already see — an avatar is the same
                        // token they are identified by everywhere else, and recognising it is
                        // faster than reading it.
                        leadingIcon = person?.let { { PersonAvatar(it, size = 20.dp) } },
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

/**
 * A frame from the clip.
 *
 * Loaded per row rather than for the whole list, so a group with forty clips decodes only what is
 * on screen. `loadThumbnail` returns MediaStore's own cached thumbnail where one exists, which is
 * far cheaper than decoding a frame out of the video.
 */
@Composable
private fun ClipThumbnail(uri: android.net.Uri) {
    val context = LocalContext.current
    val thumbnail by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(160, 160), null)
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 54.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        thumbnail?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Cropped rather than letterboxed: the tile is a glance at the subject, and bars
                // down the sides of a portrait clip leave less of it than the crop does.
                contentScale = ContentScale.Crop
            )
        } ?: Icon(
            Icons.Default.Movie,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Everyone's curve across the clip, in their own colours, against a stated axis.
 *
 * It was drawn bare for a while, on the reasoning that a row is glanced at rather than read and
 * that numbers would slow it down. That reasoning was wrong in a specific way: the curve is
 * normalised floor-to-ceiling, so it fills its box whatever happened, and "did anything happen"
 * is precisely the question a shape *cannot* answer without knowing whether the box is fifteen
 * beats tall or ninety. Three grid lines and their values cost one glance and make every other
 * glance mean something.
 *
 * Labels are drawn into the canvas rather than laid out beside it so a number is always on its own
 * line — a label column with its own layout drifts off the line it names as soon as the row height
 * changes.
 */
@Composable
private fun ClipSparkline(
    sparks: List<ClipSpark>,
    /** What the floor and ceiling stand for. See [inga.bpmetrics.ui.export.ClipSelection.bpmScale]. */
    scale: IntRange,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val gridColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 9.sp
    )

    Canvas(modifier) {
        // Ceiling, middle, floor. Three is enough to read a slope against and few enough to stay
        // out of the way of the curves.
        val rungs = listOf(
            1f to scale.last,
            0.5f to (scale.first + scale.last) / 2,
            0f to scale.first
        ).map { (fraction, bpm) ->
            fraction to measurer.measure(bpm.toString(), labelStyle)
        }

        // The numbers get their own column on the right, so a curve never runs underneath one.
        val gutter = rungs.maxOf { it.second.size.width }.toFloat() + 6.dp.toPx()
        val plotWidth = (size.width - gutter).coerceAtLeast(1f)

        // Half a label's height of room top and bottom: it keeps the topmost and bottommost numbers
        // fully on the canvas, and doubles as the margin that stops a curve sitting at its own
        // extreme from being clipped in half by its stroke width.
        val inset = rungs.first().second.size.height / 2f
        val plotTop = inset
        val plotHeight = (size.height - inset * 2f).coerceAtLeast(1f)

        rungs.forEach { (fraction, text) ->
            val y = plotTop + (1f - fraction) * plotHeight
            drawLine(
                gridColour,
                Offset(0f, y),
                Offset(plotWidth, y),
                strokeWidth = 1f
            )
            drawText(
                textLayoutResult = text,
                topLeft = Offset(size.width - text.size.width, y - text.size.height / 2f)
            )
        }

        sparks.forEach { spark ->
            val colour = Color(spark.colorArgb)
            val path = Path()
            var started = false

            spark.points.forEachIndexed { i, value ->
                if (value == null) {
                    // A dropout breaks the line rather than dipping it to the floor, the same way
                    // the real chart does. A gap is missing data, not a heart rate of zero.
                    started = false
                    return@forEachIndexed
                }
                val x = plotWidth * (i.toFloat() / (spark.points.size - 1).coerceAtLeast(1))
                val y = plotTop + (1f - value) * plotHeight
                if (started) path.lineTo(x, y) else path.moveTo(x, y).also { started = true }
            }

            drawPath(path, colour, style = Stroke(width = 3f))
        }
    }
}
