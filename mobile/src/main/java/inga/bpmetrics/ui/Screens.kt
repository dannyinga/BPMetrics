package inga.bpmetrics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.StringFormatHelpers.getDurationString
import inga.bpmetrics.ui.StringFormatHelpers.getTimeString


/**
 * The high-level library screen that displays a list of all heart rate records.
 *
 * It uses a [LazyColumn] to efficiently display records and supports navigation to
 * the detail view for any selected record.
 *
 * @param navController The [NavController] used for navigating to record details.
 * @param viewModel The [LibraryViewModel] providing the list of records and UI state.
 */
@Composable
fun LibraryScreen(navController: NavController, viewModel: LibraryViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val records = uiState.records
    // Display records with the most recent first
    val sortedRecords = records.sortedByDescending { it.metadata.startTime }

    LazyColumn (
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(sortedRecords) { record ->
            BpmRecordTile(
                record = record,
                onClick = {
                    navController.navigate("detail/${record.metadata.recordId}")
                }
            )
        }
    }
}

/**
 * The detail screen for a specific heart rate record.
 *
 * This screen displays the record metadata, statistics (Min, Max, Avg), and a visual
 * graph of the data points. It also allows the user to edit the title or delete the record.
 *
 * @param viewModel The [BpmRecordViewModel] managing the specific record's data.
 * @param onBack Callback invoked when the user navigates back to the library.
 * @param onDeleted Callback invoked after a record has been successfully deleted.
 */
@Composable
fun BpmRecordScreen(
    viewModel: BpmRecordViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val record by viewModel.record.collectAsState()

    BpmRecordContent(
        record = record,
        onBack = onBack,
        onRefresh = { viewModel.refresh() },
        onDelete = {
            viewModel.deleteRecord()
            onDeleted()
        },
        onUpdateTitle = { viewModel.updateTitle(it) }
    )
}

/**
 * The layout content for the BPM record detail view.
 *
 * Separated from the screen logic to facilitate testing and Compose previews.
 * Handles the display of the top app bar, metadata sections, and the [BpmGraph].
 *
 * @param record The current [BpmRecord] to display.
 * @param onBack Callback for the navigation back icon.
 * @param onRefresh Callback for manually reloading record data.
 * @param onDelete Callback for the delete action icon.
 * @param onUpdateTitle Callback for persisting title changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpmRecordContent(
    record: BpmRecord?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    // Local state to track whether the title is currently in an editable mode
    var isEditingTitle by remember { mutableStateOf(false) }

    record?.let {
        var title by remember(it.metadata.title) { mutableStateOf(it.metadata.title) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Record Details") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reanalyze")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Record")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(16.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title display and editing row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isEditingTitle) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Record Title") },
                            keyboardActions = KeyboardActions(onDone = {
                                onUpdateTitle(title)
                                isEditingTitle = false
                            })
                        )
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(onClick = {
                        if (isEditingTitle) {
                            onUpdateTitle(title)
                        }
                        isEditingTitle = !isEditingTitle
                    }) {
                        Icon(
                            imageVector = if (isEditingTitle) Icons.Default.Done else Icons.Default.Edit,
                            contentDescription = if (isEditingTitle) "Save Title" else "Edit Title"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Core record metadata
                Column (horizontalAlignment = Alignment.Start) {
                    Text("Date: " + getDateString(it.metadata.date))
                    Text("Time: " + getTimeString(it.metadata.startTime))
                    Text("Duration: " + getDurationString(it.metadata.durationMs))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Statistics Display
                Text("Max BPM: ${it.maxDataPoint}", color = Color.Red)
                Text("Avg BPM: ${it.metadata.avg}")
                Text("Min BPM: ${it.minDataPoint}", color = Color.Blue)

                Spacer(modifier = Modifier.height(24.dp))

                // Visual Representation
                BpmGraph(it)
            }
        }
    } ?: run {
        // Placeholder box for when the record is still being fetched from the database
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Loading record details...")
        }
    }
}
