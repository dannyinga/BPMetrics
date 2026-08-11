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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Insights
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
    /** Analyses the library as currently filtered — the question, as its own scope. */
    onAnalyseFilter: (inga.bpmetrics.library.FilterState) -> Unit = {},
    /** Analyses a hand-picked set, which is a scope like any other. */
    onAnalyseSelection: (Set<Long>) -> Unit = {}
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
    val selectedEventIdsForMode by viewModel.selectedEventIds.collectAsStateWithLifecycle()
    // Every recording the selection covers, a chosen event contributing its subtree. See
    // [LibraryViewModel.selectedRecordIdsEffective].
    val effectiveRecordIds by viewModel.selectedRecordIdsEffective.collectAsStateWithLifecycle()

    // One mode for both kinds. There used to be two selections running side by side — recordings
    // in the app bar, events in a strip above the list — each with its own count, its own exit and
    // its own back handler, and no way to express "these two sets and that one recording", which
    // is an entirely ordinary thing to want.
    val isSelectionMode =
        selectedRecordIds.isNotEmpty() || selectedEventIdsForMode.isNotEmpty() || selectionArmed

    // One way out, so no exit can clear the selection and leave the mode armed behind it — which
    // would look like the screen ignoring the close button.
    val exitSelection = {
        selectionArmed = false
        viewModel.clearWholeSelection()
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
    var showLibraryMenu by remember { mutableStateOf(false) }
    var showBulkEdit by remember { mutableStateOf(false) }
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
    val selectedEventIds = selectedEventIdsForMode
    var movingSelectedEvents by remember { mutableStateOf(false) }
    var addingSelectedEventsToCollection by remember { mutableStateOf(false) }
    val unfiled by viewModel.unfiledRecords.collectAsStateWithLifecycle()

    var showCreateEventDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var renamingEvent by remember { mutableStateOf<EventSummary?>(null) }
    // The event whose photo the row editor is choosing, and whether the framing sheet is up. Held
    // at screen level because a picker registered inside a dialog is gone by the time the gallery
    // comes back.
    var framingRowCover by remember { mutableStateOf(false) }
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
                            when {
                                selectedRecordIds.isEmpty() && selectedEventIds.isEmpty() ->
                                    "Select"
                                selectedEventIds.isEmpty() ->
                                    "${selectedRecordIds.size} selected"
                                selectedRecordIds.isEmpty() ->
                                    "${selectedEventIds.size} event" +
                                        if (selectedEventIds.size == 1) "" else "s"
                                // Both kinds, said as both. One number would be a lie about what
                                // the actions below are about to act on.
                                else -> "${selectedEventIds.size} event" +
                                    (if (selectedEventIds.size == 1) "" else "s") +
                                    " · ${selectedRecordIds.size} recording" +
                                    if (selectedRecordIds.size == 1) "" else "s"
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
                        // Through the effective set, so a selected *event* contributes its whole
                        // subtree. Every action here is an action on recordings; which kind of
                        // row was tapped to name them is not something they should care about.
                        val selectedRecords = uiState.records.filter {
                            it.metadata.recordId in effectiveRecordIds
                        }
                        val hasSelection = effectiveRecordIds.isNotEmpty()
                        val hasEvents = selectedEventIds.isNotEmpty()

                        IconButton(onClick = { viewModel.selectAll(uiState.records) }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }

                        // Editing the recordings, all of it, behind one door. This menu held
                        // thirteen items, most of which were not actions on a selection but edits
                        // to the things in it — each having earned a row as it was added.
                        if (hasSelection) {
                            IconButton(onClick = { showBulkEdit = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit selected")
                            }
                        }

                        // One Analyse, whatever was picked. A hand-chosen set is a scope like any
                        // other now — §8.3 — so it opens the same detail page an event or a
                        // collection does, and the old rule about refusing unless the recordings
                        // overlapped went with the screen that needed it.
                        if (hasSelection) {
                            IconButton(onClick = { onAnalyseSelection(effectiveRecordIds) }) {
                                Icon(Icons.Default.Insights, contentDescription = "Analyse")
                            }
                        }

                        Box {
                            IconButton(onClick = { showSelectionMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showSelectionMenu,
                                onDismissRequest = { showSelectionMenu = false }
                            ) {
                                // Filing, back where it can be reached. It was three levels down —
                                // bulk edit, then "File into an event…", then the picker — which
                                // is a long way to go for the most ordinary thing anyone does
                                // with a fresh selection.
                                DropdownMenuItem(
                                    text = { Text("Add to an event…") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.PlaylistAdd,
                                            contentDescription = null
                                        )
                                    },
                                    enabled = hasSelection,
                                    onClick = {
                                        showSelectionMenu = false
                                        showAddToEventDialog = true
                                    }
                                )

                                // What is left is what is not an edit: joining recordings into
                                // one, getting them out of the app, and destroying them.
                                DropdownMenuItem(
                                    text = { Text("Merge into one") },
                                    leadingIcon = {
                                        Icon(Icons.Default.CallMerge, contentDescription = null)
                                    },
                                    enabled = selectedRecordIds.size > 1,
                                    onClick = {
                                        showSelectionMenu = false
                                        showMergeDialog = true
                                    }
                                )

                                // The two things only an event can be. They were a separate strip
                                // above the list with its own count and its own close button.
                                if (hasEvents) {
                                    DropdownMenuItem(
                                        text = { Text("Move events into…") },
                                        onClick = {
                                            showSelectionMenu = false
                                            movingSelectedEvents = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add events to a collection…") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Bookmarks, contentDescription = null)
                                        },
                                        onClick = {
                                            showSelectionMenu = false
                                            addingSelectedEventsToCollection = true
                                        }
                                    )
                                }

                                HorizontalDivider()

                                // Two formats, both files. Video and image left this menu when the
                                // detail page gained a single Export button — a picture of a
                                // recording is made from the page showing that recording, where
                                // what it will look like is on screen.
                                DropdownMenuItem(
                                    text = { Text("Export CSV") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Save, contentDescription = null)
                                    },
                                    enabled = hasSelection,
                                    onClick = {
                                        showSelectionMenu = false
                                        recordIdsToExport =
                                            selectedRecords.map { it.metadata.recordId }
                                        chooseFolderLauncher.launch(null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Save backup (.bpmjson)") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Save, contentDescription = null)
                                    },
                                    enabled = hasSelection,
                                    onClick = {
                                        showSelectionMenu = false
                                        recordsToBackUp = selectedRecords
                                        saveBackupLauncher.launch(
                                            "BPMetrics_Backup_" +
                                                System.currentTimeMillis() + ".bpmjson"
                                        )
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
                                    enabled = hasSelection,
                                    onClick = {
                                        showSelectionMenu = false
                                        showBulkDeleteDialog = true
                                    }
                                )
                            }
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

                        // Sort and filter are the two controls over the list you are looking at,
                        // and they stay on the bar. The rest are things you *do* — start
                        // picking, bring recordings in, file them somewhere — and they were four
                        // icons deep on a bar that also has to hold a title.
                        Box {
                            IconButton(onClick = { showLibraryMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showLibraryMenu,
                                onDismissRequest = { showLibraryMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Checklist, contentDescription = null)
                                    },
                                    onClick = { showLibraryMenu = false; selectionArmed = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("New event…") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                    },
                                    // Creating an empty one, which is a library action. Filing an
                                    // existing selection into an event is not — it needs the
                                    // selection — so it lives in the selection's own overflow.
                                    onClick = {
                                        showLibraryMenu = false
                                        namingEventForSelection = false
                                        showCreateEventDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Import recordings…") },
                                    leadingIcon = {
                                        // Opening a file, not downloading one. FileDownload reads
                                        // as "fetch from somewhere", the opposite of this.
                                        Icon(Icons.Default.FileOpen, contentDescription = null)
                                    },
                                    onClick = {
                                        showLibraryMenu = false
                                        importLauncher.launch(arrayOf("*/*"))
                                    }
                                )
                            }
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
                        "Tap Select in the bar above — or press and hold a recording — to pick " +
                            "the ones you want to compare, then tap Analyse.",
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
                    filter = filterState,
                    chips = filterChips,
                    options = filterOptions,
                    onQueryChange = { viewModel.setQuery(it) },
                    onChange = { viewModel.setFilter(it) },
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
                    // The strip that used to sit here is gone: events and recordings are one
                    // selection now and it lives in the app bar, which is where a selection
                    // belongs and where the other half of it already was.
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
                        selectionMode = isSelectionMode,
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

    // The edits, behind one door. Each row opens the picker that already exists for it — the
    // selection menu used to hold all of them flat, and grew a row every time a recording gained
    // a property.
    if (showBulkEdit) {
        BulkEditDialog(
            count = selectedRecordIds.size,
            hasCover = selectedRecordsForBulk.any { coversByRecord[it.metadata.recordId] != null },
            onTags = { showBulkEdit = false; showBulkTagDialog = true },
            onAttribute = { showBulkEdit = false; showBulkWearerDialog = true },
            onFileIntoEvent = { showBulkEdit = false; showAddToEventDialog = true },
            onAddToCollection = { showBulkEdit = false; addingSelectionToCollection = true },
            onSetCover = { showBulkEdit = false; pickCoverForSelection() },
            onRemoveCover = {
                showBulkEdit = false
                viewModel.clearCoverForSelection(context) { message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { showBulkEdit = false }
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
            recordCount = effectiveRecordIds.size,
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
        val selected = effectiveRecordIds
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
        NewCollectionDialog(
            filterOptions = filterOptions,
            chipsOf = { viewModel.chipsFor(it) },
            // Whatever is selected goes in as well, rule or no rule — the two combine.
            recordCount = effectiveRecordIds.size,
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { name, rule, pinned ->
                viewModel.createCollection(name, effectiveRecordIds, rule, pinned)
            }
        )
    }

    val pickRowCover = rememberCoverPicker { uri ->
        renamingEvent?.let { summary ->
            viewModel.setEventCover(context, summary.event.eventId, uri) { ok ->
                if (ok) framingRowCover = true
                else Toast.makeText(
                    context, "That image could not be read", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    renamingEvent?.let { summary ->
        // The one implementation, shared with the event's own page. This screen used to carry its
        // own copy of the same wiring — name, type, window, the people a window applies to, the
        // venue and the clock it implies — which is two answers to "edit an event" waiting to
        // drift apart. Sprint 5 gave the event a page; this is the page's editor, opened here.
        inga.bpmetrics.ui.detail.EventEditorLauncher(
            libraryViewModel = viewModel,
            event = summary.event,
            span = summary.span,
            // Tags stay on the event's own page: a count and a button say nothing without the
            // effective set, inherited tags included, to check them against. The photo does not
            // have that problem any more — choosing one opens the framing sheet, which is the
            // picture at the size the library will draw it.
            coverEditor = {
                val live = eventSummariesById[summary.event.eventId]?.event ?: summary.event
                inga.bpmetrics.ui.components.CoverEditor(
                    cover = live.ownCover,
                    onPick = pickRowCover,
                    framing = framingRowCover,
                    onFramingChange = { framingRowCover = it },
                    onCrop = { viewModel.setEventCoverCrop(live.eventId, it) },
                    onRemove = { viewModel.clearEventCover(context, live.eventId) },
                    title = "Frame ${live.displayName}",
                    previewContent = {
                        Text(
                            live.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                    }
                )
            },
            onDismiss = { renamingEvent = null; framingRowCover = false }
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
