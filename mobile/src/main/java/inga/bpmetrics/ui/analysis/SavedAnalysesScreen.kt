package inga.bpmetrics.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.SavedAnalysisEntity
import inga.bpmetrics.library.WatchEntity
import inga.bpmetrics.ui.library.LibraryFilterDialog
import inga.bpmetrics.ui.library.LibraryViewModel
import inga.bpmetrics.ui.util.ReaderClock
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import kotlinx.coroutines.flow.Flow

/**
 * The list of stored analyses, and the entry point for taking a new one.
 *
 * Each stored analysis is frozen at the moment it was saved, so this is a shelf of past findings
 * rather than a set of saved filters that would quietly re-answer themselves over time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedAnalysesScreen(
    savedAnalyses: Flow<List<SavedAnalysisEntity>>,
    repository: LibraryRepository,
    availablePeople: List<PersonEntity>,
    availableWatches: List<WatchEntity>,
    onOpenDrawer: () -> Unit,
    onOpen: (Long) -> Unit,
    onNewAnalysis: (LibraryViewModel.FilterState) -> Unit,
    onPickForConcurrentAnalysis: () -> Unit,
    onDelete: (Long) -> Unit
) {
    val analyses by savedAnalyses.collectAsStateWithLifecycle(initialValue = emptyList())
    var pendingDelete by remember { mutableStateOf<SavedAnalysisEntity?>(null) }
    var showScopeDialog by remember { mutableStateOf(false) }
    var showKindDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analysis") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showKindDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New analysis") }
            )
        }
    ) { padding ->
        if (analyses.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text("No saved analyses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Take a new analysis of your library, then save it with a name to keep " +
                            "its results exactly as they are today.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(analyses, key = { it.analysisId }) { analysis ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(analysis.analysisId) }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    analysis.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Saved ${getDateString(analysis.createdAt, ReaderClock)} at ${getTimeString(analysis.createdAt, ReaderClock)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (analysis.filterDescription.isNotBlank()) {
                                    Text(
                                        analysis.filterDescription,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            IconButton(onClick = { pendingDelete = analysis }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete analysis",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Two questions worth asking of the same recordings, so the kind is chosen before the scope.
    if (showKindDialog) {
        AlertDialog(
            onDismissRequest = { showKindDialog = false },
            title = { Text("What would you like to compare?") },
            text = {
                Column {
                    AnalysisKindOption(
                        title = "Compare recordings",
                        detail = "Highs, lows and averages across a set of recordings, ranked by " +
                            "wearer, watch or tag. You choose them with a filter. Good for a " +
                            "whole festival.",
                        onClick = {
                            showKindDialog = false
                            showScopeDialog = true
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    AnalysisKindOption(
                        title = "Compare at the same time",
                        detail = "Everyone's heart rate on one chart, with the moments you all " +
                            "reacted together. You pick the recordings by hand, because they " +
                            "have to be ones that overlapped. Good for one set.",
                        onClick = {
                            showKindDialog = false
                            onPickForConcurrentAnalysis()
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showKindDialog = false }) { Text("Cancel") } }
        )
    }

    // Choosing what to analyse before analysing it, rather than inheriting whatever the Library
    // happened to be filtered to.
    if (showScopeDialog) {
        LibraryFilterDialog(
            currentFilter = LibraryViewModel.FilterState(),
            onDismiss = { showScopeDialog = false },
            onApply = { filter ->
                showScopeDialog = false
                onNewAnalysis(filter)
            },
            repository = repository,
            availablePeople = availablePeople,
            availableWatches = availableWatches
        )
    }

    pendingDelete?.let { analysis ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${analysis.name}\"?") },
            text = { Text("The analysis is removed. Your recordings are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(analysis.analysisId)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

/** One choice of analysis, with enough detail to tell which question it answers. */
@Composable
private fun AnalysisKindOption(title: String, detail: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
