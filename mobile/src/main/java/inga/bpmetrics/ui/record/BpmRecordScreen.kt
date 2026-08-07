package inga.bpmetrics.ui.record

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import inga.bpmetrics.export.CsvExporter
import inga.bpmetrics.library.displayName
import inga.bpmetrics.ui.analysis.ConcurrentAnalysis
import inga.bpmetrics.ui.analysis.ConcurrentChart
import inga.bpmetrics.ui.analysis.RecordInsights
import inga.bpmetrics.ui.analysis.rememberConcurrentViewWindow
import inga.bpmetrics.ui.analysis.shortDuration
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import inga.bpmetrics.ui.tags.EffectiveTagChip
import inga.bpmetrics.ui.tags.TagSelectionDialog
import inga.bpmetrics.ui.components.FlowRow
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.components.PersonPicker
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share

/**
 * The record detail screen, displaying full statistics and allowing editing of metadata and tags.
 *
 * @param viewModel The [inga.bpmetrics.ui.record.BpmRecordViewModel] for the specific record.
 * @param onBack Callback for navigating back.
 * @param onDeleted Callback when the record is successfully deleted.
 * @param onShowDetailedGraph Callback to navigate to the detailed graph view.
 * @param onManageTags Callback to navigate to the tag management screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpmRecordScreen(
    viewModel: BpmRecordViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onShowDetailedGraph: () -> Unit,
    onManageTags: () -> Unit,
    onOpenEvent: (Long) -> Unit = {},
    onOpenGroup: (Long) -> Unit = {}
) {
    val record by viewModel.record.collectAsState()
    val watchName by viewModel.watchName.collectAsState()
    val people by viewModel.people.collectAsState()
    val effectiveTags by viewModel.effectiveTags.collectAsState()
    val analysis by viewModel.analysis.collectAsState()
    val insights by viewModel.insights.collectAsState()
    val placement by viewModel.placement.collectAsState()
    var scrubbedMs by remember { mutableStateOf<Long?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val saveCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            record?.let { r ->
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(CsvExporter.getCsvString(r).toByteArray())
                }
            }
        }
    }

    record?.let { r ->
        var editedTitle by remember(r.metadata.title) { mutableStateOf(r.metadata.title) }
        var editedDescription by remember(r.metadata.description) { mutableStateOf(r.metadata.description) }
        var editedDeviceId by remember(r.metadata.deviceId) { mutableStateOf(r.metadata.deviceId) }
        var editedPersonId by remember(r.metadata.personId) { mutableStateOf(r.metadata.personId) }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                    title = {
                        Column {
                            if (isEditing) {
                                OutlinedTextField(
                                    value = editedTitle,
                                    onValueChange = { editedTitle = it },
                                    label = { Text("Title") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    // The generated name when there is no real title, so this
                                    // page never opens on "Untitled 4" either.
                                    r.displayName(
                                        people.firstOrNull { p ->
                                            p.personId == r.metadata.personId
                                        }?.displayName,
                                        watchName
                                    ),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text("${getDateString(r.metadata.date)} ${getTimeString(r.metadata.startTime)}", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (isEditing) {
                                viewModel.updateTitle(editedTitle)
                                viewModel.updateDescription(editedDescription)
                                viewModel.updateDeviceAndWearer(editedDeviceId, editedPersonId)
                            }
                            isEditing = !isEditing
                        }) {
                            Icon(if (isEditing) Icons.Default.Done else Icons.Default.Edit, contentDescription = if (isEditing) "Save" else "Edit")
                        }
                        if (!isEditing) {
                            IconButton(onClick = {
                                saveCsvLauncher.launch("${r.metadata.title.replace(" ", "_")}.csv")
                            }) {
                                Icon(Icons.Default.Save, contentDescription = "Save CSV Locally")
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))

                // Where this recording sits. Per §2.4 no analysis screen is a dead end, and a
                // recording is the bottom of the hierarchy — the only way out is upward.
                Breadcrumb(
                    placement = placement,
                    onOpenEvent = onOpenEvent,
                    onOpenGroup = onOpenGroup
                )

                Spacer(Modifier.height(8.dp))

                // Display low, avg, and max BPM metrics.
                BpmTrio(
                    low = r.minDataPoint?.bpm?.toInt() ?: 0,
                    avg = r.metadata.avg?.toInt() ?: 0,
                    max = r.maxDataPoint?.bpm?.toInt() ?: 0,
                    iconSize = 32.dp,
                    fontSize = 24.sp
                )

                Spacer(Modifier.height(16.dp))

                // Device & Wearer Metadata Card
                if (isEditing) {
                    PersonPicker(
                        people = people,
                        selectedId = editedPersonId,
                        onSelect = { editedPersonId = it },
                        label = "Wearer"
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editedDeviceId,
                            onValueChange = { editedDeviceId = it },
                            label = { Text("Watch model") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            // Editable, but only used when the watch has no name of its own.
                            enabled = watchName == null
                        )
                    }
                    if (watchName != null) {
                        // Otherwise editing the model looks broken: it saves, and nothing changes.
                        Text(
                            "This recording is labelled \"$watchName\" from the Watches section. " +
                                "Rename the watch there to change it everywhere.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val wearerLabel = r.metadata.personId?.let { id ->
                            people.firstOrNull { it.personId == id }?.displayName
                        } ?: r.metadata.wearerName.takeIf { it.isNotBlank() }
                        val deviceBadge = buildString {
                            if (wearerLabel != null) append("👤 $wearerLabel  •  ")
                            append("⌚ ${r.watchLabel(watchName)}")
                        }
                        Text(
                            text = deviceBadge,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text("Description", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (isEditing) {
                        OutlinedTextField(
                            value = editedDescription,
                            onValueChange = { editedDescription = it },
                            label = { Text("Description") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(r.metadata.description.ifBlank { "No description provided." }, style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tags", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { showTagDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Tag")
                        }
                    }

                    // Wrapping FlowRow for tags
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp), 
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (effectiveTags.isEmpty()) {
                            Text("No tags", style = MaterialTheme.typography.bodySmall)
                        } else {
                            // Inherited tags are drawn outlined and cannot be removed here — the
                            // recording is under an event or group that carries them, so removing
                            // one from this recording alone could not mean anything.
                            effectiveTags.forEach { effective ->
                                EffectiveTagChip(
                                    effective = effective,
                                    onRemove = { viewModel.removeTag(effective.tag.tagId) },
                                    onExplain = { message ->
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))

                // The event page's chart at one lane, rather than the static preview that used to
                // sit here. Same code, so this scrubs and zooms exactly as that one does — there
                // were two chart implementations and only one of them could.
                if (!analysis.isEmpty) {
                    RecordChartSection(
                        analysis = analysis,
                        scrubbedMs = scrubbedMs,
                        onScrub = { scrubbedMs = it },
                        onOpenDetail = onShowDetailedGraph
                    )

                    Spacer(Modifier.height(20.dp))

                    RecordInsightsSection(
                        insights = insights,
                        onSelectMoment = { scrubbedMs = it }
                    )
                }
                
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (showTagDialog) {
            TagSelectionDialog(
                onDismiss = { showTagDialog = false },
                onSave = { selectedIds ->
                    val currentIds = r.tags.map { it.tagId }
                    currentIds.forEach { if (!selectedIds.contains(it)) viewModel.removeTag(it) }
                    selectedIds.forEach { if (!currentIds.contains(it)) viewModel.addTag(it) }
                    showTagDialog = false
                },
                onManageTags = {
                    showTagDialog = false
                    onManageTags()
                },
                viewModel = viewModel,
                initialSelectedTagIds = r.tags.map { it.tagId }
            )
        }

        if (showDeleteConfirm) {
            DeleteConfirmDialog(
                title = "Delete Record",
                message = "Are you sure you want to permanently delete this record? This action cannot be undone.",
                onDismiss = { showDeleteConfirm = false },
                onConfirm = {
                    showDeleteConfirm = false
                    viewModel.deleteRecord()
                    onDeleted()
                }
            )
        }
    } ?: run { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
}

/**
 * The way back up: this recording's event, and that event's group.
 *
 * Nothing at all when the recording is unfiled, rather than a row of empty chips â€” an absence
 * stated is worse than an absence.
 */
