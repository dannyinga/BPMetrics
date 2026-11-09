package inga.bpmetrics.ui.detail

import androidx.compose.material.icons.Icons
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
import inga.bpmetrics.ui.Routes
import inga.bpmetrics.ui.analysis.AnalysisScreen
import inga.bpmetrics.ui.analysis.AnalysisViewModel
import inga.bpmetrics.ui.analysis.LocalOpenSubjectChildren
import inga.bpmetrics.ui.analysis.shortDuration
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.feedback.Feedback
import inga.bpmetrics.ui.library.LibraryViewModel

/**
 * A collection, and its analysis.
 *
 * Its subject comes from [ItemDetailViewModel], like the other two pages. It used to borrow the
 * library's view model and search its list of every collection, built with every rule resolved,
 * for this one (S15.1). Editing still goes through the library's editor until S16.
 *
 * What a collection's header says is shorter, because a collection has less to be: no window,
 * venue or clock of its own. What it does have is the events it names, which is also the way out.
 */
@Composable
fun CollectionDetailScreen(
    navController: NavController,
    repository: LibraryRepository,
    libraryViewModel: LibraryViewModel,
    collectionId: Long,
    onBack: () -> Unit,
    onSave: (name: String, records: List<inga.bpmetrics.ui.analysis.AnalysisRecord>) -> Unit,
    onExport: () -> Unit
) {
    val subject: ItemDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "collection-subject-$collectionId",
        factory = ItemDetailViewModel.Factory(repository, ItemRef.Collection(collectionId))
    )
    val analysis: AnalysisViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "collection-$collectionId",
        factory = AnalysisViewModel.forScope(repository, ScopeRef.Collection(collectionId))
    )

    val state by subject.subject.collectAsStateWithLifecycle()
    val uiState by analysis.uiState.collectAsStateWithLifecycle()
    val set = state.collection

    // The events it names, else the events its recordings are in. A smart collection names none:
    // its rule picks recordings, and the events are wherever they were filed.
    val events = remember(state.trail, uiState.records) {
        state.trail.ifEmpty {
            uiState.records
                .mapNotNull { r -> r.eventId?.let { it to r.eventName.ifBlank { r.eventLabel } } }
                .distinctBy { it.first }
        }
    }

    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var framingCover by remember { mutableStateOf(false) }

    AnalysisScreen(
        navController = navController,
        viewModel = analysis,
        onOpenDrawer = {},
        onBack = onBack,
        onSave = onSave,
        onExport = onExport,
        onOpenEvent = { navController.navigate("${Routes.EVENT_DETAIL}/$it") },
        subjectHeader = {
            if (set != null) {
                SubjectHeader(
                    title = state.title,
                    subtitle = SubjectText.collection(isFrozen = set.isFrozen, isSmart = set.isSmart),
                    cover = state.cover,
                    // The events it names, which is also the way into them: alphabetical, so a
                    // name can be looked for, and only the first few. The whole list is the
                    // Events tab, which "and N more" opens.
                    trail = events.sortedBy { it.second.lowercase() },
                    trailShown = HEADER_EVENTS,
                    onShowWholeTrail = LocalOpenSubjectChildren.current,
                    lowBpm = uiState.minTrio.takeIf { uiState.hasReadings },
                    avgBpm = uiState.avgTrio.takeIf { uiState.hasReadings },
                    highBpm = uiState.maxTrio.takeIf { uiState.hasReadings },
                    counts = if (uiState.isEmpty) null else SubjectText.counts(
                        recordings = uiState.recordCount,
                        people = uiState.people.size,
                        events = events.size,
                        // Time recorded, not a length: a collection can span months of unrelated
                        // nights, and "8 months long" would describe the gaps.
                        length = "${shortDuration(uiState.totalActiveDurationMs)} recorded"
                    ),
                    onOpenAncestor = { navController.navigate("${Routes.EVENT_DETAIL}/$it") },
                    trailIsPath = false,
                    onEditCover = { framingCover = true }
                )
            }
        },
        subjectActions = {
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit collection")
            }
        },
        // The events it holds, as the same cards an event lists what is inside it with.
        subjectChildren = if (events.isEmpty()) null else {
            {
                CollectionEvents(
                    collectionId = collectionId,
                    eventIds = events.map { it.first },
                    libraryViewModel = libraryViewModel,
                    onOpen = { navController.navigate("${Routes.EVENT_DETAIL}/$it") }
                )
            }
        }
    )

    if (set != null) {
        CoverEditing(
            viewModel = subject,
            ownCover = state.ownCover,
            title = "Frame ${state.title}",
            previewName = state.title,
            framing = framingCover,
            onFraming = { framingCover = it }
        )
    }

    if (editing && set != null) {
        val filterOptions by libraryViewModel.filterOptions.collectAsStateWithLifecycle()
        inga.bpmetrics.ui.library.CollectionEditorDialog(
            collection = set,
            recordCount = uiState.recordCount,
            filterOptions = filterOptions,
            chipsOf = { libraryViewModel.chipsOf(it, filterOptions) },
            onDelete = { editing = false; deleting = true },
            onDismiss = { editing = false },
            onSave = { edit ->
                libraryViewModel.applyCollectionEdit(collectionId, edit) { message -> Feedback.say(message) }
                editing = false
            }
        )
    }

    if (deleting && set != null) {
        val count = uiState.recordCount
        DeleteConfirmDialog(
            title = "Delete ${state.title}?",
            // The one delete in the app that removes no data, and people reasonably assume otherwise.
            message = "This removes the grouping only. Its $count recording" +
                "${if (count == 1) "" else "s"} stay exactly where they are on the timeline.",
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                libraryViewModel.deleteCollection(collectionId)
                onBack()
            }
        )
    }
}

/** How many of a collection's events its header names before "and N more". */
private const val HEADER_EVENTS = 3
