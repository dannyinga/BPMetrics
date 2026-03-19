package inga.bpmetrics.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import inga.bpmetrics.ui.Routes
import inga.bpmetrics.ui.analysis.AnalysisFilterDialog
import inga.bpmetrics.ui.detail.BpmRecordTile
import inga.bpmetrics.ui.detail.ExportHelpers
import inga.bpmetrics.ui.theme.BpmAccent

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
    val context = LocalContext.current

    var showSortMenu by remember { mutableStateOf(false) }
    var showAnalyzeMenu by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var navigateToAnalysisOnFilterApply by remember { mutableStateOf(false) }

    // Launcher for importing a CSV file
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val watchRecord = ExportHelpers.importFromCsv(context, it)
            if (watchRecord != null) {
                viewModel.importRecord(watchRecord)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) },
                actions = {
                    // Import CSV button
                    IconButton(onClick = { importLauncher.launch(arrayOf("text/comma-separated-values", "text/csv")) }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Import CSV")
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
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
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
                        Icon(Icons.Default.Sort, null, Modifier.size(18.dp))
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
                    BpmRecordTile(
                        record,
                        onClick = { navController.navigate("detail/${record.metadata.recordId}") })
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
}

// Extension to capitalize first letter since String.capitalize() is deprecated
fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
