package inga.bpmetrics.ui.analysis

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.components.FlowRow
import inga.bpmetrics.ui.tags.EffectiveTagChip
import inga.bpmetrics.ui.tags.TagSelectionDialog
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.ui.library.NameDialog
import inga.bpmetrics.ui.library.formatSpan
import inga.bpmetrics.library.TimeSpan
import inga.bpmetrics.library.ZoneTime
import inga.bpmetrics.ui.theme.BpmAvg
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow
import inga.bpmetrics.ui.record.BpmRecordTile
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import kotlin.math.roundToInt

/**
 * One event, as a whole.
 *
 * The same shape as [ConcurrentAnalysisScreen] but keyed on people rather than recordings, and with
 * the things an event has that a loose selection does not: a name, a group above it, and the
 * recordings underneath that can be taken back out.
 *
 * Every section reads the same isolation state. Tapping someone on the chart, in the legend or in
 * the summary brings their curve forward everywhere — per §2.4 of the product doc, that is the
 * interaction that makes a six-curve chart readable at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventAnalysisScreen(
    viewModel: EventDetailViewModel,
    onBack: () -> Unit,
    onOpenRecord: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
    onExportVideo: (List<BpmRecord>, String?) -> Unit,
    onExportImage: (List<BpmRecord>, String?) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isolatedId by viewModel.isolatedId.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var scrubbedMs by remember { mutableStateOf<Long?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<BpmRecord?>(null) }

    var framingCover by remember { mutableStateOf(false) }
    val pickCover = inga.bpmetrics.ui.components.rememberCoverPicker { uri ->
        viewModel.setCover(context, uri) { ok ->
            if (ok) {
                framingCover = true
            } else {
                android.widget.Toast
                    .makeText(context, "That image could not be read", android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    state.event?.ownCover?.takeIf { framingCover }?.let { cover ->
        inga.bpmetrics.ui.components.CoverCropDialog(
            cover = cover,
            title = "Frame ${state.event?.displayName.orEmpty()}",
            previewContent = {
                Text(
                    state.event?.displayName.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            },
            onDismiss = { framingCover = false },
            onConfirm = { viewModel.setCoverCrop(it); framingCover = false },
            onRemove = { viewModel.clearCover(context); framingCover = false }
        )
    }

    val analysis = state.analysis
    val viewWindow = rememberConcurrentViewWindow(analysis)

    // An event deleted from elsewhere leaves nothing to show, so the screen closes itself rather
    // than sitting on a blank page with a stale title.
    LaunchedEffect(state.missing) {
        if (state.missing) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.event?.displayName ?: "Event",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.records.isNotEmpty()) {
                        IconButton(
                            onClick = { onExportImage(state.records, state.event?.displayName) }
                        ) {
                            Icon(Icons.Default.Image, contentDescription = "Export as image")
                        }
                        IconButton(
                            onClick = { onExportVideo(state.records, state.event?.displayName) }
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = "Export as video")
                        }
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { showMenu = false; showRename = true }
                        )
                        // Set here, the picture reaches every recording in this event — including
                        // ones that arrive from a watch afterwards, which is the whole reason a
                        // cover belongs to the event rather than to each recording.
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (state.event?.coverPath == null) "Set cover…" else "Change cover…"
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                            onClick = { showMenu = false; pickCover() }
                        )
                        if (state.event?.coverPath != null) {
                            DropdownMenuItem(
                                text = { Text("Reframe cover") },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                },
                                onClick = { showMenu = false; framingCover = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove cover") },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                },
                                onClick = { showMenu = false; viewModel.clearCover(context) }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text("Delete event", color = MaterialTheme.colorScheme.error)
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { showMenu = false; showDelete = true }
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                EventHeader(
                    span = analysis.takeIf { !it.isEmpty }
                        ?.let { TimeSpan(it.windowStartMs, it.windowEndMs) },
                    recordCount = state.records.size,
                    personCount = analysis.series.size,
                    groupName = state.group?.displayName,
                    onOpenGroup = { state.group?.let { onOpenGroup(it.eventId) } }
                )
            }

            // Tagging here reaches every recording in the event without writing to any of them.
            item {
                EventTags(
                    tags = tags,
                    onAdd = { showTagDialog = true },
                    onRemove = { viewModel.removeTag(it) },
                    onExplain = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                )
            }

            if (analysis.isEmpty) {
                item {
                    Text(
                        if (state.isLoading) "Loading…" else {
                            "Nothing filed here yet. Press and hold recordings in the Library, " +
                                "then choose Add to event."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@LazyColumn
            }

            item {
                ConcurrentChart(
                    analysis = analysis,
                    window = viewWindow,
                    scrubbedMs = scrubbedMs,
                    onScrub = { scrubbedMs = it },
                    isolatedId = isolatedId,
                    onIsolate = { viewModel.isolate(it) }
                )
            }

            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (viewWindow.isZoomed) {
                                "Showing ${getTimeString(viewWindow.startMs, analysis.clock)} – " +
                                    getTimeString(viewWindow.endMs, analysis.clock)
                            } else {
                                "Pinch to zoom · tap a curve to isolate it"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (viewWindow.isZoomed) {
                            TextButton(onClick = { viewWindow.reset() }) { Text("Show all") }
                        }
                    }
                    if (viewWindow.isZoomed) {
                        Slider(
                            value = viewWindow.scrollFraction,
                            onValueChange = { viewWindow.scrollTo(it) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (isolatedId != null) {
                        TextButton(onClick = { viewModel.isolate(null) }) { Text("Show everyone") }
                    }
                }
            }

            item {
                PersonReadout(
                    clock = analysis.clock,
                    analysis = analysis,
                    at = scrubbedMs,
                    isolatedId = isolatedId,
                    onIsolate = { viewModel.isolate(it) }
                )
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Per person",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Active time counts only what was measured — a dropout is left out rather " +
                        "than filled in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(analysis.series, key = { "summary-${it.id}" }) { series ->
                PersonSummaryRow(
                    series = series,
                    isolated = isolatedId == series.id,
                    dimmed = isolatedId != null && isolatedId != series.id,
                    onClick = { viewModel.isolate(series.id) }
                )
            }

            if (analysis.peaks.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Moments you reacted together",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Ranked by how far each person was into their own range, so one person " +
                            "being fitter than another does not decide the result.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(analysis.peaks, key = { "peak-${it.wallClockMs}" }) { moment ->
                    EventMomentRow(
                        clock = analysis.clock,
                        moment = moment,
                        isSelected = scrubbedMs == moment.wallClockMs,
                        onClick = { scrubbedMs = moment.wallClockMs }
                    )
                }
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Recordings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(state.records, key = { "record-${it.metadata.recordId}" }) { record ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        BpmRecordTile(
                            record = record,
                            wearer = record.metadata.personId?.let { state.people[it] },
                            onClick = { onOpenRecord(record.metadata.recordId) }
                        )
                    }
                    IconButton(onClick = { removing = record }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove from event")
                    }
                }
            }
        }
    }

    if (showTagDialog) {
        val categories by viewModel.categories.collectAsStateWithLifecycle()
        TagSelectionDialog(
            onDismiss = { showTagDialog = false },
            onSave = { selected ->
                viewModel.setTags(selected)
                showTagDialog = false
            },
            categories = categories,
            getTagsByCategoryFlow = { viewModel.tagsInCategory(it) },
            onCreateTag = { axis, name, onMade -> viewModel.createTag(axis, name, onMade) },
            // Only what was applied *here* is pre-selected. Offering an inherited tag as though it
            // could be unticked would promise something this dialog cannot do.
            initialSelectedTagIds = tags.filterNot { it.isInherited }.map { it.tag.tagId }
        )
    }

    if (showRename) {
        NameDialog(
            title = "Rename event",
            label = "Event name",
            initial = state.event?.name.orEmpty(),
            onDismiss = { showRename = false },
            onConfirm = { name ->
                viewModel.rename(name)
                showRename = false
            }
        )
    }

    if (showDelete) {
        DeleteConfirmDialog(
            title = "Delete ${state.event?.displayName ?: "this event"}?",
            message = if (state.records.isNotEmpty()) {
                "Its ${state.records.size} recording${if (state.records.size == 1) "" else "s"} " +
                    "will be kept and move back to Unfiled. Only the event is deleted."
            } else {
                "This event has no recordings in it."
            },
            onDismiss = { showDelete = false },
            onConfirm = {
                showDelete = false
                viewModel.deleteEvent(onBack)
            }
        )
    }

    removing?.let { record ->
        DeleteConfirmDialog(
            title = "Remove from this event?",
            message = "The recording goes back to Unfiled. It is not deleted.",
            confirmLabel = "Remove",
            onDismiss = { removing = null },
            onConfirm = {
                viewModel.removeRecord(record.metadata.recordId)
                removing = null
            }
        )
    }
}

/**
 * Name, when, how much — and the way back up to the group.
 *
 * The breadcrumb is a link rather than a label because per §2.4 no analysis screen is a dead end.
 */
