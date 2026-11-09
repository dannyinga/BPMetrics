package inga.bpmetrics.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import inga.bpmetrics.library.LocationEntity

/**
 * The one editor for a recording, an event or a collection: a title, the fields, and Save and
 * Cancel.
 *
 * Shared because the three were three dialogs that each answered the same questions differently:
 * where the name goes, where notes go, how tags are reached, how a venue is chosen, where Delete
 * lives. Each kind still adds its own section (an event's type and window, a collection's rule and
 * pin, a recording's wearer and start) as [content].
 *
 * **Laid out against the window's insets, not inside an AlertDialog.** An AlertDialog's buttons are
 * part of its scrolling content's frame, and on Android 15 and later, edge to edge, the keyboard
 * covered them: typing a name left Save underneath it. Here the body scrolls between a fixed title
 * and a fixed row of actions, and the whole sheet stays between the status bar and the keyboard.
 *
 * **Nothing is applied until Save.** Every editor built on this keeps what it changes as a draft,
 * so Cancel and Back leave the item exactly as it was. That includes the things reached through a
 * door from here (tags, a rule), which used to save the moment their own dialog closed.
 *
 * @param problem What stops Save, said at the top where it's seen whatever the scroll. The fields
 * it's about say it again beside themselves.
 * @param onDelete Deleting the item, set apart at the bottom, furthest from Save. Null where there
 * is nothing to delete yet.
 */
@Composable
fun ItemEditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = "Save",
    confirmEnabled: Boolean = true,
    problem: String? = null,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "Delete",
    content: @Composable ColumnScope.() -> Unit
) {
    val scroll = rememberScrollState()
    // A problem appearing after Save is tapped is the answer to that tap; scrolled down to the
    // window, it would otherwise appear where nobody is looking.
    LaunchedEffect(problem) { if (problem != null) scroll.animateScrollTo(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Outside the sheet still dismisses, as the platform dialog did.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
            ) {
                Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(Modifier.height(16.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(scroll)
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        problem?.let { ProblemBanner(it) }
                        content()
                        onDelete?.let { delete ->
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            TextButton(
                                onClick = delete,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(deleteLabel)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 16.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
                    }
                }
            }
        }
    }
}

/** What stops Save, at the top of the editor. */
@Composable
private fun ProblemBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A small heading over one part of an editor. */
@Composable
fun EditorHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/** A line of explanation under a field or heading. */
@Composable
fun EditorHint(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * What the item is called. A recording calls it a title, so the label is the caller's.
 *
 * @param error Said under the field, in the field's own error state.
 */
@Composable
fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Name",
    error: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Free text about the item. Every kind has had notes in the database; events and collections had
 * no way to write them.
 */
@Composable
fun NotesField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Notes") },
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * The way through to the item's tags, with how many it has.
 *
 * A door rather than the picker itself: choosing tags is its own dialog with its own categories.
 * What's chosen there comes back to the editor as a draft and is saved with everything else.
 */
@Composable
fun TagsButton(count: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Default.Sell, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (count == 0) "Tags" else "$count tag" + if (count == 1) "" else "s")
    }
}

/**
 * A labeled value that opens a choice: the event an event sits in, a start time, a venue.
 *
 * One shape for every field that is chosen rather than typed, so they read as fields in a column
 * instead of a mixture of buttons, chips and menus.
 */
@Composable
fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    error: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            border = if (error != null) {
                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.outlinedButtonBorder(enabled = true)
            }
        ) {
            Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Spacer(Modifier.width(8.dp))
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.invoke()
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Where the item happened, chosen from the registry.
 *
 * The one venue control. The event editor opened a second dialog for this and the recording editor
 * laid every venue out as chips, so the same choice looked different depending on what was being
 * edited. A menu off the field: one tap, no dialog over the dialog, and room for each venue's clock.
 *
 * @param inheritedName The venue it gets from above when none is set, offered by name so
 * inheriting is visibly one of the choices rather than the absence of one.
 */
@Composable
fun LocationField(
    locations: List<LocationEntity>,
    selectedId: Long?,
    inheritedName: String?,
    onSelect: (Long?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val chosen = locations.firstOrNull { it.locationId == selectedId }
    Box {
        PickerField(
            label = "Where",
            value = chosen?.displayName
                // Says what it will inherit rather than "None", so nobody sets a venue on a set
                // that already has the right one from its festival.
                ?: inheritedName?.let { "$it (inherited)" }
                ?: "Not set",
            onClick = { open = true },
            trailing = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(inheritedName?.let { "Inherit — $it" } ?: "Not set") },
                onClick = { onSelect(null); open = false }
            )
            locations.forEach { place ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(place.displayName)
                            place.timeZoneId?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = { onSelect(place.locationId); open = false }
                )
            }
            if (locations.isEmpty()) {
                Text(
                    "No venues yet. Make one under Locations in the menu, and recordings there " +
                        "read in its clock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
