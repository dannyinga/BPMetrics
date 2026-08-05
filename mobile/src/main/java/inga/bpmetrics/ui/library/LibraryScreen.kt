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
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Card
import inga.bpmetrics.ui.analysis.ConcurrentAnalysis
import inga.bpmetrics.ui.record.BpmRecordTile
import inga.bpmetrics.ui.tags.TagSelectionDialog
import inga.bpmetrics.ui.theme.BpmAccent
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.datasync.IncomingRecordManager
import inga.bpmetrics.datasync.isActive
import inga.bpmetrics.export.RenderQueueManager
import inga.bpmetrics.export.RenderStatus
import inga.bpmetrics.library.BpmRecord

/**
 * The main record library screen, displaying a list of BPM records with sorting and filtering options.
 *
 * @param navController The navigation controller used to navigate between screens.
 * @param viewModel The [inga.bpmetrics.ui.library.LibraryViewModel] providing the state and logic for this screen.
 */
import inga.bpmetrics.export.JsonExporter
import inga.bpmetrics.ui.export.VideoExportDialog
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Timeline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    viewModel: LibraryViewModel,
    onOpenDrawer: () -> Unit,
    /**
     * True when the user came here from Analysis to pick recordings to compare, so the screen can
     * say what it is waiting for rather than looking like it navigated somewhere arbitrary.
     */
    awaitingConcurrentSelection: Boolean = false,
    onAnalyseTogether: (Set<Long>) -> Unit = {},
    onAnalyseCurrentFilter: (LibraryViewModel.FilterState) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val currentSort by viewModel.sortOption.collectAsStateWithLifecycle()
    val selectedRecordIds by viewModel.selectedRecordIds.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Watch names are resolved live rather than stored on each record: renaming a watch should
    // relabel every recording it made. The wearer is the opposite — frozen when the record arrived.
    val watches by viewModel.availableWatches.collectAsStateWithLifecycle()
    val watchNames = remember(watches) {
        watches.filter { it.isNamed }.associate { it.watchId to it.deviceName }
    }

    val isSelectionMode = selectedRecordIds.isNotEmpty()

    var showSortMenu by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showBulkTagDialog by remember { mutableStateOf(false) }

    var showMultiVideoDialog by remember { mutableStateOf(false) }
    var selectedRecordsForMultiVideo by remember { mutableStateOf<List<BpmRecord>>(emptyList()) }

    if (isSelectionMode) {
        BackHandler {
            viewModel.clearSelection()
        }
    }

    // Launcher for importing CSV and JSON (.bpmjson) file(s)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            var successCount = 0
            uris.forEach { uri ->
                val mimeType = context.contentResolver.getType(uri)
                val isJson = mimeType?.contains("json") == true || uri.path?.lowercase()?.let { it.endsWith(".json") || it.endsWith(".bpmjson") } == true
                if (isJson) {
                    val jsonRecords = JsonExporter.importFromJson(context, uri)
                    jsonRecords.forEach { watchRecord ->
                        viewModel.importRecord(watchRecord)
                        successCount++
                    }
                } else {
                    val watchRecord = CsvExporter.importFromCsv(context, uri)
                    if (watchRecord != null) {
                        viewModel.importRecord(watchRecord)
                        successCount++
                    }
                }
            }
            if (successCount > 0) {
                Toast.makeText(context, "Successfully imported $successCount record(s)!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to import record(s).", Toast.LENGTH_SHORT).show()
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
                        val selectedRecords = uiState.records.filter {
                            it.metadata.recordId in selectedRecordIds
                        }

                        // Only Select All stays on the bar. Six icons left no room for a label,
                        // so every one of them was a guess from an unfamiliar glyph.
                        IconButton(onClick = { viewModel.selectAll(uiState.records) }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }

                        IconButton(onClick = { showSelectionMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                        }

                        DropdownMenu(
                            expanded = showSelectionMenu,
                            onDismissRequest = { showSelectionMenu = false }
                        ) {
                            // Same-time analysis needs recordings that actually ran together, so
                            // the action says why it is unavailable rather than simply refusing.
                            val overlaps = remember(selectedRecords) {
                                ConcurrentAnalysis.anyOverlap(selectedRecords)
                            }
                            DropdownMenuItem(
                                enabled = overlaps,
                                text = {
                                    Column {
                                        Text("Analyse together")
                                        if (!overlaps) {
                                            Text(
                                                if (selectedRecords.size < 2) {
                                                    "Select two or more recordings"
                                                } else {
                                                    "These were not recorded at the same time"
                                                },
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.Timeline, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    onAnalyseTogether(selectedRecordIds)
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Add tags") },
                                leadingIcon = { Icon(Icons.Default.Sell, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    showBulkTagDialog = true
                                }
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("Export video") },
                                leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    selectedRecordsForMultiVideo = selectedRecords
                                    showMultiVideoDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export CSV") },
                                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    recordsToExport = selectedRecords
                                    chooseFolderLauncher.launch(null)
                                    viewModel.clearSelection()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share as .bpmjson") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    JsonExporter.shareJson(context, selectedRecords)
                                    viewModel.clearSelection()
                                }
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showSelectionMenu = false
                                    showBulkDeleteDialog = true
                                }
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            // Work in progress is announced here too. The drawer carries the
                            // counts, but it is invisible while the drawer is closed — and a
                            // recording arriving from a watch is exactly what you want to notice.
                            val queue by RenderQueueManager.queue.collectAsState(initial = emptyList())
                            val incoming by IncomingRecordManager.incoming.collectAsState()
                            val activeCount = queue.count {
                                it.status == RenderStatus.RENDERING || it.status == RenderStatus.QUEUED
                            } + incoming.count { it.status.isActive }
                            Box {
                                Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
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
                    },
                    actions = {
                        // Import CSV / JSON button
                        IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Import Record(s)")
                        }
                        // Show Clear Filters button only when a filter is active
                        if (filterState != LibraryViewModel.FilterState()) {
                            IconButton(onClick = { viewModel.clearFilters() }) {
                                Icon(Icons.Default.FilterAltOff, contentDescription = "Clear All Filters")
                            }
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
                        // Analyses exactly what the Library is showing. Choosing a different
                        // scope means filtering here first, where the effect is visible, rather
                        // than through a dialog that silently re-filtered the list behind it.
                        onClick = { onAnalyseCurrentFilter(filterState) },
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
            if (awaitingConcurrentSelection && !isSelectionMode) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        "Press and hold recordings that were made at the same time, then choose " +
                            "Analyse together.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

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
                        watchName = record.metadata.watchId?.let { watchNames[it] },
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

    if (showFilterDialog) {
        val availableWearers by viewModel.availableWearers.collectAsStateWithLifecycle()
        val availableWatches by viewModel.availableWatches.collectAsStateWithLifecycle()

        LibraryFilterDialog(
            currentFilter = filterState,
            onDismiss = { showFilterDialog = false },
            onApply = { newFilter ->
                viewModel.updateFilter { newFilter }
                showFilterDialog = false
            },
            repository = viewModel.repository,
            availableWearers = availableWearers,
            availableWatches = availableWatches
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

    if (showMultiVideoDialog && selectedRecordsForMultiVideo.isNotEmpty()) {
        VideoExportDialog(
            record = selectedRecordsForMultiVideo.first(),
            records = selectedRecordsForMultiVideo,
            onDismiss = {
                showMultiVideoDialog = false
                viewModel.clearSelection()
            },
            onExport = { config, _ ->
                inga.bpmetrics.export.BpmExportService.startExport(
                    context,
                    selectedRecordsForMultiVideo.first().metadata.recordId,
                    "Multi-Watch Export (${selectedRecordsForMultiVideo.size} wearers)",
                    config,
                    null
                )
                Toast.makeText(context, "Multi-watch video export started in background!", Toast.LENGTH_SHORT).show()
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
