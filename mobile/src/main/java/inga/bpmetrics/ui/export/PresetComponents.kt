package inga.bpmetrics.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.ExportPresetEntity
import inga.bpmetrics.ui.components.FlowRow

/**
 * The preset bar for step 3.
 *
 * Applying one is a single tap on a chip; everything that changes a preset lives behind the
 * overflow, because the common action is "use this one" and the rare ones should not sit next to
 * it competing for attention.
 *
 * @param selectedId Which preset the current settings started from. It stays selected through
 *   edits, so "Update to current settings" is there when there's something to update with.
 * @param edited Whether the settings hold changes no preset has. Choosing a preset then asks
 *   first, because it replaces them (Audit #21).
 */
@Composable
fun PresetBar(
    presets: List<ExportPresetEntity>,
    selectedId: Long?,
    edited: Boolean,
    onApply: (ExportPresetEntity) -> Unit,
    onSaveAs: (String) -> Unit,
    onUpdate: (ExportPresetEntity) -> Unit,
    onSetDefault: (ExportPresetEntity) -> Unit,
    onDelete: (ExportPresetEntity) -> Unit,
    onExportFile: (ExportPresetEntity) -> Unit,
    onImportFile: () -> Unit
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ExportPresetEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<ExportPresetEntity?>(null) }
    var confirmApply by remember { mutableStateOf<ExportPresetEntity?>(null) }
    val selected = presets.firstOrNull { it.presetId == selectedId }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Presets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = onImportFile) { Text("Import") }
                TextButton(onClick = { showSaveDialog = true }) { Text("Save as…") }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            presets.forEach { preset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = preset.presetId == selectedId,
                        onClick = { if (edited) confirmApply = preset else onApply(preset) },
                        leadingIcon = if (preset.isDefault) {
                            {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "Default",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        } else null,
                        label = { Text(preset.name) }
                    )
                    Box {
                        IconButton(
                            onClick = { menuFor = preset },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "${preset.name} options",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuFor?.presetId == preset.presetId,
                            onDismissRequest = { menuFor = null }
                        ) {
                            if (!preset.isDefault) {
                                DropdownMenuItem(
                                    text = { Text("Make default") },
                                    onClick = { menuFor = null; onSetDefault(preset) }
                                )
                            }
                            // On the preset the settings started from, edited or not. It used to
                            // vanish at the first edit, which was the one time it was wanted.
                            if (preset.presetId == selectedId && !preset.isBuiltIn) {
                                DropdownMenuItem(
                                    text = { Text("Update to current settings") },
                                    onClick = { menuFor = null; onUpdate(preset) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Export to file") },
                                onClick = { menuFor = null; onExportFile(preset) }
                            )
                            if (!preset.isBuiltIn) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = { menuFor = null; confirmDelete = preset }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (edited) {
            Text(
                when {
                    selected == null -> "These settings aren't saved as a preset."
                    selected.isBuiltIn -> "Changed from ${selected.name}. Save as a new preset to keep them."
                    else -> "Changed from ${selected.name}. Update it from its menu, or save as a new preset."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                        "These settings aren't saved as a preset, and ${preset.name} replaces them."
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
            title = { Text("Save these settings") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Preset name") },
                        placeholder = { Text("Story 9:16") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Saves how the export looks — canvas, graph, colours. Never which " +
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

