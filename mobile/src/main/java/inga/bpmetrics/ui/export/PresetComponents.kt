package inga.bpmetrics.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.ExportPresetEntity

/**
 * The saved look in use, and the way to change it, in one line above the preview.
 *
 * Saved looks used to be a tab beside the settings, but choosing one isn't a setting: it replaces
 * all of them. So the name of the look sits where the whole editor can see it, and opens a list;
 * everything that changes a saved look is behind the overflow, because the common action is "use
 * this one" and the rare ones shouldn't compete with it.
 *
 * @param selectedId Which look the current settings started from. It stays named through edits,
 *   marked "edited", so Update is there when there's something to update with.
 * @param edited Whether the settings hold changes no saved look has. Choosing a look then asks
 *   first, because it replaces them (Audit #21).
 */
@Composable
fun LookPicker(
    presets: List<ExportPresetEntity>,
    selectedId: Long?,
    edited: Boolean,
    onApply: (ExportPresetEntity) -> Unit,
    onSaveAs: (String) -> Unit,
    onUpdate: (ExportPresetEntity) -> Unit,
    onDelete: (ExportPresetEntity) -> Unit,
    onExportFile: (ExportPresetEntity) -> Unit,
    onImportFile: () -> Unit,
    /** Shown between the look's name and the overflow: which clips a change reaches. */
    scope: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ExportPresetEntity?>(null) }
    var confirmApply by remember { mutableStateOf<ExportPresetEntity?>(null) }
    val selected = presets.firstOrNull { it.presetId == selectedId }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Look",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f)) {
            TextButton(
                onClick = { listOpen = true },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    selected?.name ?: "Unsaved",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (edited && selected != null) {
                    Text(
                        " · edited",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose a saved look")
            }
            DropdownMenu(expanded = listOpen, onDismissRequest = { listOpen = false }) {
                // Yours first: they're the ones made for how you film.
                val (builtIn, yours) = presets.partition { it.isBuiltIn }
                listOf("Yours" to yours, "Built in" to builtIn).forEach { (heading, group) ->
                    if (group.isEmpty()) return@forEach
                    Text(
                        heading,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    group.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            leadingIcon = {
                                if (preset.presetId == selectedId) Icon(Icons.Default.Check, contentDescription = "In use")
                                else Spacer(Modifier.size(24.dp))
                            },
                            onClick = {
                                listOpen = false
                                if (edited) confirmApply = preset else onApply(preset)
                            }
                        )
                    }
                }
                HorizontalDivider()
                // Said here because nothing else does: there is no "default" to set any more.
                Text(
                    "A new export starts from the look you used last.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 260.dp)
                )
            }
        }
        scope?.invoke()
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Saved look options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Only when there's something to update with, and never a built-in: editing one
                // saves a new look instead.
                if (selected != null && edited && !selected.isBuiltIn) {
                    DropdownMenuItem(
                        text = { Text("Update ${selected.name}") },
                        onClick = { menuOpen = false; onUpdate(selected) }
                    )
                }
                // Unsaved changes carry over from the last export, so "edited" can be there before
                // anything was touched today. This is the way back to the saved look.
                if (selected != null && edited) {
                    DropdownMenuItem(
                        text = { Text("Revert to ${selected.name}") },
                        onClick = { menuOpen = false; onApply(selected) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Save as a new look…") },
                    onClick = { menuOpen = false; showSaveDialog = true }
                )
                DropdownMenuItem(
                    text = { Text("Import from a file") },
                    onClick = { menuOpen = false; onImportFile() }
                )
                if (selected != null) {
                    DropdownMenuItem(
                        text = { Text("Export ${selected.name} to a file") },
                        onClick = { menuOpen = false; onExportFile(selected) }
                    )
                }
                if (selected != null && !selected.isBuiltIn) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete ${selected.name}", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; confirmDelete = selected }
                    )
                }
            }
        }
    }

    confirmApply?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirmApply = null },
            title = { Text("Use ${preset.name}?") },
            text = {
                Text(
                    if (selected != null) {
                        "Your changes to ${selected.name} aren't saved, and ${preset.name} replaces them."
                    } else {
                        "These settings aren't saved as a look, and ${preset.name} replaces them."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { onApply(preset); confirmApply = null }) {
                    Text("Use ${preset.name}")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmApply = null }) { Text("Keep my changes") }
            }
        )
    }

    if (showSaveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save as a new look") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        placeholder = { Text("Story 9:16") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Saves how the export looks — canvas, graph, colors. Never which " +
                            "recordings or what time range, so it stays usable on anything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { onSaveAs(name.trim()); showSaveDialog = false }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }

    confirmDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${preset.name}?") },
            text = { Text("Exports already queued keep the settings they were made with.") },
            confirmButton = {
                TextButton(onClick = { onDelete(preset); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}

