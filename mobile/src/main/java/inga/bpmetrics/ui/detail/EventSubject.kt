package inga.bpmetrics.ui.detail

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.TimeSpan
import inga.bpmetrics.library.displayName

/**
 * Opens the event editor, wired to everything it needs.
 *
 * Takes the library's ViewModel rather than growing a second copy of this plumbing: name, type,
 * window, the people a window applies to, the venue and the clock it implies are all already
 * resolved there, and two implementations of "edit an event" is exactly the duplication this
 * initiative keeps unpicking. Shared by the library's inline edit and the detail page.
 *
 * **Tags and Delete are rendered here, not passed in.** They were optional slots, which is a way of
 * being one component and two modals: the library filled the cover slot and the event's page filled
 * the other three, so editing the same event from two places offered different things and only one
 * of them could delete it. A slot is right for something a caller can genuinely do better, and
 * neither of these is — the tag picker and the delete confirmation want exactly the same data
 * wherever they are opened from. Two slots remain, and both earn it: the cover, because the event's
 * page edits it from the header where the picture actually is, and What's included, because only a
 * screen with an analysis behind it can offer to refine one.
 *
 * Stays open when a window is refused, so the message lands beside the dates that caused it rather
 * than after the dialog has gone.
 */
@Composable
fun EventEditorLauncher(
    libraryViewModel: inga.bpmetrics.ui.library.LibraryViewModel,
    event: EventEntity,
    /** The span of what it already holds, so switching a window on starts from the truth. */
    span: TimeSpan?,
    /** How many recordings are in its subtree, so the delete confirmation says what is at stake. */
    recordCount: Int = 0,
    /** See [inga.bpmetrics.ui.library.EventEditorDialog]. */
    coverEditor: (@Composable () -> Unit)? = null,
    /** See [inga.bpmetrics.ui.library.EventEditorDialog]. Null where there is no analysis behind it. */
    excludedCount: Int = 0,
    onRefineScope: (() -> Unit)? = null,
    /** Where to go once it is gone. The library stays put; its own page has to leave. */
    onDeleted: () -> Unit = {},
    onDismiss: () -> Unit
) {
    // Its own tags, not the inherited ones: an inherited tag cannot be removed from here, so
    // offering it pre-ticked would make unticking it look broken.
    val ownTags by remember(event.eventId) {
        libraryViewModel.ownTagsOfEvent(event.eventId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by libraryViewModel.categories.collectAsStateWithLifecycle()

    var deleting by remember { mutableStateOf(false) }

    if (deleting) {
        inga.bpmetrics.ui.components.DeleteConfirmDialog(
            title = "Delete ${event.displayName}?",
            message = if (recordCount == 0) "This event has no recordings in it." else
                "Its $recordCount recording${if (recordCount == 1) "" else "s"} will be kept and " +
                    "move back to Unfiled. Only the event is deleted.",
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                libraryViewModel.deleteEvent(event.eventId)
                onDismiss()
                onDeleted()
            }
        )
    }

    val knownTypes by libraryViewModel.eventTypesInUse.collectAsStateWithLifecycle()
    val windowError by libraryViewModel.windowError.collectAsStateWithLifecycle()
    val locations by libraryViewModel.locations.collectAsStateWithLifecycle()
    val people by libraryViewModel.availablePeople.collectAsStateWithLifecycle()
    val windowPeople by remember(event.eventId) {
        libraryViewModel.windowPeople(event.eventId)
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val inherited by remember(event.eventId) {
        libraryViewModel.inheritedLocationName(event.eventId, null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val zone by remember(event.eventId) {
        libraryViewModel.windowZone(event.eventId)
    }.collectAsStateWithLifecycle(initialValue = java.util.TimeZone.getDefault())

    inga.bpmetrics.ui.library.EventEditorDialog(
        initialName = event.name,
        initialType = event.type,
        initialStart = event.windowStart,
        initialEnd = event.windowEnd,
        initialPeople = windowPeople,
        initialNotes = event.notes,
        suggestedStart = span?.startMs,
        suggestedEnd = span?.endMs,
        initialLocationId = event.locationId,
        locations = locations,
        inheritedLocationName = inherited,
        zone = zone,
        knownTypes = knownTypes,
        people = people,
        collisionError = windowError,
        initialTagIds = ownTags.map { it.tagId },
        tagPicker = { selected, onCancel, onDone ->
            inga.bpmetrics.ui.tags.TagSelectionDialog(
                onDismiss = onCancel,
                onSave = onDone,
                categories = categories,
                getTagsByCategoryFlow = { libraryViewModel.tagsInCategory(it) },
                onCreateTag = { axis, name, onMade -> libraryViewModel.createTag(axis, name, onMade) },
                initialSelectedTagIds = selected
            )
        },
        coverEditor = coverEditor,
        excludedCount = excludedCount,
        onRefineScope = onRefineScope,
        onDelete = { deleting = true },
        onDismiss = {
            libraryViewModel.clearWindowError()
            onDismiss()
        },
        onConfirm = { edit ->
            libraryViewModel.applyEventEdit(event.eventId, edit) { done -> if (done) onDismiss() }
        }
    )
}
