package inga.bpmetrics.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import inga.bpmetrics.ui.graph.BpmGraphPreview
import inga.bpmetrics.ui.tags.TagSelectionDialog
import inga.bpmetrics.ui.components.FlowRow

/**
 * The record detail screen, displaying full statistics and allowing editing of metadata and tags.
 *
 * @param viewModel The [inga.bpmetrics.ui.detail.BpmRecordViewModel] for the specific record.
 * @param onBack Callback for navigating back.
 * @param onDeleted Callback when the record is successfully deleted.
 * @param onShowDetailedGraph Callback to navigate to the detailed graph view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpmRecordScreen(
    viewModel: BpmRecordViewModel, 
    onBack: () -> Unit, 
    onDeleted: () -> Unit,
    onShowDetailedGraph: () -> Unit
) {
    val record by viewModel.record.collectAsState()
    var isEditing by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }

    record?.let { r ->
        var editedTitle by remember(r.metadata.title) { mutableStateOf(r.metadata.title) }
        var editedDescription by remember(r.metadata.description) { mutableStateOf(r.metadata.description) }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                    title = {
                        Column {
                            if (isEditing) {
                                OutlinedTextField(
                                    value = editedTitle,
                                    onValueChange = { editedTitle = it },
                                    label = { Text("Title") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(r.metadata.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            Text("${getDateString(r.metadata.date)} ${getTimeString(r.metadata.startTime)}", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (isEditing) {
                                viewModel.updateTitle(editedTitle)
                                viewModel.updateDescription(editedDescription)
                            }
                            isEditing = !isEditing
                        }) {
                            Icon(if (isEditing) Icons.Default.Done else Icons.Default.Edit, contentDescription = if (isEditing) "Save" else "Edit")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))
                
                // Enhanced BpmTrio: Bigger icons/text and interactive highlighting
                BpmTrio(
                    low = r.minDataPoint?.bpm?.toInt() ?: 0,
                    avg = r.metadata.avg?.toInt() ?: 0,
                    max = r.maxDataPoint?.bpm?.toInt() ?: 0,
                    iconSize = 32.dp,
                    fontSize = 24.sp,
                    onLowClick = { /* No longer highlighting directly on preview */ },
                    onMaxClick = { /* No longer highlighting directly on preview */ }
                )
                
                Spacer(Modifier.height(24.dp))
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text("Description", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (isEditing) {
                        OutlinedTextField(
                            value = editedDescription,
                            onValueChange = { editedDescription = it },
                            label = { Text("Description") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(r.metadata.description.ifBlank { "No description provided." }, style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tags", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { showTagDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Tag")
                        }
                    }

                    // Wrapping FlowRow for tags
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp), 
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (r.tags.isEmpty()) {
                            Text("No tags", style = MaterialTheme.typography.bodySmall)
                        } else {
                            r.tags.forEach { tag ->
                                SuggestionChip(
                                    onClick = { viewModel.removeTag(tag.tagId) },
                                    label = { Text(tag.name) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                
                // Static Preview Graph - Clicking navigates to Detail (Requirement)
                BpmGraphPreview(
                    record = r, 
                    modifier = Modifier.height(300.dp),
                    onClick = onShowDetailedGraph
                )
                
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { viewModel.deleteRecord(); onDeleted() }) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (showTagDialog) {
            TagSelectionDialog(
                onDismiss = { showTagDialog = false },
                onSave = { selectedIds ->
                    val currentIds = r.tags.map { it.tagId }
                    currentIds.forEach { if (!selectedIds.contains(it)) viewModel.removeTag(it) }
                    selectedIds.forEach { if (!currentIds.contains(it)) viewModel.addTag(it) }
                    showTagDialog = false
                },
                viewModel = viewModel,
                initialSelectedTagIds = r.tags.map { it.tagId }
            )
        }
    } ?: run { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
}
