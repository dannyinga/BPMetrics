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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.components.rememberCoverPicker
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import inga.bpmetrics.export.CsvExporter
import inga.bpmetrics.ui.export.ExportKind
import inga.bpmetrics.ui.Routes
import androidx.compose.material3.Card
import inga.bpmetrics.ui.analysis.ConcurrentAnalysis
import inga.bpmetrics.ui.record.BpmRecordTile
import inga.bpmetrics.ui.tags.TagSelectionDialog
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.datasync.IncomingRecordManager
import inga.bpmetrics.datasync.isActive
import inga.bpmetrics.export.RenderQueueManager
import inga.bpmetrics.export.RenderStatus
import inga.bpmetrics.library.clock
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.displayName
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString

/**
 * The main record library screen, displaying a list of BPM records with sorting and filtering options.
 *
 * @param navController The navigation controller used to navigate between screens.
 * @param viewModel The [inga.bpmetrics.ui.library.LibraryViewModel] providing the state and logic for this screen.
 */
import inga.bpmetrics.export.JsonExporter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Image
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
    /** Opens the export utility for the current multi-selection, as a video or an image. */
    onExportSelection: (List<BpmRecord>, ExportKind) -> Unit = { _, _ -> },
    /** Analyses the library as currently filtered — the question, as its own scope. */
    onAnalyseFilter: (inga.bpmetrics.library.FilterState) -> Unit = {}
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
    val coversByRecord by viewModel.coversByRecord.collectAsStateWithLifecycle()
    val coversByEvent by viewModel.coversByEvent.collectAsStateWithLifecycle()

    // Collected here rather than at the share call so a backup carries the profiles and category
    // names its records point at. Without them the file restores recordings attributed to nobody.
    val availablePeopleForBackup by viewModel.availablePeople.collectAsStateWithLifecycle()
    val categoriesForBackup by remember { viewModel.repository.getAllCategories() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Selection used to be reachable only by pressing and holding a tile, which is a gesture with
    // nothing on screen to suggest it exists — the app was telling people about it in a hint card,
    // which is what a UI does when it has run out of ways to show something. The app bar button
    // arms the mode with nothing selected yet, so the gesture is now the shortcut rather than the
    // only door.
    var selectionArmed by rememberSaveable { mutableStateOf(false) }
    val isSelectionMode = selectedRecordIds.isNotEmpty() || selectionArmed

    // One way out, so no exit can clear the selection and leave the mode armed behind it — which
    // would look like the screen ignoring the close button.
    val exitSelection = {
        selectionArmed = false
        viewModel.clearSelection()
    }

    // Setting a cover from multi-select puts it on the *event* those recordings share, not on each
    // recording. That is the whole design — a recording arriving late from the same night inherits
    // the picture rather than needing the operation repeated. It refuses rather than guesses when
    // the selection spans several events or includes something unfiled, because the alternative is
    // silently doing something other than what was asked.
    val pickCoverForSelection = rememberCoverPicker { uri ->
        viewModel.setCoverForSelection(context, uri) { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // Which collection a cover is being chosen for. Held outside the picker because the launcher is
    var showSortMenu by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    // The filter is a bar now, not a dialog. Kept only as "is the bar open" — the terms themselves
    // live in the ViewModel, so closing the bar no longer hides what is applied.
    var showFilterBar by rememberSaveable { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showBulkTagDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }

    // Needed outside the top bar too, by the merge dialog. Derived rather than held, so it can
    // never describe a selection that has since changed.
    val selectedRecordsForBulk = uiState.records.filter { it.metadata.recordId in selectedRecordIds }
    var showBulkWearerDialog by remember { mutableStateOf(false) }


    // --- Events and groups ---

    val events by viewModel.events.collectAsStateWithLifecycle()
    val eventPickerRows by viewModel.eventPickerRows.collectAsStateWithLifecycle()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val eventSummariesById by viewModel.eventSummariesById.collectAsStateWithLifecycle()
    val timelineExpanded by viewModel.expandedInTimeline.collectAsStateWithLifecycle()
    val knownLocations by viewModel.locations.collectAsStateWithLifecycle()
    val placeNames by viewModel.placeNamesByEvent.collectAsStateWithLifecycle()
    val filterChips by viewModel.filterChips.collectAsStateWithLifecycle()
    val filterOptions by viewModel.filterOptions.collectAsStateWithLifecycle()
    val savedViews by viewModel.pinnedSelections.collectAsStateWithLifecycle()
    val activeViewId by viewModel.activeViewId.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    var addingSelectionToCollection by remember { mutableStateOf(false) }
    var addingEventToCollection by remember { mutableStateOf<EventSummary?>(null) }
    val selectedEventIds by viewModel.selectedEventIds.collectAsStateWithLifecycle()
    var movingSelectedEvents by remember { mutableStateOf(false) }
    var addingSelectedEventsToCollection by remember { mutableStateOf(false) }
    val unfiled by viewModel.unfiledRecords.collectAsStateWithLifecycle()

    // Events and groups share one expansion set. Their ids come from different tables and could
    // collide, but the two never appear in the same view, so a collision is never visible.
    var expandedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    var showCreateEventDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var renamingEvent by remember { mutableStateOf<EventSummary?>(null) }
    var deletingEvent by remember { mutableStateOf<EventSummary?>(null) }
    var movingEvent by remember { mutableStateOf<EventSummary?>(null) }
    var showAddToEventDialog by remember { mutableStateOf(false) }
    // Set when "New event…" is chosen from the bulk menu, so the name dialog knows to file the
    // current selection into whatever it creates rather than creating an empty event.
    var namingEventForSelection by remember { mutableStateOf(false) }

    if (isSelectionMode) {
        BackHandler {
            exitSelection()
        }
    }

    // Its own handler rather than a branch inside the one above: the two selections are separate,
    // and back should undo whichever one is actually running.
    if (selectedEventIds.isNotEmpty()) {
        BackHandler { viewModel.clearEventSelection() }
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
                        viewModel.restoreFromBackup(backup, context) { result ->
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

    // Ids, not records. A CSV is every reading of every chosen recording, so the readings are
    // loaded at the point of writing the file rather than held here waiting for a folder to be
    // picked — which may never happen.
    var recordIdsToExport by remember { mutableStateOf<List<Long>>(emptyList()) }
    val exportScope = rememberCoroutineScope()

    val chooseFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { folderUri ->
            if (recordIdsToExport.isNotEmpty()) exportScope.launch {
                val chosen = viewModel.repository.recordsWithPoints(recordIdsToExport)
                val success = CsvExporter.exportToFolder(context, chosen, folderUri)
                if (success) {
                    Toast.makeText(context, "Successfully exported  CSVs!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Failed to export CSVs.", Toast.LENGTH_LONG).show()
                }
                recordIdsToExport = emptyList()
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
            people = availablePeopleForBackup,
            watches = watches,
            categories = categoriesForBackup,
            context = context
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
                    title = {
                        Text(
                            // "0 Selected" is a strange thing for a screen to announce when the
                            // mode was just armed and nothing has been tapped yet. Say what to do
                            // instead, and switch to the count once there is one.
                            if (selectedRecordIds.isEmpty()) {
                                "Select recordings"
                            } else {
                                "${selectedRecordIds.size} selected"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
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
                                ConcurrentAnalysis.anyOverlap(selectedRecords.map { it.metadata })
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

                            // Distinct from filing, and worth two menu items rather than one:
                            // an event is where a recording *lives* and it has exactly one, a
                            // collection is a grouping it can be in any number of at once.
                            DropdownMenuItem(
                                text = { Text("Add to collection") },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.LibraryBooks,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showSelectionMenu = false
                                    addingSelectionToCollection = true
                                }
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("Export video") },
                                leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    onExportSelection(selectedRecords, ExportKind.VIDEO)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Set cover…") },
                                leadingIcon = {
                                    Icon(Icons.Default.Image, contentDescription = null)
                                },
                                onClick = {
                                    showSelectionMenu = false
                                    pickCoverForSelection()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove cover") },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                },
                                // The counterpart to Set. It clears the cover on the event these
                                // recordings share — the same place Set put it, so the pair are
                                // symmetrical rather than one of them acting somewhere else.
                                onClick = {
                                    showSelectionMenu = false
                                    viewModel.clearCoverForSelection(context) { message ->
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Merge into one") },
                                leadingIcon = {
                                    Icon(Icons.Default.Timeline, contentDescription = null)
                                },
                                // Two or more of one person. The menu says why when it cannot,
                                // rather than offering an item that silently does nothing.
                                enabled = selectedRecords.size > 1,
                                onClick = {
                                    showSelectionMenu = false
                                    showMergeDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export image") },
                                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    onExportSelection(selectedRecords, ExportKind.IMAGE)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export CSV") },
                                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    recordIdsToExport =
                                        selectedRecords.map { it.metadata.recordId }
                                    chooseFolderLauncher.launch(null)
                                    exitSelection()
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
                                    exitSelection()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share as .bpmjson") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    showSelectionMenu = false
                                    // People, watches and categories go with the records, or the
                                    // file restores recordings that belong to nobody.
                                    // Readings loaded for the chosen recordings at the moment of
                                    // sharing. A share is the file's whole content, so it needs
                                    // them — but only for what was picked.
                                    val chosen = selectedRecords.map { it.metadata.recordId }
                                    exportScope.launch {
                                        JsonExporter.shareJson(
                                            context = context,
                                            records = viewModel.repository
                                                .recordsWithPoints(chosen),
                                            people = availablePeopleForBackup,
                                            watches = watches,
                                            categories = categoriesForBackup
                                        )
                                    }
                                    exitSelection()
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
                        // Sorting and filtering live here rather than in a row of their own.
                        // The list had four bands of chrome above the first recording — app bar,
                        // view switcher, a sort/filter row, and section header — on a screen whose
                        // entire job is showing a list. This is the row that had to go, and the
                        // app bar is where every other list app puts these.
                        //
                        // None of them are conditional any more: there is one list, and all three
                        // act on it.
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Sort recordings"
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                LibraryViewModel.SortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        // The shape is named, because the list changing shape
                                        // under you is the one surprising thing here. Sorting
                                        // by time keeps the events; sorting by peak cannot,
                                        // since a tree is only meaningful when the ordering key
                                        // is the one that nests.
                                        text = {
                                            Text("${option.label} — ${option.shapeLabel}")
                                        },
                                        trailingIcon = {
                                            if (option == currentSort) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSortOption(option)
                                            showSortMenu = false
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Reverse order") },
                                    leadingIcon = {
                                        Icon(Icons.Default.SwapVert, contentDescription = null)
                                    },
                                    onClick = {
                                        viewModel.toggleReverse()
                                        showSortMenu = false
                                    }
                                )
                            }
                        }

                        val filtered = !filterState.isEmpty
                        IconButton(onClick = { showFilterBar = !showFilterBar }) {
                            Icon(
                                // The icon carries the state, so an active filter is visible
                                // without a row explaining it.
                                // The same icon either way, tinted when active. FilterAltOff is a
                                // crossed-out glyph, which reads as "filtering is unavailable"
                                // rather than "no filter set", and it all but vanished against
                                // the dark bar.
                                Icons.Default.FilterAlt,
                                contentDescription = if (filtered) {
                                    "Filters active — edit them"
                                } else {
                                    "Filter recordings"
                                },
                                tint = if (filtered) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }

                        IconButton(onClick = { selectionArmed = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = "Select recordings")
                        }

                        // Four, which the bar holds comfortably. Collections left this menu
                        // entirely when it became a tab of its own — it was never a library
                        // action, and a door to another section does not belong beside the
                        // controls for the list in front of you.
                        IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            // Opening a file, not downloading one. FileDownload reads as "fetch
                            // from somewhere", which is the opposite of what this does.
                            Icon(Icons.Default.FileOpen, contentDescription = "Import recordings")
                        }
                    }
                )
            }
        },
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
                        "Tap Select in the bar above — or press and hold a recording — to pick the " +
                            "ones made at the same time, then choose Analyse together.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Directly above the list it narrows, in whichever shape that list is currently in.
            //
            // Shown whenever anything is applied, whether or not it was opened. A filter you
            // cannot see is the thing that made the dialog wrong — you would scroll a library
            // wondering where half of it went.
            if (showFilterBar || !filterState.isEmpty || savedViews.isNotEmpty()) {
                FilterBar(
                    query = filterState.query,
                    chips = filterChips,
                    options = filterOptions,
                    onQueryChange = { viewModel.setQuery(it) },
                    onRemoveChip = { viewModel.removeChip(it) },
                    onAdd = { dimension, id -> viewModel.addFilterTerm(dimension, id) },
                    onClearAll = { viewModel.clearFilter() },
                    onAnalyse = { onAnalyseFilter(filterState) },
                    savedViews = savedViews,
                    activeViewId = activeViewId,
                    onApplyView = { viewModel.applyView(it) },
                    onSaveView = { viewModel.saveCurrentAsView(it) },
                    onUnpinView = { viewModel.unpinView(it) }
                )
                Spacer(Modifier.height(4.dp))
            }

            // A tile, wherever one appears — in the flat list, inside an event, or under a
            // collection. Selection and navigation behave the same in all three, which is what
            // makes press-and-hold-then-file work from anywhere.
            val tile: @Composable (BpmRecord) -> Unit = { record ->
                val isSelected = selectedRecordIds.contains(record.metadata.recordId)
                BpmRecordTile(
                    record = record,
                    isSelected = isSelected,
                    watchName = record.metadata.watchId?.let { watchNames[it] },
                    wearer = record.metadata.personId?.let { peopleById[it] },
                    // Resolved for the whole library at once rather than walked per row — see
                    // LibraryViewModel.coversByRecord.
                    cover = coversByRecord[record.metadata.recordId],
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

            // One list, and the sort decides its shape. Chronology is the only ordering the tree
            // means anything under — a festival is a stretch of time containing stretches of time —
            // so sorting by time draws the events, and every other ordering draws the recordings
            // flat. "Which set went hardest" is a real question, and it is a list of recordings.
            //
            // These were two views over the same recordings until now, reached by a segmented
            // control, and the duplication did what duplication does: the timeline read the whole
            // library while the flat list read the filtered one, so the filter bar did nothing at
            // all in the view the app opens on.
            val nothingMatched = !filterState.isEmpty &&
                if (currentSort.isGrouped) timeline.isEmpty() else uiState.records.isEmpty()

            when {
                // Distinct from an empty library, which the two lists below say for themselves and
                // which has no action worth offering. This one does.
                nothingMatched -> EmptyLibrary(
                    title = "Nothing matches this filter",
                    message = "Try widening it, or clear it to see everything again.",
                    action = "Clear filters",
                    onAction = { viewModel.clearFilters() }
                )

                currentSort.isGrouped -> Column(Modifier.fillMaxSize()) {
                    // Above the list rather than in the app bar: the app bar already belongs to the
                    // recording selection, and two selections competing for one bar is how you end
                    // up acting on the wrong one.
                    if (selectedEventIds.isNotEmpty()) {
                        EventSelectionBar(
                            count = selectedEventIds.size,
                            onMove = { movingSelectedEvents = true },
                            onAddToCollection = { addingSelectedEventsToCollection = true },
                            onClear = { viewModel.clearEventSelection() }
                        )
                    }
                    TimelineList(
                        rows = timeline,
                        summaries = eventSummariesById,
                        // The rows carry entities; a tile wants the whole recording with readings.
                        recordsById = remember(uiState.records) {
                            uiState.records.associateBy { it.metadata.recordId }
                        },
                        eventCovers = coversByEvent,
                        placeNames = placeNames,
                        peopleById = peopleById,
                        expandedIds = timelineExpanded,
                        onToggleExpand = { viewModel.toggleTimelineExpansion(it) },
                        selectedEventIds = selectedEventIds,
                        onToggleEventSelection = { viewModel.toggleEventSelection(it) },
                        onCreateEvent = { showCreateEventDialog = true },
                        onOpenEvent = { navController.navigate("${Routes.EVENT_DETAIL}/$it") },
                        onEdit = { renamingEvent = it },
                        onMoveToGroup = { movingEvent = it },
                        onAddToCollection = { addingEventToCollection = it },
                        onDelete = { deletingEvent = it },
                        tile = tile
                    )
                }

                uiState.records.isEmpty() -> EmptyLibrary(
                    title = "No recordings yet",
                    message = "Start one on a watch. Recordings arrive here when the watch and " +
                        "phone are next connected."
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.records) { record -> tile(record) }
                }
            }
        }
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


    if (showMergeDialog) {
        MergeSelectionDialog(
            records = selectedRecordsForBulk,
            people = peopleById,
            onDismiss = { showMergeDialog = false },
            onMerge = { deleteOriginals ->
                viewModel.mergeSelectedRecords(deleteOriginals) { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                showMergeDialog = false
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
            categories = categories,
            getTagsByCategoryFlow = { viewModel.repository.getTagsByCategory(it) },
            onCreateTag = { axis, name, onMade -> viewModel.createTag(axis, name, onMade) },
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
            rows = eventPickerRows,
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
        val selected = selectedRecordIds
        val knownTypes by viewModel.eventTypesInUse.collectAsStateWithLifecycle()
        val windowError by viewModel.windowError.collectAsStateWithLifecycle()

        // The same editor as afterwards, rather than a name field and a trip back to fix everything
        // else. "A new set inside Day 1, nine till half ten" is one thought.
        val chosen = remember(selected, uiState.records) {
            uiState.records.filter { it.metadata.recordId in selected }
        }
        EventEditorDialog(
            initialName = "",
            knownTypes = knownTypes,
            people = availablePeopleForBackup,
            // From the recordings being filed into it, where there are any — the same rule as
            // editing, applied to what the event is about to contain rather than what it holds.
            suggestedStart = chosen.minOfOrNull { it.metadata.startTime }?.takeIf { forSelection },
            suggestedEnd = chosen.maxOfOrNull { it.metadata.endTime }?.takeIf { forSelection },
            parentOptions = eventPickerRows,
            locations = knownLocations,
            collisionError = windowError,
            onDismiss = {
                showCreateEventDialog = false
                namingEventForSelection = false
                viewModel.clearWindowError()
            },
            onConfirm = { edit ->
                viewModel.createEvent(edit, if (forSelection) selected else emptySet()) { done ->
                    if (done) {
                        showCreateEventDialog = false
                        namingEventForSelection = false
                    }
                }
            }
        )
    }

    if (showCreateGroupDialog) {
        NameDialog(
            title = "New collection",
            label = "Collection name",
            confirmLabel = "Create",
            supporting = "A collection gathers things that belong together but did not happen " +
                "together — every festival, or everything with Kyle. Whatever you add stays " +
                "exactly where it is on the timeline.",
            onDismiss = { showCreateGroupDialog = false },
            onConfirm = { name ->
                viewModel.createCollection(name)
                showCreateGroupDialog = false
            }
        )
    }

    renamingEvent?.let { summary ->
        // Name, type and window together. They used to be a rename dialog and nothing else, so a
        // window — the thing that actually decides what the event contains — could not be set at all.
        val knownTypes by viewModel.eventTypesInUse.collectAsStateWithLifecycle()
        val windowError by viewModel.windowError.collectAsStateWithLifecycle()
        val windowPeople by remember(summary.event.eventId) {
            viewModel.windowPeople(summary.event.eventId)
        }.collectAsStateWithLifecycle(initialValue = emptySet())
        val inheritedForEditing by remember(summary.event.eventId) {
            viewModel.inheritedLocationName(summary.event.eventId, null)
        }.collectAsStateWithLifecycle(initialValue = null)
        val editingZone by remember(summary.event.eventId) {
            viewModel.windowZone(summary.event.eventId)
        }.collectAsStateWithLifecycle(initialValue = java.util.TimeZone.getDefault())

        EventEditorDialog(
            initialName = summary.event.name,
            initialType = summary.event.type,
            initialStart = summary.event.windowStart,
            initialEnd = summary.event.windowEnd,
            initialPeople = windowPeople,
            // The span of what it already holds, so switching a window on starts from the truth
            // rather than from today.
            suggestedStart = summary.span?.startMs,
            suggestedEnd = summary.span?.endMs,
            initialLocationId = summary.event.locationId,
            locations = knownLocations,
            inheritedLocationName = inheritedForEditing,
            zone = editingZone,
            knownTypes = knownTypes,
            people = availablePeopleForBackup,
            collisionError = windowError,
            onDismiss = {
                renamingEvent = null
                viewModel.clearWindowError()
            },
            onConfirm = { edit ->
                // Stays open when the window is refused, so the message lands next to the dates
                // that caused it rather than after the dialog has gone.
                viewModel.applyEventEdit(summary.event.eventId, edit) { done ->
                    if (done) renamingEvent = null
                }
            }
        )
    }

    movingEvent?.let { summary ->
        EventParentPickerDialog(
            moving = summary.event,
            rows = eventPickerRows,
            onDismiss = { movingEvent = null },
            onPick = { parentId ->
                viewModel.setEventGroup(summary.event.eventId, parentId)
                movingEvent = null
            },
            onCreateEvent = {
                movingEvent = null
                showCreateEventDialog = true
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

    // --- Collections, as sets ---

    if (addingSelectionToCollection) {
        AddToCollectionDialog(
            count = selectedRecordIds.size,
            collections = collections,
            onDismiss = { addingSelectionToCollection = false },
            onPick = {
                viewModel.addSelectionToCollection(it)
                addingSelectionToCollection = false
            },
            onCreate = {
                addingSelectionToCollection = false
                showCreateGroupDialog = true
            }
        )
    }

    if (movingSelectedEvents) {
        EventParentPickerDialog(
            // A stand-in for the group being moved: the picker needs something to exclude, and with
            // several events there is no single self. Nothing is excluded, and each move is guarded
            // individually in the repository — which is where the real check has to live anyway.
            moving = inga.bpmetrics.library.EventEntity(
                eventId = -1L,
                name = "${selectedEventIds.size} events"
            ),
            rows = eventPickerRows.filterNot { (event, _) -> event.eventId in selectedEventIds },
            onDismiss = { movingSelectedEvents = false },
            onPick = { parentId ->
                movingSelectedEvents = false
                viewModel.moveSelectedEventsInto(parentId) { moved, refused ->
                    if (refused > 0) {
                        Toast.makeText(
                            context,
                            "Moved $moved. $refused could not go there without nesting inside itself.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onCreateEvent = {
                movingSelectedEvents = false
                showCreateEventDialog = true
            }
        )
    }

    if (addingSelectedEventsToCollection) {
        AddToCollectionDialog(
            count = selectedEventIds.size,
            noun = "event",
            collections = collections,
            onDismiss = { addingSelectedEventsToCollection = false },
            onPick = {
                viewModel.addSelectedEventsToCollection(it)
                addingSelectedEventsToCollection = false
            },
            onCreate = {
                addingSelectedEventsToCollection = false
                showCreateGroupDialog = true
            }
        )
    }

    addingEventToCollection?.let { summary ->
        // Ticks rather than a single pick: an event can be in any number of sets at once, which is
        // the whole difference between a set and where it lives. A picker that closed on the first
        // tap would make "Festivals and 2026" three gestures instead of two.
        val member by remember(summary.event.eventId) {
            viewModel.collectionsHoldingEvent(summary.event.eventId)
        }.collectAsStateWithLifecycle(initialValue = emptySet())

        AlertDialog(
            onDismissRequest = { addingEventToCollection = null },
            title = { Text("Collections for ${summary.event.displayName}") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (collections.isEmpty()) {
                        Text(
                            "No collections yet. Make one and this event can go in it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    collections.forEach { set ->
                        val inSet = set.collection.collectionId in member
                        DropdownMenuItem(
                            text = { Text(set.collection.displayName) },
                            trailingIcon = {
                                if (inSet) Icon(Icons.Default.Check, contentDescription = "In it")
                            },
                            onClick = {
                                viewModel.toggleEventInCollection(
                                    set.collection.collectionId,
                                    summary.event.eventId,
                                    member = !inSet
                                )
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("New collection…") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        onClick = {
                            addingEventToCollection = null
                            showCreateGroupDialog = true
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { addingEventToCollection = null }) { Text("Done") }
            }
        )
    }
}

/**
 * Puts the current selection into a set.
 *
 * Deliberately additive: no "remove from collection" here, because a recording can be in several at
 * once and this dialog has no way to know which one someone meant. Removing is done from the set,
 * where the question is unambiguous.
 */
@Composable
private fun AddToCollectionDialog(
    count: Int,
    noun: String = "recording",
    collections: List<CollectionSummary>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onCreate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $count $noun${if (count == 1) "" else "s"} to…") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                DropdownMenuItem(
                    text = { Text("New collection…") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = onCreate
                )
                if (collections.isNotEmpty()) HorizontalDivider()
                collections.forEach { summary ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(summary.collection.displayName)
                                Text(
                                    countLabelPublic(summary.recordCount, "recording"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = { onPick(summary.collection.collectionId) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// EmptySection moved to ui/components as BpmEmptySection: three screens were writing their own,
// and an empty state is exactly the kind of thing that should read the same everywhere.
/**
 * The whole-screen version, for a list with nothing in it at all.
 *
 * Wraps the shared component with the Library icon, so the wording is the only thing this screen
 * still decides.
 */
@Composable
private fun EmptyLibrary(
    title: String,
    message: String,
    /** Omitted when there is nothing useful to offer — an empty library has no remedy. */
    action: String? = null,
    onAction: () -> Unit = {}
) {
    inga.bpmetrics.ui.components.BpmEmptyState(
        icon = Icons.AutoMirrored.Filled.LibraryBooks,
        title = title,
        body = message,
        actionLabel = action,
        onAction = onAction.takeIf { action != null }
    )
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

/**
 * Joining a selection of recordings into one.
 *
 * Lives here rather than on a recording's own page, where it briefly did: merging is inherently
 * about *several* recordings, and asking one of them to nominate the others meant building a picker
 * that duplicated the multi-select the library already has.
 *
 * The dialog's job is to say what will happen before it does. Merging is not reversible once the
 * originals are gone, and the two ways to get it wrong — joining different people, or joining two
 * sets an hour apart without meaning to — are both invisible afterwards.
 */
@Composable
private fun MergeSelectionDialog(
    records: List<BpmRecord>,
    people: Map<Long, inga.bpmetrics.library.PersonEntity>,
    onDismiss: () -> Unit,
    onMerge: (deleteOriginals: Boolean) -> Unit
) {
    var deleteOriginals by remember { mutableStateOf(true) }

    val refusal = inga.bpmetrics.library.RecordMerge.refusal(records.map { it.metadata })
    val ordered = records.sortedBy { it.metadata.startTime }
    val personName = ordered.firstOrNull()?.metadata?.personId?.let { people[it]?.displayName }
    val gapMs = if (refusal == null) inga.bpmetrics.library.RecordMerge.gapMs(records.map { it.metadata }) else 0L
    val span = if (ordered.isEmpty()) 0L else {
        ordered.maxOf { it.metadata.startTime + it.metadata.durationMs } -
            ordered.minOf { it.metadata.startTime }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge ${records.size} recordings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (refusal != null) {
                    Text(refusal, style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }

                Text(
                    "Joins these into one recording of ${personName ?: "this person"}, in clock " +
                        "order. The gaps between them are kept, so the timeline stays true.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                ordered.forEach { record ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                record.displayName(personName),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${getTimeString(record.metadata.startTime, record.clock)} · " +
                                    inga.bpmetrics.ui.analysis.shortDuration(record.metadata.durationMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "The result spans ${inga.bpmetrics.ui.analysis.shortDuration(span)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                if (gapMs > 60_000L) {
                    // Joining two sets an hour apart is a legitimate thing to want and also an
                    // easy mistake. Said before the merge rather than discovered after it.
                    Text(
                        "${inga.bpmetrics.ui.analysis.shortDuration(gapMs)} of that has no " +
                            "readings — the time between them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = deleteOriginals,
                        onCheckedChange = { deleteOriginals = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Delete the originals", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    // The merged copy is written before anything is removed, so a failure halfway
                    // cannot leave someone with neither.
                    if (deleteOriginals) {
                        "The merged recording is saved before anything is deleted."
                    } else {
                        "You will have both the parts and the merged copy."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onMerge(deleteOriginals) },
                enabled = refusal == null
            ) { Text("Merge") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
