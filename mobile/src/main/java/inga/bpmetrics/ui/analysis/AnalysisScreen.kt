package inga.bpmetrics.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import inga.bpmetrics.ui.theme.BpmAvg
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow

/**
 * The analysis screen, displaying aggregated statistics and rankings based on user filters.
 *
 * @param navController The navigation controller.
 * @param viewModel The [inga.bpmetrics.ui.analysis.AnalysisViewModel] providing the analytical data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(navController: NavController, viewModel: AnalysisViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedMetric by viewModel.selectedMetric.collectAsStateWithLifecycle()
    val selectedCategoryId by viewModel.selectedCategoryTabId.collectAsStateWithLifecycle()

    // Determine the theme color based on the selected metric using semantic colors from theme
    val themeColor = when (selectedMetric) {
        AnalysisViewModel.MetricType.LOW -> BpmLow
        AnalysisViewModel.MetricType.AVG -> BpmAvg
        AnalysisViewModel.MetricType.HIGH -> BpmHigh
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analysis View", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {}
            )
        }
    ) { paddingValues ->
        if (uiState.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No data available for current filter.")
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("categories: ${uiState.categoriesText}", style = MaterialTheme.typography.bodySmall)
                Text("tags: ${uiState.tagsText}", style = MaterialTheme.typography.bodySmall)
                Text("date range: ${uiState.dateRangeText}", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    AnalysisTrioItem(value = uiState.minTrio, color = BpmLow, isSelected = selectedMetric == AnalysisViewModel.MetricType.LOW) {
                        viewModel.setSelectedMetric(AnalysisViewModel.MetricType.LOW)
                    }
                    AnalysisTrioItem(value = uiState.avgTrio, color = BpmAvg, isSelected = selectedMetric == AnalysisViewModel.MetricType.AVG) {
                        viewModel.setSelectedMetric(AnalysisViewModel.MetricType.AVG)
                    }
                    AnalysisTrioItem(value = uiState.maxTrio, color = BpmHigh, isSelected = selectedMetric == AnalysisViewModel.MetricType.HIGH) {
                        viewModel.setSelectedMetric(AnalysisViewModel.MetricType.HIGH)
                    }
                }

                Spacer(Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 300.dp), 
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(onClick = { viewModel.toggleRecordsReverse() }) {
                                Icon(Icons.Default.SwapVert, null)
                            }
                            Text("Filtered Records", fontWeight = FontWeight.Bold)
                        }
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(uiState.records) { record ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                    navController.navigate("detail/${record.metadata.recordId}")
                                }, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(record.metadata.title, style = MaterialTheme.typography.bodyMedium)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.FavoriteBorder, null, tint = themeColor, modifier = Modifier.size(14.dp))
                                        val displayValue = when (selectedMetric) {
                                            AnalysisViewModel.MetricType.LOW -> record.minDataPoint?.bpm?.toInt() ?: 0
                                            AnalysisViewModel.MetricType.AVG -> record.metadata.avg?.toInt() ?: 0
                                            AnalysisViewModel.MetricType.HIGH -> record.maxDataPoint?.bpm?.toInt() ?: 0
                                        }
                                        Text("$displayValue", fontWeight = FontWeight.Bold, color = themeColor)
                                    }
                                }
                            }
                        }
                    }
                }

                // Only display Rankings section if there are categories with multiple tags to compare
                if (uiState.availableCategories.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))

                    val selectedTabIndex = uiState.availableCategories
                        .indexOfFirst { it.categoryId == selectedCategoryId }
                        .coerceAtLeast(0)

                    ScrollableTabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = Color.Transparent,
                        edgePadding = 0.dp,
                        divider = {}
                    ) {
                        uiState.availableCategories.forEach { category ->
                            Tab(
                                selected = selectedCategoryId == category.categoryId,
                                onClick = { viewModel.setSelectedCategoryTab(category.categoryId) },
                                text = { Text(category.name, maxLines = 1) }
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                IconButton(onClick = { viewModel.toggleRankingsReverse() }) {
                                    Icon(Icons.Default.SwapVert, null)
                                }
                                Text("Rankings", fontWeight = FontWeight.Bold)
                            }
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                uiState.categoricalRankings.forEach { ranking ->
                                    AnalysisBar(
                                        label = ranking.tagName,
                                        progress = (ranking.averageBpm / 200).toFloat().coerceIn(0f, 1f),
                                        value = ranking.averageBpm.toInt(),
                                        color = themeColor,
                                        onClick = ranking.topRecordId?.let { id ->
                                            { navController.navigate("detail/$id") }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
