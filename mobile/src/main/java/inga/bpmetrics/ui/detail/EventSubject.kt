package inga.bpmetrics.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.TimeSpan
import inga.bpmetrics.library.displayName
import inga.bpmetrics.ui.analysis.shortDuration
import inga.bpmetrics.ui.tags.EffectiveTagChip
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import java.time.ZoneId

/**
 * What an event is, above the analysis of it.
 *
 * Deliberately compact. Everything below it — the curves, the split, the rankings, the readings —
 * is the same component every other subject gets, and a header that pushed it off the screen would
 * make the page about the label rather than the thing. So this is a name, a line saying when and
 * where, its tags, and the way up the tree; editing is an action in the app bar, opening the editor
 * that already exists.
 *
 * The counts come from the subtree, matching what the analysis below actually covers. A festival
 * whose recordings all sit in its days would otherwise report nothing while the numbers under it
 * described forty.
 */
@Composable
fun EventSubjectHeader(
    event: EventEntity,
    /** Where it sits, innermost last. Tapping a step walks up. */
    ancestry: List<EventEntity>,
    recordCount: Int,
    personCount: Int,
    /** What it actually covers, which is not always what its window says. */
    span: TimeSpan?,
    placeName: String?,
    tags: List<EffectiveTag>,
    clock: ZoneId,
    onOpenAncestor: (Long) -> Unit,
    onRemoveTag: (Long) -> Unit,
    onExplainTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {

        // The way up, before the name — a set inside a day inside a festival is somewhere, and a
        // page that does not say where is a dead end however good its numbers are.
        if (ancestry.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ancestry.forEachIndexed { index, step ->
                    if (index > 0) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        step.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onOpenAncestor(step.eventId) }
                            .padding(vertical = 2.dp, horizontal = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                event.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            event.type?.takeIf { it.isNotBlank() }?.let { type ->
                Spacer(Modifier.size(8.dp))
                Text(
                    type,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // One line, in the event's own clock. A set at the Gorge reads in Pacific time whoever is
        // looking at it, which is the whole point of §2.7.
        Text(
            buildString {
                span?.let {
                    append(getDateString(it.startMs, clock))
                    append(" · ")
                    append(getTimeString(it.startMs, clock))
                    append("–")
                    append(getTimeString(it.endMs, clock))
                    append(" · ")
                    append(shortDuration(it.endMs - it.startMs))
                } ?: append("Nothing in it yet")
                placeName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            buildString {
                append("$recordCount recording${if (recordCount == 1) "" else "s"}")
                if (personCount > 0) {
                    append(" · $personCount ${if (personCount == 1) "person" else "people"}")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Tagging here reaches every recording underneath without writing to any of them — the tag
        // is on the event, and inheritance is resolved at read time.
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            inga.bpmetrics.ui.components.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEach { effective ->
                    EffectiveTagChip(
                        effective = effective,
                        onRemove = { onRemoveTag(effective.tag.tagId) },
                        onExplain = onExplainTag
                    )
                }
            }
        }
    }
}

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

    var tagging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    if (tagging) {
        inga.bpmetrics.ui.tags.TagSelectionDialog(
            onDismiss = { tagging = false },
            onSave = { selected ->
                libraryViewModel.setEventTags(event.eventId, selected)
                tagging = false
            },
            categories = categories,
            getTagsByCategoryFlow = { libraryViewModel.tagsInCategory(it) },
            onCreateTag = { axis, name, onMade -> libraryViewModel.createTag(axis, name, onMade) },
            initialSelectedTagIds = ownTags.map { it.tagId }
        )
    }

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
        suggestedStart = span?.startMs,
        suggestedEnd = span?.endMs,
        initialLocationId = event.locationId,
        locations = locations,
        inheritedLocationName = inherited,
        zone = zone,
        knownTypes = knownTypes,
        people = people,
        collisionError = windowError,
        tagCount = ownTags.size,
        onEditTags = { tagging = true },
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
