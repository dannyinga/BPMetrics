package inga.bpmetrics.ui.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.ScopeRef
import inga.bpmetrics.library.clock
import inga.bpmetrics.library.clockDiffersFromReader
import inga.bpmetrics.library.displayName
import inga.bpmetrics.ui.analysis.AnalysisScreen
import inga.bpmetrics.ui.analysis.AnalysisViewModel
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.record.BpmRecordViewModel
import inga.bpmetrics.ui.tags.EffectiveTagChip
import inga.bpmetrics.ui.tags.TagSelectionDialog
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString

/**
 * A recording, and its analysis.
 *
 * The last subject to fold, and the one that makes the claim honest: **a recording is not a
 * different kind of page, it is the narrowest scope there is.** It gets the same chart, the same
 * split control, the same rankings and the same refinement as a festival, because a festival is
 * only this with more recordings in it.
 *
 * What is genuinely recording-shaped stays: its cover and where it sits, its venue and clock, its
 * own tags, and where it stands among that person's other recordings. Editing is in the app bar,
 * opening the dialogs the old screen already had — none of them were the problem.
 */
@Composable
fun RecordingDetailScreen(
    navController: NavController,
    repository: LibraryRepository,
    recordId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onExport: () -> Unit,
    onOpenEvent: (Long) -> Unit
) {
    val context = LocalContext.current

    val subject: BpmRecordViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "record-subject-$recordId",
        factory = BpmRecordViewModel.Factory(repository, recordId)
    )
    val analysis: AnalysisViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "record-analysis-$recordId",
        factory = AnalysisViewModel.forScope(repository, ScopeRef.Recording(recordId))
    )

    val record by subject.record.collectAsStateWithLifecycle()
    val watchName by subject.watchName.collectAsStateWithLifecycle()
    val people by subject.people.collectAsStateWithLifecycle()
    val effectiveTags by subject.effectiveTags.collectAsStateWithLifecycle()
    val insights by subject.insights.collectAsStateWithLifecycle()
    val placement by subject.placement.collectAsStateWithLifecycle()
    val cover by subject.cover.collectAsStateWithLifecycle()
    val place by subject.place.collectAsStateWithLifecycle()
    val knownLocations by subject.locations.collectAsStateWithLifecycle()

    // Registered once for the screen: a launcher outlives the dialog that starts it, so it cannot
    // live inside the editor.
    var framingCover by remember { mutableStateOf(false) }
    val pickCover = inga.bpmetrics.ui.components.rememberCoverPicker { uri ->
        subject.setOwnCover(context, uri) { ok ->
            // Straight into framing, while the choice is fresh.
            if (ok) framingCover = true
            else Toast.makeText(context, "That image could not be read", Toast.LENGTH_LONG).show()
        }
    }

    // One recording's readings as a file, from the page that recording is on. It was in the old
    // app bar and the fold dropped it; the library's multi-select still has the bulk version.
    val saveCsv = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: android.net.Uri? ->
        uri?.let { target ->
            record?.let { rec ->
                context.contentResolver.openOutputStream(target)?.use { out ->
                    out.write(inga.bpmetrics.export.CsvExporter.getCsvString(rec).toByteArray())
                }
            }
        }
    }

    var editing by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var splitting by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val r = record
    val wearer = r?.metadata?.personId?.let { id -> people.firstOrNull { it.personId == id } }

    AnalysisScreen(
        navController = navController,
        viewModel = analysis,
        onOpenDrawer = {},
        onBack = onBack,
        // No title: the header below carries the name over the cover. It used to be in both.
        title = null,
        onExport = onExport,
        // Splitting, driven by the chart. The dialog it used to open had two text fields and no
        // picture; the chart could show the exact stretch and had no way to act on it.
        onSplitFromTimeline = r?.let { rec ->
            { fromMs: Long, toMs: Long -> splitBetween(context, subject, rec, fromMs, toMs) }
        },
        subjectHeader = {
            if (r != null) {
                val uiState by analysis.uiState.collectAsStateWithLifecycle()
                SubjectHeader(
                    title = r.displayName(wearer?.displayName, watchName),
                    subtitle = buildString {
                        append(getDateString(r.metadata.date, r.clock))
                        append(" · ")
                        append(getTimeString(r.metadata.startTime, r.clock))
                        // Named only where it differs from the reader's. Stamping the zone on
                        // every time in a library that never leaves one is noise; omitting it on
                        // the one made three zones away is a wrong number with no warning.
                        if (r.metadata.clockDiffersFromReader) {
                            append(" ")
                            append(r.clock.id.substringAfterLast('/').replace('_', ' '))
                        }
                        wearer?.let { append(" · ${it.displayName}") }
                        place?.location?.displayName?.let { append(" · $it") }
                    },
                    cover = cover,
                    trail = placement.trail(),
                    lowBpm = uiState.minTrio.takeIf { !uiState.isEmpty },
                    avgBpm = uiState.avgTrio.takeIf { !uiState.isEmpty },
                    highBpm = uiState.maxTrio.takeIf { !uiState.isEmpty },
                    // How long it ran. It used to head the zone breakdown further down the page;
                    // it belongs with the other figures for the same recording.
                    counts = if (uiState.isEmpty) null else {
                        inga.bpmetrics.ui.analysis.shortDuration(uiState.totalActiveDurationMs)
                    },
                    onOpenAncestor = onOpenEvent,
                    tags = effectiveTags,
                    onRemoveTag = { subject.removeTag(it) },
                    onExplainTag = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
                    onEditCover = { framingCover = true }
                )
            }
        },
        subjectActions = {
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit recording")
            }
        },
        subjectOverflow = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Tags and the venue moved into the editor with the rest of what a recording
                    // is. What is left here are the two things that are not edits: making a new
                    // recording out of part of this one, and destroying it.
                    DropdownMenuItem(
                        text = { Text("Save CSV…") },
                        leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            r?.let { saveCsv.launch(it.metadata.title.replace(" ", "_") + ".csv") }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Split…") },
                        leadingIcon = { Icon(Icons.Default.CallSplit, contentDescription = null) },
                        onClick = { menuOpen = false; splitting = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete recording") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; deleting = true }
                    )
                }
            }
        },
        // Its standing among that person's others — a fact about the recording, not the scope, and
        // far too tall for a header that has to stay out of the chart's way.
        summaryExtra = r?.let {
            {
                RecordInsightsSection(
                    insights = insights,
                    clock = it.clock,
                    onSelectMoment = { /* Scrubbing lives in the chart above. */ }
                )
            }
        }
    )

    // Opened from the corner of the cover rather than from the editor. See [SubjectHeader].
    if (framingCover && r != null) {
        inga.bpmetrics.ui.components.CoverCropDialog(
            cover = r.metadata.ownCover,
            onPick = pickCover,
            title = "Frame this recording",
            previewContent = {},
            onDismiss = { framingCover = false },
            onConfirm = { subject.setOwnCoverCrop(it); framingCover = false },
            // Stays open: removing is usually the first half of replacing.
            onRemove = { subject.clearOwnCover(context) }
        )
    }

    if (editing && r != null) {
        // The title, description and who wore it — the three fields the old screen edited inline in
        // its app bar. A dialog now, because the app bar belongs to the analysis as much as to the
        // subject and a text field growing out of it made the page jump.
        RecordEditDialog(
            record = r,
            people = people,
            locations = knownLocations,
            inheritedPlaceName = place?.takeIf { it.isInherited }?.location?.displayName,
            tagCount = r.tags.size,
            onEditTags = { tagging = true },
            onDismiss = { editing = false },
            onConfirm = { title, description, deviceId, personId, locationId ->
                subject.updateTitle(title)
                subject.updateDescription(description)
                subject.updateDeviceAndWearer(deviceId, personId)
                subject.setLocation(locationId)
                editing = false
            }
        )
    }

    if (tagging && r != null) {
        val categories by subject.getAllCategories().collectAsStateWithLifecycle(emptyList())
        TagSelectionDialog(
            onDismiss = { tagging = false },
            onSave = { selected ->
                val current = r.tags.map { it.tagId }
                current.forEach { if (it !in selected) subject.removeTag(it) }
                selected.forEach { if (it !in current) subject.addTag(it) }
                tagging = false
            },
            categories = categories,
            getTagsByCategoryFlow = { subject.getTagsByCategory(it) },
            onCreateTag = { axis, name, onMade -> subject.createTag(axis, name, onMade) },
            // Its own tags only. An inherited one cannot be removed here, so offering it pre-ticked
            // would make unticking it look broken.
            initialSelectedTagIds = r.tags.map { it.tagId }
        )
    }

    if (splitting && r != null) {
        SplitRecordDialog(
            record = r,
            onDismiss = { splitting = false },
            // Offsets from the dialog, instants from the chart — one implementation either way.
            onSplit = { startMs, endMs ->
                splitBetween(
                    context,
                    subject,
                    r,
                    r.metadata.startTime + startMs,
                    r.metadata.startTime + endMs
                )
                splitting = false
            }
        )
    }

    if (deleting && r != null) {
        DeleteConfirmDialog(
            title = "Delete this recording?",
            message = "Its readings go with it. This cannot be undone.",
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                subject.deleteRecord()
                onDeleted()
            }
        )
    }
}

