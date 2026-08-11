package inga.bpmetrics.ui.detail

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import inga.bpmetrics.library.EventTree
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.ScopeRef
import inga.bpmetrics.library.displayName
import inga.bpmetrics.ui.analysis.AnalysisScreen
import inga.bpmetrics.ui.analysis.AnalysisViewModel
import inga.bpmetrics.ui.analysis.EventDetailViewModel
import inga.bpmetrics.ui.analysis.shortDuration
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.tags.EffectiveTagChip
import inga.bpmetrics.ui.tags.TagSelectionDialog
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import inga.bpmetrics.ui.util.ReaderClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * An event, and its analysis.
 *
 * The first subject to fold. Two ViewModels, split by what they are *about*: the subject one owns
 * the event — its name, its tags, its place in the tree, and everything you can do to it — and
 * [AnalysisViewModel] owns the scope, which is this event and everything beneath it.
 *
 * That split is why the fold works at all. The analysis half was never event-specific; it was one
 * component that had been copied to sit under each subject as each subject was added. Sprint 5
 * points the copy that survived at a [ScopeRef] instead.
 */
@Composable
fun EventDetailScreen(
    navController: NavController,
    repository: LibraryRepository,
    /** The app-wide library ViewModel, which already owns the event-editing plumbing. */
    libraryViewModel: inga.bpmetrics.ui.library.LibraryViewModel,
    eventId: Long,
    onBack: () -> Unit,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val subject: EventDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        // Keyed on the event, or walking from one to another through the breadcrumb would reuse
        // the first one's ViewModel and show the wrong subject over the right numbers.
        key = "event-subject-$eventId",
        factory = EventDetailViewModel.Factory(repository, eventId)
    )
    val analysis: AnalysisViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "event-analysis-$eventId",
        factory = AnalysisViewModel.forScope(repository, ScopeRef.Event(eventId))
    )

    val state by subject.state.collectAsStateWithLifecycle()
    val tags by subject.tags.collectAsStateWithLifecycle(initialValue = emptyList())
    val tree by repository.allEventsInTree.collectAsStateWithLifecycle(initialValue = emptyList())
    val places by repository.getAllLocations().collectAsStateWithLifecycle(initialValue = emptyList())

    // Straight into framing once it lands, while the choice is fresh. A cover that came out
    // centred on somebody's shoulder and has to be hunted down to fix is worse than one more step
    // now — and until this existed there was no way to fix it at all.
    var framingCover by remember { mutableStateOf(false) }

    // Registered once for the screen: a launcher outlives the dialog that starts it.
    val pickCover = inga.bpmetrics.ui.components.rememberCoverPicker { uri ->
        subject.setCover(context, uri) { ok ->
            if (ok) framingCover = true
            else Toast.makeText(context, "That image could not be read", Toast.LENGTH_LONG).show()
        }
    }

    var editing by remember { mutableStateOf(false) }
    var addingInside by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val event = state.event

    // The event's own clock, so a set at the Gorge reads in Pacific time whoever is looking.
    val eventClock = remember(places, event, tree) { resolvedZone(event, tree, places) }

    // Its own cover, or whatever it inherits — the same answer the timeline card shows, so the
    // page and the card that opened it look like the same thing.
    val cover = remember(event, tree) {
        // Nearest wins up the tree, exactly as the timeline card resolves it: a set with no
        // picture of its own shows its day's, or its festival's.
        event?.let { own ->
            EventTree.ancestryOf(tree, own.eventId).firstNotNullOfOrNull { it.ownCover }
        }
    }

    val resolved = remember(event, tree, places) {
        event?.let {
            inga.bpmetrics.library.LocationResolver.forEvent(
                it.eventId, tree, places.associateBy { p -> p.locationId }
            )
        }
    }

    AnalysisScreen(
        navController = navController,
        viewModel = analysis,
        onOpenDrawer = {},
        onBack = onBack,
        // No title: the header below carries the name over the cover, and the app bar repeating it
        // was the same word twice on one screen.
        title = null,
        onExport = onExport,
        subjectHeader = {
            if (event != null) {
                val uiState by analysis.uiState.collectAsStateWithLifecycle()
                SubjectHeader(
                    title = event.displayName,
                    subtitle = buildString {
                        event.type?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
                        state.span?.let {
                            append(getDateString(it.startMs, eventClock))
                            append(" · ")
                            append(getTimeString(it.startMs, eventClock))
                            append("–")
                            append(getTimeString(it.endMs, eventClock))
                        } ?: append("Nothing in it yet")
                        resolved?.location?.displayName?.let { append(" · $it") }
                    },
                    cover = cover,
                    // Innermost last, and without the event itself — it is the title.
                    trail = EventTree.ancestryOf(tree, event.eventId)
                        .filter { it.eventId != event.eventId }
                        .reversed()
                        .map { it.eventId to it.displayName },
                    lowBpm = uiState.minTrio.takeIf { !uiState.isEmpty },
                    avgBpm = uiState.avgTrio.takeIf { !uiState.isEmpty },
                    highBpm = uiState.maxTrio.takeIf { !uiState.isEmpty },
                    counts = buildString {
                        append("${state.records.size} recording")
                        if (state.records.size != 1) append("s")
                        if (state.personCount > 0) {
                            append(" · ${state.personCount} ")
                            append(if (state.personCount == 1) "person" else "people")
                        }
                        // The one figure that used to head the zone breakdown. It is a total like
                        // the counts either side of it, not a heading for a chart.
                        if (!uiState.isEmpty) {
                            append(" · ${shortDuration(uiState.totalActiveDurationMs)}")
                        }
                    },
                    onOpenAncestor = { id ->
                        navController.navigate("${inga.bpmetrics.ui.Routes.EVENT_DETAIL}/$id")
                    },
                    tags = tags,
                    onRemoveTag = { subject.removeTag(it) },
                    onExplainTag = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
                    onEditCover = { framingCover = true }
                )
            }
        },
        subjectActions = {
            // Making a set inside this day, from the day. It used to mean going back to the
            // timeline, creating one loose, and moving it in — two trips for one thought. An icon
            // rather than a menu, because the page has no overflow any more and bringing one back
            // to hold a single item is the thing that was just removed.
            IconButton(onClick = { addingInside = true }) {
                Icon(Icons.Default.Add, contentDescription = "New event inside this one")
            }
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit event")
            }
        },
        // The editor, rendered by the analysis so it can offer "What's included" — see the slot's
        // documentation. An event is the one subject where refining means much: it is the only one
        // with a subtree to leave parts of out.
        subjectEditor = { openRefineScope ->
            if (editing && event != null) {
                val exclusions by analysis.exclusions.collectAsStateWithLifecycle()
                EventEditorLauncher(
                    libraryViewModel = libraryViewModel,
                    event = event,
                    span = state.span,
                    tagCount = tags.count { !it.isInherited },
                    onEditTags = { tagging = true },
                    excludedCount = exclusions.excludedEventIds.size +
                        exclusions.excludedRecordIds.size,
                    // Closes the editor first. Two stacked dialogs would leave the sheet's own
                    // scrim over an editor nobody can reach, and returning to a half-typed name
                    // after picking through a tree is not where anyone wants to land.
                    onRefineScope = { editing = false; openRefineScope() },
                    // At the bottom of the editor rather than in a three-dot menu holding one
                    // item — and that item the destructive one.
                    onDelete = { editing = false; deleting = true },
                    onDismiss = { editing = false }
                )
            }
        }
    )

    // Opened from the corner of the cover rather than from the editor. See [SubjectHeader].
    if (framingCover && event != null) {
        inga.bpmetrics.ui.components.CoverCropDialog(
            cover = event.ownCover,
            onPick = pickCover,
            title = "Frame ${event.displayName}",
            previewContent = {
                Text(
                    event.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            onDismiss = { framingCover = false },
            onConfirm = { subject.setCoverCrop(it); framingCover = false },
            // Stays open: removing is usually the first half of replacing.
            onRemove = { subject.clearCover(context) }
        )
    }

    // The same editor the timeline creates with, opened with this event already chosen as the
    // parent — so the only thing left to say is what the new one is.
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
            // Its parent's window, as the suggestion. A set is inside the day it happened on, so
            // the day is the right starting guess for when the set was.
            suggestedStart = state.span?.startMs,
            suggestedEnd = state.span?.endMs,
            collisionError = windowError,
            onDismiss = { addingInside = false; libraryViewModel.clearWindowError() },
            onConfirm = { edit ->
                libraryViewModel.createEvent(edit) { done -> if (done) addingInside = false }
            }
        )
    }

    if (tagging) {
        val categories by subject.categories.collectAsStateWithLifecycle(initialValue = emptyList())
        TagSelectionDialog(
            onDismiss = { tagging = false },
            onSave = { selected -> subject.setTags(selected); tagging = false },
            categories = categories,
            getTagsByCategoryFlow = { subject.tagsInCategory(it) },
            onCreateTag = { axis, name, onMade -> subject.createTag(axis, name, onMade) },
            // Only the ones applied here. An inherited tag cannot be removed on this page, so
            // offering it pre-ticked would make unticking it look broken.
            initialSelectedTagIds = tags.filterNot { it.isInherited }.map { it.tag.tagId }
        )
    }

    if (deleting && event != null) {
        DeleteConfirmDialog(
            title = "Delete ${event.displayName}?",
            message = if (state.records.isEmpty()) "This event has no recordings in it." else
                "Its ${state.records.size} recording" +
                    "${if (state.records.size == 1) "" else "s"} will be kept and move back to " +
                    "Unfiled. Only the event is deleted.",
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                scope.launch { subject.deleteEvent(onBack) }
            }
        )
    }
}

/**
 * The clock an event's times should read in.
 *
 * Through [inga.bpmetrics.library.LocationResolver], so the header agrees with the timeline card
 * and the export — the venue is inherited from the nearest ancestor that names one, and the zone
 * comes with it. Falls back to the reader's own, which is what the app did everywhere before
 * places existed.
 */
private fun resolvedZone(
    event: inga.bpmetrics.library.EventEntity?,
    tree: List<inga.bpmetrics.library.EventEntity>,
    places: List<inga.bpmetrics.library.LocationEntity>
): java.time.ZoneId {
    val id = event?.let {
        inga.bpmetrics.library.LocationResolver
            .forEvent(it.eventId, tree, places.associateBy { p -> p.locationId })
            ?.location
            ?.timeZoneId
    }
    return id?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: ReaderClock
}
