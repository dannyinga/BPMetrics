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
    count: Int,
    /** Whether the selection currently has a cover to remove. */
    hasCover: Boolean,
    onTags: () -> Unit,
    onAttribute: () -> Unit,
    onFileIntoEvent: () -> Unit,
    onAddToCollection: () -> Unit,
    onSetCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit $count recording${if (count == 1) "" else "s"}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Row("Tags…", Icons.Default.Sell, onTags)
                Row("Attribute to…", Icons.Default.People, onAttribute)
                Row("File into an event…", Icons.Default.Folder, onFileIntoEvent)
                Row("Add to a collection…", Icons.Default.Bookmarks, onAddToCollection)

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
                    "A photo set here goes on the event these recordings share, so one arriving " +
                        "late from the same night inherits it.",
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