/**
 * Cuts the readings between two wall-clock instants into a new recording.
 *
 * Shared by the two ways in — the chart's own Split, and the older typed dialog in the overflow —
 * so the two cannot disagree about what a split produces. Timestamps are rebased to the new
 * recording's own start, so it reads as a recording rather than as a fragment that begins forty
 * minutes in.
 */
private fun splitBetween(
    context: android.content.Context,
    subject: BpmRecordViewModel,
    record: inga.bpmetrics.library.BpmRecordWithPoints,
    fromMs: Long,
    toMs: Long
) {
    val base = record.metadata.startTime
    // The chart works in wall-clock instants and the readings are stored relative to the start.
    val fromOffset = fromMs - base
    val toOffset = toMs - base

    val points = record.dataPoints
        .filter { it.timestamp in fromOffset..toOffset }
        .map { it.copy(timestamp = it.timestamp - fromOffset) }

    if (points.isEmpty()) {
        Toast.makeText(context, "Nothing recorded in that range", Toast.LENGTH_SHORT).show()
        return
    }

    subject.splitRecord(
        inga.bpmetrics.core.BpmWatchRecord(
            date = java.sql.Date(base + fromOffset),
            dataPoints = points.map { inga.bpmetrics.core.BpmDataPoint(it.timestamp, it.bpm) },
            startTime = base + fromOffset,
            endTime = base + toOffset
        ),
        "${record.metadata.title} (Split)"
    )
    Toast.makeText(context, "New record created from split", Toast.LENGTH_SHORT).show()
}
