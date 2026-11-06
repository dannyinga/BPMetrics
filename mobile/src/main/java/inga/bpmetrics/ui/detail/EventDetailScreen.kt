package inga.bpmetrics.ui.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import inga.bpmetrics.library.ItemRef
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.ScopeRef
import inga.bpmetrics.ui.analysis.AnalysisScreen
import inga.bpmetrics.ui.analysis.AnalysisViewModel
import inga.bpmetrics.ui.analysis.endedAt
import inga.bpmetrics.ui.analysis.shortDuration
import inga.bpmetrics.ui.feedback.Feedback
import inga.bpmetrics.ui.util.ReaderClock

/**
 * An event, and its analysis.
 *
 * Two view models, split by what they are about: [ItemDetailViewModel] owns the event (its name,
 * cover, tags and place in the tree) and [AnalysisViewModel] owns the scope, this event and
 * everything beneath it. The header's figures come from the analysis, so once people are filtered
 * or parts left out, the counts and length say what the numbers beside them describe (S15.4).
 */
@Composable
fun EventDetailScreen(
    navController: NavController,
    repository: LibraryRepository,
    /** The app-wide library view model, which owns event editing until S16. */
    libraryViewModel: inga.bpmetrics.ui.library.LibraryViewModel,
    eventId: Long,
    onBack: () -> Unit,
    onExport: () -> Unit
) {
    val subject: ItemDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        // Keyed on the event, or walking from one to another through the trail would reuse the
        // first one's view model and show the wrong subject over the right numbers.
        key = "event-subject-$eventId",
        factory = ItemDetailViewModel.Factory(repository, ItemRef.Event(eventId))
    )
    val analysis: AnalysisViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "event-analysis-$eventId",
        factory = AnalysisViewModel.forScope(repository, ScopeRef.Event(eventId))
    )

    val state by subject.subject.collectAsStateWithLifecycle()
    val tags by subject.tags.collectAsStateWithLifecycle()
    val places by repository.getAllLocations().collectAsStateWithLifecycle(initialValue = emptyList())

    var framingCover by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var addingInside by remember { mutableStateOf(false) }

    val event = state.event

    AnalysisScreen(
        navController = navController,
        viewModel = analysis,
        onOpenDrawer = {},
        onBack = onBack,
        onExport = onExport,
        onOpenEvent = { id -> navController.navigate("${inga.bpmetrics.ui.Routes.EVENT_DETAIL}/$id") },
        subjectHeader = {
            if (event != null) {
                val uiState by analysis.uiState.collectAsStateWithLifecycle()
                SubjectHeader(
                    title = state.title,
                    // When it ran, from what is in view, so a filtered page's dates match its figures.
                    subtitle = SubjectText.event(
                        type = event.type,
                        startMs = uiState.records.takeIf { it.isNotEmpty() }?.minOf { it.date } ?: state.span?.startMs,
                        endMs = uiState.records.takeIf { it.isNotEmpty() }?.maxOf { it.endedAt }
                            ?: state.span?.endMs,
                        zone = state.clock,
                        reader = ReaderClock,
                        place = state.placeName
                    ),
                    cover = state.cover,
                    trail = state.trail,
                    lowBpm = uiState.minTrio.takeIf { uiState.hasReadings },
                    avgBpm = uiState.avgTrio.takeIf { uiState.hasReadings },
                    highBpm = uiState.maxTrio.takeIf { uiState.hasReadings },
                    counts = if (uiState.isEmpty) null else SubjectText.counts(
                        recordings = uiState.recordCount,
                        people = uiState.people.size,
                        // Its length, first start to last end, as the Timeline tab shows it. Not
                        // the time measured summed across everyone, which read like a length.
                        length = "${shortDuration(uiState.lengthMs)} long"
                    ),
                    onOpenAncestor = { id ->
                        navController.navigate("${inga.bpmetrics.ui.Routes.EVENT_DETAIL}/$id")
                    },
                    tags = tags,
                    onRemoveTag = { subject.removeTag(it) },
                    onExplainTag = { Feedback.say(it) },
                    onEditCover = { framingCover = true }
                )
            }
        },
        subjectActions = {
            // Making a set inside this day, from the day.
            IconButton(onClick = { addingInside = true }) {
                Icon(Icons.Default.Add, contentDescription = "New event inside this one")
            }
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit event")
            }
        },
        // What is inside this event, as its own tab, only when something is.
        subjectChildren = if (event == null || !state.hasChildEvents) null else {
            {
                EventChildren(
                    parentId = event.eventId,
                    libraryViewModel = libraryViewModel,
                    onOpen = { id ->
                        navController.navigate("${inga.bpmetrics.ui.Routes.EVENT_DETAIL}/$id")
                    }
                )
            }
        },
        // The editor, rendered by the analysis so it can offer "What's included".
        subjectEditor = { openRefineScope ->
            if (editing && event != null) {
                val exclusions by analysis.exclusions.collectAsStateWithLifecycle()
                EventEditorLauncher(
                    libraryViewModel = libraryViewModel,
                    event = event,
                    span = state.span,
                    recordCount = state.recordCount,
                    excludedCount = exclusions.leftOutCount,
                    // Closes the editor first, so two dialogs aren't stacked.
                    onRefineScope = { editing = false; openRefineScope() },
                    // Deleting an event from its own page has to leave the page.
                    onDeleted = onBack,
                    onDismiss = { editing = false }
                )
            }
        }
    )

    if (event != null) {
        CoverEditing(
            viewModel = subject,
            ownCover = state.ownCover,
            title = "Frame ${state.title}",
            previewName = state.title,
            framing = framingCover,
            onFraming = { framingCover = it }
        )
    }

    // The editor the timeline creates with, opened with this event already chosen as the parent.
    if (addingInside && event != null) {
        val knownTypes by libraryViewModel.eventTypesInUse.collectAsStateWithLifecycle()
        val windowError by libraryViewModel.windowError.collectAsStateWithLifecycle()
        val parentRows by libraryViewModel.eventPickerRows.collectAsStateWithLifecycle()

        inga.bpmetrics.ui.library.EventEditorDialog(
            initialName = "",
            initialParentId = event.eventId,
            knownTypes = knownTypes,
            parentOptions = parentRows,
            locations = places,
            // Its parent's window, as the suggestion.
            suggestedStart = state.span?.startMs,
            suggestedEnd = state.span?.endMs,
            collisionError = windowError,
            onDismiss = { addingInside = false; libraryViewModel.clearWindowError() },
            onConfirm = { edit ->
                libraryViewModel.createEvent(edit) { done -> if (done) addingInside = false }
            }
        )
    }
}