@Composable
private fun Breadcrumb(
    placement: RecordPlacement,
    onOpenEvent: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit
) {
    if (placement.event == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        placement.group?.let { group ->
            Text(
                text = group.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onOpenGroup(group.groupId) }
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            )
            Text(
                " > ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = placement.event.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { onOpenEvent(placement.event.eventId) }
                .padding(vertical = 4.dp, horizontal = 2.dp)
        )
    }
}

/** The chart, plus the reading under the scrub line and a way through to export. */
@Composable
private fun RecordChartSection(
    analysis: ConcurrentAnalysis,
    scrubbedMs: Long?,
    onScrub: (Long?) -> Unit,
    onOpenDetail: () -> Unit
) {
    val window = rememberConcurrentViewWindow(analysis)
    val series = analysis.series.firstOrNull()

    Column(modifier = Modifier.fillMaxWidth()) {
        ConcurrentChart(
            analysis = analysis,
            window = window,
            scrubbedMs = scrubbedMs,
            onScrub = onScrub,
            // The blue-to-red gradient rather than the wearer's colour. With one lane there is
            // nobody to tell apart, so the colour is free to say how high the rate is instead —
            // which is what the graph detail screen has always done. The wearer keeps their colour
            // as the stripe, the swatch and the breadcrumb accent everywhere else.
            colourByValue = true
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = scrubbedMs?.let { at ->
                    val bpm = series?.bpmAt(at)
                    // No reading inside a dropout, which is a different thing from a heart rate
                    // of zero and has to read differently.
                    "${getTimeString(at)} - ${bpm?.roundToInt()?.toString() ?: "not measured"}"
                } ?: "Tap or drag to read a moment, pinch to zoom",
                style = MaterialTheme.typography.bodySmall
            )
            if (window.isZoomed) {
                TextButton(onClick = { window.reset() }) { Text("Show all") }
            }
        }

        if (window.isZoomed) {
            Slider(
                value = window.scrollFraction,
                onValueChange = { window.scrollTo(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        TextButton(
            onClick = onOpenDetail,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("Graph details & export") }
    }
}

/**
 * What this recording did, beyond three numbers.
 *
 * Min, average and max describe a recording the way three points describe a curve. Where the time
 * went, when it moved, how much of it was actually measured, and whether this was a big one for
 * the person who made it are the things that make a single recording worth opening.
 */
@Composable
private fun RecordInsightsSection(
    insights: RecordInsights,
    onSelectMoment: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        insights.comparison?.takeIf { it.isMeaningful }?.let { comparison ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        ordinal(comparison.peakRank) + " highest of their " +
                            "${comparison.totalRecordings} recordings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            comparison.percentVsAverage > 0 ->
                                "${comparison.percentVsAverage}% above their usual average"
                            comparison.percentVsAverage < 0 ->
                                "${-comparison.percentVsAverage}% below their usual average"
                            else -> "About their usual average"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            InsightStat("Measured", shortDuration(insights.activeDurationMs), Modifier.weight(1f))
            insights.longestClimb?.let { climb ->
                InsightStat(
                    "Biggest climb",
                    "+${climb.riseBpm.roundToInt()} in ${shortDuration(climb.durationMs)}",
                    Modifier.weight(1.4f)
                )
            }
        }

        // Said plainly rather than drawn as an unbroken line. A recording with a dropout is not a
        // recording of a very smooth heart rate.
        if (insights.gaps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${insights.gaps.size} gap${if (insights.gaps.size == 1) "" else "s"}, " +
                    "${shortDuration(insights.missingMs)} not measured",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        val zones = insights.zoneTimes.filter { it.durationMs > 0L }
        if (zones.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Time in range",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            zones.forEach { zone ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${zone.zone.name} (${zone.zone.lowerBpm}" +
                            (zone.zone.upperBpm?.let { "-$it" } ?: "+") + ")",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${shortDuration(zone.durationMs)}  ${(zone.share * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (insights.peaks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Moments",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Above this recording's own average, so a flat recording reports none.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            insights.peaks.forEach { peak ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectMoment(peak.wallClockMs) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        getTimeString(peak.wallClockMs),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "${peak.bpm.roundToInt()} bpm",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** "1st", "2nd", "23rd" â€” the ranking reads as a sentence rather than as a number. */
private fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

