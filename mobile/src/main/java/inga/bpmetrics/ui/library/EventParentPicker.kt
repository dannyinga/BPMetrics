package inga.bpmetrics.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.EventTree

/**
 * Chooses which event this one sits inside.
 *
 * Nesting is `parentId` and has been since the fold. This replaced a picker that offered
 * *collections* — a tier type that no longer exists — so after collections became sets it listed
 * nothing at all and there was no way to nest an event through the UI.
 *
 * The whole tree is offered, at the depth each entry sits, because anything can contain anything:
 * a festival holds days, a day holds sets, and a set can perfectly well hold another. What is *not*
 * offered is the event itself or anything beneath it — that would make it its own ancestor, and a
 * cycle does not throw, it hangs every walk of the tree. The repository refuses it too; this is so
 * the option is never presented.
 */
@Composable
fun EventParentPickerDialog(
    /** The event being moved. */
    moving: EventEntity,
    /** The whole tree in reading order with depth, as [LibraryViewModel.eventPickerRows] gives it. */
    rows: List<Pair<EventEntity, Int>>,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
    onCreateEvent: () -> Unit
) {
    // Self and descendants, from the one walk. Working this out by hand here would be a second
    // definition of "inside", which is the habit this whole rework exists to break.
    val forbidden = EventTree.descendantsOf(rows.map { it.first }, moving.eventId)
    val candidates = rows.filterNot { (event, _) -> event.eventId in forbidden }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${moving.displayName} into…") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                DropdownMenuItem(
                    text = { Text("Nothing — keep it at the top") },
                    trailingIcon = {
                        if (moving.parentId == null) {
                            Icon(Icons.Default.Check, contentDescription = "Current")
                        }
                    },
                    onClick = { onPick(null) }
                )

                HorizontalDivider()

                if (candidates.isEmpty()) {
                    Text(
                        "There is nowhere else to put this yet. Make another event and this one " +
                            "can go inside it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }

                candidates.forEach { (event, depth) ->
                    val current = moving.parentId == event.eventId
                    DropdownMenuItem(
                        modifier = Modifier.padding(start = (depth.coerceAtLeast(0) * 14).dp),
                        text = {
                            Column {
                                Text(
                                    event.displayName,
                                    fontWeight = if (current) FontWeight.Bold else null
                                )
                                event.type?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        trailingIcon = {
                            if (current) Icon(Icons.Default.Check, contentDescription = "Current")
                        },
                        onClick = { onPick(event.eventId) }
                    )
                }

                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("New event…") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = onCreateEvent
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * What to do with the events that are picked out.
 *
 * Only actions that make sense for several events at once. Editing is absent on purpose — a name, a
 * type and a window are each specific to one event, and a bulk editor would either apply one
 * event's window to all of them or present four blank fields that mean nothing.
 */
@Composable
fun EventSelectionBar(
    count: Int,
    onMove: () -> Unit,
    onAddToCollection: () -> Unit,
    onClear: () -> Unit
) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = onClear) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear selection"
                )
            }
            Text(
                "$count event${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onMove) { Text("Move into…") }
            TextButton(onClick = onAddToCollection) { Text("Collection…") }
        }
    }
}
