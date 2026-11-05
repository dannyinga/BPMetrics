package inga.bpmetrics.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Everything you can change about several recordings at once.
 *
 * The selection menu had thirteen items in it. Most were not *actions on a selection* at all — they
 * were edits to the recordings in it, each having earned a row of its own as it was added, so the
 * one list held tagging, attribution, filing, covers, four kinds of export, merging, analysing and
 * deleting, in no particular order.
 *
 * The edits belong together and behind one door. What is left in the menu is what is not an edit:
 * joining recordings into one, getting them out of the app, and destroying them.
 *
 * A hub rather than a form: each row opens the picker that already exists for it, which is the same
 * shape the detail page's editors use for tags. Building a combined form would mean a second way of
 * choosing a person, a second way of choosing tags, and two of each to keep in agreement.
 */
@Composable
fun BulkEditDialog(
    /** Events picked as events. Each one counts once, however much is inside it. */
    eventCount: Int,
    /** Recordings picked one by one. */
    recordCount: Int,
    /** Every recording the selection covers, including the ones inside picked events. */
    coveredCount: Int,
    /** Whether the selection currently has a cover to remove. */
    hasCover: Boolean,
    onTags: () -> Unit,
    onAttribute: () -> Unit,
    onFileIntoEvent: () -> Unit,
    onAddToCollection: () -> Unit,
    onCorrectTime: () -> Unit,
    onSetCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Both kinds, counted as picked. This said "Edit 0 recordings" over a selection of events.
        title = { Text("Edit ${selectionLabel(eventCount, recordCount)}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (eventCount > 0) {
                    Text(
                        "${countLabel(coveredCount, "recording")} in all, counting what's inside " +
                            if (eventCount == 1) "the event." else "the events.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                Row("Tags…", Icons.Default.Sell, onTags)
                Row("Attribute to…", Icons.Default.People, onAttribute)
                Row("File into an event…", Icons.Default.Folder, onFileIntoEvent)
                Row("Add to a collection…", Icons.Default.Bookmarks, onAddToCollection)
                // Only for recordings picked one by one. An event has its own times, and moving
                // the recordings inside it would leave them outside it.
                if (recordCount > 0) {
                    Row(
                        if (eventCount > 0) "Correct the time of the ${countLabel(recordCount, "recording")}…"
                        else "Correct the time…",
                        Icons.Default.Schedule,
                        onCorrectTime
                    )
                }

                // A cover set from a multi-selection goes on the *event* those recordings share,
                // not on each one — see LibraryViewModel.setCoverForSelection. It refuses rather
                // than guesses when the selection spans several events.
                Row(
                    if (hasCover) "Change photo…" else "Set photo…",
                    Icons.Default.Image,
                    onSetCover
                )
                if (hasCover) {
                    Row("Remove photo", Icons.Default.Image, onRemoveCover)
                }

                Text(
                    if (eventCount > 0) {
                        "Tags and photos go on the events themselves, so recordings arriving in " +
                            "them later get them too."
                    } else {
                        "A photo set here goes on the event these recordings share, so one " +
                            "arriving late from the same night inherits it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** "2 events and 3 recordings", "2 events", "3 recordings". */
fun selectionLabel(eventCount: Int, recordCount: Int): String = when {
    eventCount == 0 -> countLabel(recordCount, "recording")
    recordCount == 0 -> countLabel(eventCount, "event")
    else -> "${countLabel(eventCount, "event")} and ${countLabel(recordCount, "recording")}"
}

@Composable
private fun Row(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick
    )
}

/** Why some picked events stayed where they were: the target is inside them. */
fun cantNestMessage(refused: Int): String =
    if (refused == 1) "1 event stayed where it was. It can't go inside itself."
    else "$refused events stayed where they were. An event can't go inside itself."
