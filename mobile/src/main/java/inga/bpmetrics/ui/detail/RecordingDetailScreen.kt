package inga.bpmetrics.ui.detail

import inga.bpmetrics.library.location
import inga.bpmetrics.ui.feedback.Feedback
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

    var framingCover by remember { mutableStateOf(false) }

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
    var menuOpen by remember { mutableStateOf(false) }

    // What the split dialog opens on, or null when it is closed. The chart opens it on the stretch
    // it is showing; the menu opens it on nothing in particular, which the dialog reads as the
    // whole recording. One dialog, two ways of arriving at it — see [SplitRecordDialog].
    var splitting by remember { mutableStateOf<Pair<Long?, Long?>?>(null) }

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
        // Splitting, driven by the chart. It used to cut immediately, through a dialog of its own
        // that knew nothing about the recording's bounds; now it opens the same dialog the menu
        // does, holding the window as its opening range.
        onSplitFromTimeline = r?.let { rec ->
            { fromMs: Long, toMs: Long ->
                val base = rec.metadata.startTime
                splitting = (fromMs - base) to (toMs - base)
            }
        },
        subjectHeader = {
            if (r != null) {
                val uiState by analysis.uiState.collectAsStateWithLifecycle()
                SubjectHeader(
                    title = r.displayName(wearer?.displayName, watchName),
                    subtitle = SubjectText.recording(
                        startMs = r.metadata.startTime,
                        zone = r.clock,
                        reader = inga.bpmetrics.ui.util.ReaderClock,
                        wearer = wearer?.displayName,
                        place = place?.location?.displayName
                    ),
                    cover = cover,
                    trail = placement.trail(),
                    // "No readings" rather than Low 0 / Avg 0 / High 0 for one with nothing in it
                    // (S15.5): a zero reads as a heart that stopped, not as nothing measured.
                    lowBpm = uiState.minTrio.takeIf { uiState.hasReadings },
                    avgBpm = uiState.avgTrio.takeIf { uiState.hasReadings },
                    highBpm = uiState.maxTrio.takeIf { uiState.hasReadings },
                    // How long it ran, with the other figures for the same recording.
                    counts = when {
                        uiState.isEmpty -> null
                        !uiState.hasReadings -> "No readings"
                        else -> inga.bpmetrics.ui.analysis.shortDuration(uiState.totalActiveDurationMs)
                    },
                    onOpenAncestor = onOpenEvent,
                    tags = effectiveTags,
                    onRemoveTag = { subject.removeTag(it) },
                    onExplainTag = { Feedback.say(it) },
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
                            r?.let { saveCsv.launch(inga.bpmetrics.export.FileNames.of(it.metadata.title) + ".csv") }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Split…") },
                        leadingIcon = { Icon(Icons.Default.CallSplit, contentDescription = null) },
                        onClick = { menuOpen = false; splitting = null to null }
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
    if (r != null) {
        CoverEditing(
            viewModel = subject,
            ownCover = r.metadata.ownCover,
            title = "Frame this recording",
            previewName = null,
            framing = framingCover,
            onFraming = { framingCover = it }
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
            onConfirm = { title, description, deviceId, personId, locationId, startTime ->
                subject.updateTitle(title)
                subject.updateDescription(description)
                subject.updateDeviceAndWearer(deviceId, personId)
                subject.setLocation(locationId)
                subject.moveStartTo(startTime)
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

    splitting?.let { (openFrom, openTo) ->
        if (r != null) {
            SplitRecordDialog(
                record = r,
                initialFromMs = openFrom,
                initialToMs = openTo,
                onDismiss = { splitting = null },
                onSplit = { fromMs, toMs ->
                    splitting = null
                    subject.splitRecord(fromMs, toMs, "${r.metadata.title} (Split)") { newId ->
                        if (newId == null) {
                            Feedback.failed("Nothing was recorded in that range.")
                        } else {
                            Feedback.created("Split into a new recording.") {
                                navController.navigate("${inga.bpmetrics.ui.Routes.DETAIL}/$newId")
                            }
                        }
                    }
                }
            )
        }
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