/**
 * Tags on the event, plus the ones it inherits from its group.
 *
 * Applying one here reaches every recording underneath without writing to any of them, which is
 * what makes moving a recording out immediately correct — see §2.5.
 */
@Composable
private fun EventTags(
    tags: List<EffectiveTag>,
    onAdd: () -> Unit,
    onRemove: (Long) -> Unit,
    onExplain: (String) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tags", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Tag this event")
            }
        }
        if (tags.isEmpty()) {
            Text(
                "A tag here applies to every recording in this event.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEach { effective ->
                    EffectiveTagChip(
                        effective = effective,
                        onRemove = { onRemove(effective.tag.tagId) },
                        onExplain = onExplain
                    )
                }
            }
        }
    }
}

@Composable
private fun EventHeader(
    span: TimeSpan?,
    recordCount: Int,
    personCount: Int,
    groupName: String?,
    onOpenGroup: () -> Unit
) {
    Column {
        groupName?.let { name ->
            Row(
                modifier = Modifier.clickable(onClick = onOpenGroup).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(formatSpan(span), style = MaterialTheme.typography.bodyMedium)
        Text(
            "$recordCount recording${if (recordCount == 1) "" else "s"} · " +
                "$personCount ${if (personCount == 1) "person" else "people"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Who is here, and what each of them was doing at the scrubbed instant.
 *
 * Rows are tappable for the same reason the curves are: on a phone a curve is a three-pixel target
 * and a row is a comfortable one.
 */
@Composable
private fun PersonReadout(
    clock: java.time.ZoneId,
    analysis: ConcurrentAnalysis,
    at: Long?,
    isolatedId: String?,
    onIsolate: (String?) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = at?.let { "At ${getTimeString(it, clock)}" }
                    ?: "Tap or drag the chart to read a moment",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            analysis.series.forEach { series ->
                val bpm = at?.let { series.bpmAt(it) }
                val dimmed = isolatedId != null && isolatedId != series.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onIsolate(series.id) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                Color(series.colorArgb).copy(alpha = if (dimmed) 0.3f else 1f)
                            )
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = series.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isolatedId == series.id) FontWeight.Bold else null,
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = if (dimmed) 0.5f else 1f)
                        )
                        series.watchLabel?.let { watch ->
                            inga.bpmetrics.ui.components.BpmIconLabel(
                                icon = Icons.Default.Watch,
                                text = watch,
                                tone = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        text = when {
                            at == null -> "${series.minBpm.roundToInt()}–${series.maxBpm.roundToInt()}"
                            // No reading here means the sensor was not delivering, which is a
                            // different thing from a heart rate of zero.
                            bpm == null -> "—"
                            else -> "${bpm.roundToInt()} bpm"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonSummaryRow(
    series: ConcurrentSeries,
    isolated: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isolated) {
                    Modifier.border(
                        2.dp,
                        Color(series.colorArgb),
                        MaterialTheme.shapes.medium
                    )
                } else Modifier
            ),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(series.colorArgb).copy(alpha = if (dimmed) 0.3f else 1f))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    series.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (series.recordIds.size > 1) {
                    // Says the lane is a merge, so a gap in the middle reads as two recordings
                    // rather than as a sensor that failed for an hour.
                    Text(
                        "${series.recordIds.size} recordings",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat("Min", "${series.minBpm.roundToInt()}", Modifier.weight(1f))
                Stat("Avg", "${series.avgBpm.roundToInt()}", Modifier.weight(1f))
                Stat("Max", "${series.maxBpm.roundToInt()}", Modifier.weight(1f))
                Stat("Active", shortDuration(series.activeDurationMs), Modifier.weight(1.2f))
            }
            // Where the time actually went. Touching 186 once and sitting above 160 for half an
            // hour are very different evenings, and min/avg/max cannot tell them apart.
            val zones = series.zoneTimes.filter { it.durationMs > 0L }
            if (zones.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ZoneBreakdown(zones, showDurations = false)
            }

            if (series.gaps.isNotEmpty()) {
                val missing = series.gaps.sumOf { it.durationMs }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${series.gaps.size} gap${if (series.gaps.size == 1) "" else "s"} · " +
                        "${shortDuration(missing)} not measured",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EventMomentRow(
    moment: GroupMoment,
    isSelected: Boolean,
    clock: java.time.ZoneId,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = getTimeString(moment.wallClockMs, clock),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${moment.participants} recording at once",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "${moment.intensityPercent}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** "1h 24m", "8m 12s", "40s" — the largest two units that say anything. */
internal fun shortDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
