package inga.bpmetrics.ui.library

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import inga.bpmetrics.export.CsvExporter
import inga.bpmetrics.ui.Routes
import inga.bpmetrics.ui.analysis.AnalysisFilterDialog
import inga.bpmetrics.ui.record.BpmRecordTile
import inga.bpmetrics.ui.tags.TagSelectionDialog
import inga.bpmetrics.ui.theme.BpmAccent
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.export.RenderQueueManager
import inga.bpmetrics.export.RenderStatus
import inga.bpmetrics.library.BpmRecord

/**
 * The main record library screen, displaying a list of BPM records with sorting and filtering options.
 *
 * @param navController The navigation controller used to navigate between screens.
 * @param viewModel The [inga.bpmetrics.ui.library.LibraryViewModel] providing the state and logic for this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController, viewModel: LibraryViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val currentSort by viewModel.sortOption.collectAsStateWithLifecycle()
    val selectedRecordIds by viewModel.selectedRecordIds.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val isSelectionMode = selectedRecordIds.isNotEmpty()

    var showSortMenu by remember { mutableStateOf(false) }
    var showAnalyzeMenu by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var navigateToAnalysisOnFilterApply by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showBulkTagDialog by remember { mutableStateOf(false) }

    if (isSelectionMode) {
        BackHandler {
            viewModel.clearSelection()
        }
    }

    // Launcher for importing CSV file(s)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            var successCount = 0
            uris.forEach { uri ->
                val watchRecord = CsvExporter.importFromCsv(context, uri)
                if (watchRecord != null) {
                    viewModel.importRecord(watchRecord)
                    successCount++
                }
            }
            if (successCount > 0) {
                Toast.makeText(context, "Successfully imported $successCount record(s)!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to import CSV record(s).", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var recordsToExport by remember { mutableStateOf<List<BpmRecord>>(emptyList()) }

    val chooseFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { folderUri ->
            if (recordsToExport.isNotEmpty()) {
                val success = CsvExporter.exportToFolder(context, recordsToExport, folderUri)
                if (success) {
                    Toast.makeText(context, "Successfully exported ${recordsToExport.size} CSVs!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Failed to export CSVs.", Toast.LENGTH_LONG).show()
                }
                recordsToExport = emptyList()
            }
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedRecordIds.size} Selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll(uiState.records) }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }
                        IconButton(onClick = { showBulkTagDialog = true }) {
                            Icon(Icons.Default.Sell, contentDescription = "Add Tags in Bulk")
                        }
                        IconButton(onClick = {
                            recordsToExport = uiState.records.filter { it.metadata.recordId in selectedRecordIds }
                            chooseFolderLauncher.launch(null)
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "Export CSV in Bulk")
                        }
                        IconButton(onClick = { showBulkDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) },
                    actions = {
                        // Import CSV button
                        IconButton(onClick = { importLauncher.launch(arrayOf("text/comma-separated-values", "text/csv")) }) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Import CSV(s)")
                        }
                        // Show Clear Filters button only when a filter is active
                        if (filterState != LibraryViewModel.FilterState()) {
                            IconButton(onClick = { viewModel.clearFilters() }) {
                                Icon(Icons.Default.FilterAltOff, contentDescription = "Clear All Filters")
                            }
                        }
                        IconButton(onClick = { navController.navigate(Routes.TAG_MANAGEMENT) }) {
                            Icon(Icons.Default.Sell, contentDescription = "Manage Tags")
                        }
                        
                        val queue by RenderQueueManager.queue.collectAsState(initial = emptyList())
                        val activeCount = queue.count { it.status == RenderStatus.RENDERING || it.status == RenderStatus.QUEUED }
                        IconButton(onClick = { navController.navigate(Routes.RENDER_QUEUE) }) {
                            Box {
                                Icon(Icons.Default.VideoLibrary, contentDescription = "Render Queue")
                                if (activeCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(BpmHigh)
                                            .align(Alignment.TopEnd)
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!isSelectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = { showAnalyzeMenu = true },
                        modifier = Modifier.fillMaxWidth(0.7f),
                        colors = ButtonDefaults.buttonColors(containerColor = BpmAccent)
                    ) {
                        Text("Analyze", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), 
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { showSortMenu = true }, 
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(currentSort.name.replace("_", " ").lowercase().capitalize())
                    }
                    
                    DropdownMenu(
                        expanded = showSortMenu, 
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        LibraryViewModel.SortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name.replace("_", " ").lowercase().capitalize()) },
                                onClick = {
                                    viewModel.setSortOption(option)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { viewModel.toggleReverse() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Reverse Order")
                }

                OutlinedButton(
                    onClick = {
                        navigateToAnalysisOnFilterApply = false
                        showFilterDialog = true
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FilterAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Filter")
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(uiState.records) { record ->
                    val isSelected = selectedRecordIds.contains(record.metadata.recordId)
                    BpmRecordTile(
                        record = record,
                        isSelected = isSelected,
                        onClick = {
                            if (isSelectionMode) {
                                viewModel.toggleRecordSelection(record.metadata.recordId)
                            } else {
                                navController.navigate("detail/${record.metadata.recordId}")
                            }
                        },
                        onLongClick = {
                            viewModel.toggleRecordSelection(record.metadata.recordId)
                        }
                    )
                }
            }
        }
    }

    if (showAnalyzeMenu) {
        AnalysisFilterDialog(
            onDismiss = { showAnalyzeMenu = false },
            onAnalyzeCurrent = {
                showAnalyzeMenu = false
                navController.navigate(Routes.ANALYSIS)
            },
            onSelectNewFilter = {
                showAnalyzeMenu = false
                navigateToAnalysisOnFilterApply = true
                showFilterDialog = true
            }
        )
    }

    if (showFilterDialog) {
        LibraryFilterDialog(
            currentFilter = filterState,
            onDismiss = { showFilterDialog = false },
            onApply = { newFilter ->
                viewModel.updateFilter { newFilter }
                showFilterDialog = false
                if (navigateToAnalysisOnFilterApply) {
                    navController.navigate(Routes.ANALYSIS)
                }
            },
            repository = viewModel.repository
        )
    }

    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { Text("Delete Selected Recordings") },
            text = { Text("Are you sure you want to permanently delete the ${selectedRecordIds.size} selected recordings? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedRecords()
                        showBulkDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBulkTagDialog) {
        val categories by viewModel.repository.getAllCategories().collectAsState(initial = emptyList())
        TagSelectionDialog(
            onDismiss = { showBulkTagDialog = false },
            onSave = { selectedTagIds ->
                viewModel.addTagsToSelectedRecords(selectedTagIds)
                showBulkTagDialog = false
            },
            onManageTags = {
                showBulkTagDialog = false
                navController.navigate(Routes.TAG_MANAGEMENT)
            },
            categories = categories,
            getTagsByCategoryFlow = { viewModel.repository.getTagsByCategory(it) },
            initialSelectedTagIds = emptyList()
        )
    }
}

// Extension to capitalize first letter since String.capitalize() is deprecated
fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
