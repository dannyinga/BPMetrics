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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import inga.bpmetrics.library.EventSuggestion
import inga.bpmetrics.ui.components.DeleteConfirmDialog
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
import androidx.compose.material.icons.filled.People
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

    // Both names are resolved live rather than stored on each record, so renaming a watch or
    // correcting someone's spelling relabels every recording it applies to. What stays fixed is
    // *which* watch and *which person* a recording points at.
    val watches by viewModel.availableWatches.collectAsStateWithLifecycle()
    val watchNames = remember(watches) {
        watches.filter { it.isNamed }.associate { it.watchId to it.deviceName }
    }
    val peopleById by viewModel.peopleById.collectAsStateWithLifecycle()

    // Collected here rather than at the share call so a backup carries the profiles and category
    // names its records point at. Without them the file restores recordings attributed to nobody.
    val availablePeopleForBackup by viewModel.availablePeople.collectAsStateWithLifecycle()
    val categoriesForBackup by remember { viewModel.repository.getAllCategories() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val isSelectionMode = selectedRecordIds.isNotEmpty()

    var showSortMenu by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showBulkTagDialog by remember { mutableStateOf(false) }
    var showBulkWearerDialog by remember { mutableStateOf(false) }

    var showMultiVideoDialog by remember { mutableStateOf(false) }
    var selectedRecordsForMultiVideo by remember { mutableStateOf<List<BpmRecord>>(emptyList()) }

    // --- Events and groups ---

    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val eventGroups by viewModel.eventGroups.collectAsStateWithLifecycle()
    val ungroupedEvents by viewModel.ungroupedEvents.collectAsStateWithLifecycle()
    val unfiled by viewModel.unfiledRecords.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    // Events and groups share one expansion set. Their ids come from different tables and could
    // collide, but the two never appear in the same view, so a collision is never visible.
    var expandedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    var showCreateEventDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var renamingEvent by remember { mutableStateOf<EventSummary?>(null) }
    var renamingGroup by remember { mutableStateOf<GroupSummary?>(null) }
    var deletingEvent by remember { mutableStateOf<EventSummary?>(null) }
    var deletingGroup by remember { mutableStateOf<GroupSummary?>(null) }
    var movingEvent by remember { mutableStateOf<EventSummary?>(null) }
    var showAddToEventDialog by remember { mutableStateOf(false) }
    var suggestionToName by remember { mutableStateOf<EventSuggestion?>(null) }
    // Set when "New event…" is chosen from the bulk menu, so the name dialog knows to file the
    // current selection into whatever it creates rather than creating an empty event.
    var namingEventForSelection by remember { mutableStateOf(false) }

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
                    // Restored as a whole backup rather than record by record, so the people and
                    // watches come back before the recordings that refer to them. Importing one
                    // record at a time cannot recreate a person, and every recording would land
                    // attributed to nobody.
                    val backup = JsonExporter.readBackup(context, uri)
                    if (backup != null) {
                        successCount += backup.records.size
                        viewModel.restoreFromBackup(backup) { result ->
                            Toast.makeText(
                                context,
                                if (result.succeeded) {
                                    "Restored ${result.recordsImported} recordings, " +
                                        "${result.peopleCreated} people, ${result.watchesCreated} watches"
                                } else {
                                    "Restore failed: ${result.failure}"
                                },
                                Toast.LENGTH_LONG
                            ).show()
                        }
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

    // Saves the backup to a file the user picks, rather than handing it to the share sheet.
    //
    // A backup wants to land somewhere you can find it again — Downloads, Drive, an SD card —
    // and the share sheet's list of chat apps is the wrong set of destinations for that.
    var recordsToBackUp by remember { mutableStateOf<List<BpmRecord>>(emptyList()) }

    val saveBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val toWrite = recordsToBackUp
        recordsToBackUp = emptyList()
        if (uri == null || toWrite.isEmpty()) return@rememberLauncherForActivityResult

        viewModel.buildBackupJson(
            records = toWrite,
            people = availablePeopleForBackup,
            watches = watches,
            categories = categoriesForBackup
        ) { json ->
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            Toast.makeText(
                context,
                if (ok) {
                    "Backed up ${toWrite.size} recording${if (toWrite.size == 1) "" else "s"}, " +
                        "plus saved analyses and settings"
                } else {
                    "Could not write the backup"
                },
                Toast.LENGTH_LONG
            ).show()
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

                            DropdownMenuItem(
                                text = { Text("Set wearer") },
                                leadingIcon = { Icon(Icons.Default.People, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    showBulkWearerDialog = true
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Add to event") },
                                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    showAddToEventDialog = true
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
                                text = { Text("Save backup (.bpmjson)") },
                                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    recordsToBackUp = selectedRecords
                                    val stamp = java.text.SimpleDateFormat(
                                        "yyyyMMdd_HHmm",
                                        java.util.Locale.US
                                    ).format(java.util.Date())
                                    saveBackupLauncher.launch("BPMetrics_Backup_$stamp.bpmjson")
                                    viewModel.clearSelection()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share as .bpmjson") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    // People, watches and categories go with the records, or the
                                    // file restores recordings that belong to nobody.
                                    JsonExporter.shareJson(
                                        context = context,
                                        records = selectedRecords,
                                        people = availablePeopleForBackup,
                                        watches = watches,
                                        categories = categoriesForBackup
                                    )
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

            // Three ways of looking at the same recordings, so the switch sits above everything
            // that only applies to one of them.
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                LibraryViewMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = viewMode == mode,
                        onClick = { viewModel.setViewMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, LibraryViewMode.entries.size)
                    ) {
                        Text(
                            when (mode) {
                                LibraryViewMode.RECORDINGS -> "Recordings"
                                LibraryViewMode.EVENTS -> "Events"
                                LibraryViewMode.GROUPS -> "Groups"
                            }
                        )
                    }
                }
            }

            if (viewMode == LibraryViewMode.RECORDINGS) {
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
            }

            // A tile, wherever one appears — in the flat list, inside an event, or under a
            // suggestion. Selection and navigation behave the same in all three, which is what
            // makes press-and-hold-then-file work from anywhere.
            val tile: @Composable (BpmRecord) -> Unit = { record ->
                val isSelected = selectedRecordIds.contains(record.metadata.recordId)
                BpmRecordTile(
                    record = record,
                    isSelected = isSelected,
                    watchName = record.metadata.watchId?.let { watchNames[it] },
                    wearer = record.metadata.personId?.let { peopleById[it] },
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

            when (viewMode) {
                LibraryViewMode.RECORDINGS -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.records) { record -> tile(record) }
                    }
                }

                LibraryViewMode.EVENTS -> EventsList(
                    events = events,
                    unfiled = unfiled,
                    suggestions = suggestions,
                    groupNames = remember(eventGroups) {
                        eventGroups.associate { it.group.groupId to it.group.displayName }
                    },
                    peopleById = peopleById,
                    expandedIds = expandedIds,
                    onToggleExpand = { id ->
                        expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
                    },
                    onCreateEvent = { showCreateEventDialog = true },
                    onRename = { renamingEvent = it },
                    onMoveToGroup = { movingEvent = it },
                    onDelete = { deletingEvent = it },
                    onAcceptSuggestion = { suggestionToName = it },
                    onDismissSuggestion = { viewModel.dismissSuggestion(it) },
                    tile = tile
                )

                LibraryViewMode.GROUPS -> GroupsList(
                    groups = eventGroups,
                    ungrouped = ungroupedEvents,
                    expandedIds = expandedIds,
                    onToggleExpand = { id ->
                        expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
                    },
                    onCreateGroup = { showCreateGroupDialog = true },
                    onRenameGroup = { renamingGroup = it },
                    onDeleteGroup = { deletingGroup = it },
                    onMoveEvent = { movingEvent = it }
                )
            }
        }
    }

    if (showFilterDialog) {
        val availablePeople by viewModel.availablePeople.collectAsStateWithLifecycle()
        val availableWatches by viewModel.availableWatches.collectAsStateWithLifecycle()

        LibraryFilterDialog(
            currentFilter = filterState,
            onDismiss = { showFilterDialog = false },
            onApply = { newFilter ->
                viewModel.updateFilter { newFilter }
                showFilterDialog = false
            },
            repository = viewModel.repository,
            availablePeople = availablePeople,
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

    if (showBulkWearerDialog) {
        val availablePeople by viewModel.availablePeople.collectAsStateWithLifecycle()
        BulkWearerDialog(
            recordCount = selectedRecordIds.size,
            people = availablePeople,
            onDismiss = { showBulkWearerDialog = false },
            onAssign = { personId ->
                viewModel.assignPersonToSelectedRecords(personId)
                showBulkWearerDialog = false
            }
        )
    }

    // --- Event and group dialogs ---

    if (showAddToEventDialog) {
        AddToEventDialog(
            recordCount = selectedRecordIds.size,
            events = events,
            onDismiss = { showAddToEventDialog = false },
            onPick = { eventId ->
                viewModel.assignSelectedToEvent(eventId)
                showAddToEventDialog = false
            },
            onCreateEvent = {
                showAddToEventDialog = false
                namingEventForSelection = true
                showCreateEventDialog = true
            }
        )
    }

    if (showCreateEventDialog) {
        val forSelection = namingEventForSelection
        val count = selectedRecordIds.size
        NameDialog(
            title = "New event",
            label = "Event name",
            confirmLabel = "Create",
            supporting = if (forSelection) {
                "The $count selected recording${if (count == 1) "" else "s"} will be filed here."
            } else null,
            onDismiss = {
                showCreateEventDialog = false
                namingEventForSelection = false
            },
            onConfirm = { name ->
                viewModel.createEvent(name, if (forSelection) selectedRecordIds else emptySet())
                showCreateEventDialog = false
                namingEventForSelection = false
            }
        )
    }

    // Naming a suggested event is the same dialog, pre-filled with nothing — the app has no idea
    // what the occasion was, only that it happened.
    suggestionToName?.let { suggestion ->
        NameDialog(
            title = "New event",
            label = "Event name",
            confirmLabel = "Create",
            supporting = "${suggestion.size} recordings from ${formatSpan(suggestion.span)}.",
            onDismiss = { suggestionToName = null },
            onConfirm = { name ->
                viewModel.createEvent(
                    name,
                    suggestion.records.map { it.metadata.recordId }.toSet()
                )
                suggestionToName = null
            }
        )
    }

    if (showCreateGroupDialog) {
        NameDialog(
            title = "New group",
            label = "Group name",
            confirmLabel = "Create",
            supporting = "A group collects events that belong together — a tour, a season, a study.",
            onDismiss = { showCreateGroupDialog = false },
            onConfirm = { name ->
                viewModel.createEventGroup(name)
                showCreateGroupDialog = false
            }
        )
    }

    renamingEvent?.let { summary ->
        NameDialog(
            title = "Rename event",
            label = "Event name",
            initial = summary.event.name,
            onDismiss = { renamingEvent = null },
            onConfirm = { name ->
                viewModel.renameEvent(summary.event.eventId, name)
                renamingEvent = null
            }
        )
    }

    renamingGroup?.let { summary ->
        NameDialog(
            title = "Rename group",
            label = "Group name",
            initial = summary.group.name,
            onDismiss = { renamingGroup = null },
            onConfirm = { name ->
                viewModel.renameEventGroup(summary.group.groupId, name)
                renamingGroup = null
            }
        )
    }

    movingEvent?.let { summary ->
        GroupPickerDialog(
            eventName = summary.event.displayName,
            groups = eventGroups,
            currentGroupId = summary.event.groupId,
            onDismiss = { movingEvent = null },
            onPick = { groupId ->
                viewModel.setEventGroup(summary.event.eventId, groupId)
                movingEvent = null
            },
            onCreateGroup = {
                movingEvent = null
                showCreateGroupDialog = true
            }
        )
    }

    deletingEvent?.let { summary ->
        DeleteConfirmDialog(
            title = "Delete ${summary.event.displayName}?",
            message = if (summary.recordCount > 0) {
                "Its ${summary.recordCount} recording" +
                    "${if (summary.recordCount == 1) "" else "s"} will be kept and move back to " +
                    "Unfiled. Only the event is deleted."
            } else {
                "This event has no recordings in it."
            },
            onDismiss = { deletingEvent = null },
            onConfirm = {
                viewModel.deleteEvent(summary.event.eventId)
                deletingEvent = null
            }
        )
    }

    deletingGroup?.let { summary ->
        DeleteConfirmDialog(
            title = "Delete ${summary.group.displayName}?",
            message = if (summary.eventCount > 0) {
                "Its ${summary.eventCount} event${if (summary.eventCount == 1) "" else "s"} will " +
                    "be kept and stop belonging to any group. No recordings are deleted."
            } else {
                "This group has no events in it."
            },
            onDismiss = { deletingGroup = null },
            onConfirm = {
                viewModel.deleteEventGroup(summary.group.groupId)
                deletingGroup = null
            }
        )
    }
}

/**
 * The events view: suggestions, then the events, then Unfiled.
 *
 * Events come first because they are what the view is for — the organised library, not the inbox.
 * Unfiled sits below as its own section rather than mixed in, and the suggestion cards at the top
 * are what keeps it from being missed.
 */
@Composable
private fun EventsList(
    events: List<EventSummary>,
    unfiled: List<BpmRecord>,
    suggestions: List<EventSuggestion>,
    groupNames: Map<Long, String>,
    peopleById: Map<Long, inga.bpmetrics.library.PersonEntity>,
    expandedIds: Set<Long>,
    onToggleExpand: (Long) -> Unit,
    onCreateEvent: () -> Unit,
    onRename: (EventSummary) -> Unit,
    onMoveToGroup: (EventSummary) -> Unit,
    onDelete: (EventSummary) -> Unit,
    onAcceptSuggestion: (EventSuggestion) -> Unit,
    onDismissSuggestion: (EventSuggestion) -> Unit,
    tile: @Composable (BpmRecord) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedButton(onClick = onCreateEvent, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New event")
            }
        }

        // Keys are prefixed because record ids, event ids and group ids come from different tables
        // and freely collide. LazyColumn requires them unique across the whole list, so an event
        // numbered 3 sitting under a recording numbered 3 would crash the screen.
        items(suggestions, key = { "suggestion-${it.records.first().metadata.recordId}" }) { suggestion ->
            SuggestionCard(
                suggestion = suggestion,
                people = suggestion.records
                    .mapNotNull { r -> r.metadata.personId?.let { peopleById[it] } }
                    .distinct(),
                onAccept = { onAcceptSuggestion(suggestion) },
                onDismiss = { onDismissSuggestion(suggestion) }
            )
        }

        if (events.isNotEmpty()) {
            item { SectionHeader("Events", "${events.size}") }
        }
        items(events, key = { "event-${it.event.eventId}" }) { summary ->
            EventCard(
                summary = summary,
                groupName = summary.event.groupId?.let { groupNames[it] },
                expanded = summary.event.eventId in expandedIds,
                onToggleExpand = { onToggleExpand(summary.event.eventId) },
                onRename = { onRename(summary) },
                onMoveToGroup = { onMoveToGroup(summary) },
                onDelete = { onDelete(summary) }
            ) {
                summary.records.forEach { record -> tile(record) }
            }
        }

        if (unfiled.isNotEmpty()) {
            item {
                SectionHeader("Unfiled", "${unfiled.size}")
            }
            items(unfiled, key = { "unfiled-${it.metadata.recordId}" }) { record -> tile(record) }
        }

        if (events.isEmpty() && unfiled.isEmpty()) {
            item {
                Text(
                    "No recordings yet. Once some arrive from a watch, group them into events here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The groups view: groups, then the events that belong to none of them. */
@Composable
private fun GroupsList(
    groups: List<GroupSummary>,
    ungrouped: List<EventSummary>,
    expandedIds: Set<Long>,
    onToggleExpand: (Long) -> Unit,
    onCreateGroup: () -> Unit,
    onRenameGroup: (GroupSummary) -> Unit,
    onDeleteGroup: (GroupSummary) -> Unit,
    onMoveEvent: (EventSummary) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedButton(onClick = onCreateGroup, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New group")
            }
        }

        items(groups, key = { "group-${it.group.groupId}" }) { summary ->
            GroupCard(
                summary = summary,
                expanded = summary.group.groupId in expandedIds,
                onToggleExpand = { onToggleExpand(summary.group.groupId) },
                onRename = { onRenameGroup(summary) },
                onDelete = { onDeleteGroup(summary) }
            ) {
                summary.events.forEach { event ->
                    NestedEventRow(event) { onMoveEvent(event) }
                }
            }
        }

        if (ungrouped.isNotEmpty()) {
            item { SectionHeader("Not in a group", "${ungrouped.size}") }
            items(ungrouped, key = { "ungrouped-${it.event.eventId}" }) { event ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        NestedEventRow(event) { onMoveEvent(event) }
                    }
                }
            }
        }

        if (groups.isEmpty() && ungrouped.isEmpty()) {
            item {
                Text(
                    "Groups collect events that belong together — a tour, a season, a study. " +
                        "Create some events first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            count,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Extension to capitalize first letter since String.capitalize() is deprecated
fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
