package inga.bpmetrics.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.ui.library.LibraryViewModel
import kotlinx.coroutines.flow.*

/**
 * ViewModel for the single Analysis Screen.
 * 
 * It performs statistical analysis on a provided stream of filtered records.
 *
 * @param repository The repository for fetching static metadata like categories.
 * @param filteredRecords A flow of records that have already been filtered by the Library.
 * @param initialFilter Information about the current filter (for display text).
 */
class AnalysisViewModel(
    private val repository: LibraryRepository,
    filteredRecords: Flow<List<BpmRecord>>,
    initialFilter: LibraryViewModel.FilterState = LibraryViewModel.FilterState()
) : ViewModel() {

    private val _selectedMetric = MutableStateFlow(MetricType.HIGH)
    /**
     * The currently selected metric (LOW, AVG, HIGH) for sorting and display.
     */
    val selectedMetric = _selectedMetric.asStateFlow()

    private val _isRecordsReversed = MutableStateFlow(false)
    /**
     * Whether the records list should be reversed from its default metric-based sort.
     */
    val isRecordsReversed = _isRecordsReversed.asStateFlow()

    private val _isRankingsReversed = MutableStateFlow(false)
    /**
     * Whether the categorical rankings list should be reversed from its default metric-based sort.
     */
    val isRankingsReversed = _isRankingsReversed.asStateFlow()

    private val _selectedCategoryTabId = MutableStateFlow<Long?>(null)
    /**
     * The ID of the currently selected category tab for rankings.
     */
    val selectedCategoryTabId = _selectedCategoryTabId.asStateFlow()

    /**
     * Internal data class to bundle UI options for the combine transformation.
     */
    private data class AnalysisOptions(
        val metricType: MetricType,
        val recRev: Boolean,
        val rankRev: Boolean,
        val categoryId: Long?
    )

    /**
     * The primary UI state for the analysis screen.
     */
    val uiState: StateFlow<AnalysisUiState> = combine(
        filteredRecords,
        combine(_selectedMetric, _isRecordsReversed, _isRankingsReversed, _selectedCategoryTabId) { metric, recRev, rankRev, catId ->
            AnalysisOptions(metric, recRev, rankRev, catId)
        },
        repository.getAllCategories()
    ) { records, options, allCategories ->
        
        if (records.isEmpty()) {
            return@combine AnalysisUiState(isEmpty = true)
        }

        // Calculate Trio values: Absolute lowest min, Time-weighted Average, Absolute highest max
        val absoluteMin = records.mapNotNull { it.minDataPoint?.bpm }.minOrNull() ?: 0.0
        
        val totalDuration = records.sumOf { it.metadata.durationMs }
        val weightedSum = records.sumOf { (it.metadata.avg ?: 0.0) * it.metadata.durationMs }
        val timeWeightedAverage = if (totalDuration > 0L) weightedSum / totalDuration else 0.0
        
        val absoluteMax = records.mapNotNull { it.maxDataPoint?.bpm }.maxOrNull() ?: 0.0

        // Filtered Records based on the SELECTED metric
        var sortedRecords = when (options.metricType) {
            MetricType.LOW -> records.sortedBy { it.minDataPoint?.bpm ?: Double.MAX_VALUE }
            MetricType.AVG -> records.sortedByDescending { it.metadata.avg ?: 0.0 }
            MetricType.HIGH -> records.sortedByDescending { it.maxDataPoint?.bpm ?: 0.0 }
        }
        
        if (options.recRev) {
            sortedRecords = sortedRecords.reversed()
        }

        // Categorize filtered records for ranking analysis
        val categoryGroups = records
            .flatMap { record -> record.tags.map { it to record } }
            .groupBy({ it.first.parentCategoryId }, { it.first to it.second })
            .mapValues { (_, tagRecordPairs) ->
                tagRecordPairs.groupBy({ it.first }, { it.second })
            }

        // Only show tabs for categories that have more than one tag in the filtered results
        val filteredCategories = allCategories.filter { (categoryGroups[it.categoryId]?.size ?: 0) > 1 }

        // Determine which category is actually being viewed (fallback to first available if needed)
        val effectiveCategoryId = if (options.categoryId != null && filteredCategories.any { it.categoryId == options.categoryId }) {
            options.categoryId
        } else {
            filteredCategories.firstOrNull()?.categoryId
        }

        // Categorical Rankings for the effective category
        val rawRankings = if (effectiveCategoryId != null) {
            categoryGroups[effectiveCategoryId]?.map { (tag, groupRecords) ->
                // Identify the specific record that achieved the "Top" value for this tag
                val topRecord = when (options.metricType) {
                    MetricType.LOW -> groupRecords.minByOrNull { it.minDataPoint?.bpm ?: Double.MAX_VALUE }
                    MetricType.AVG -> groupRecords.maxByOrNull { it.metadata.avg ?: 0.0 }
                    MetricType.HIGH -> groupRecords.maxByOrNull { it.maxDataPoint?.bpm ?: 0.0 }
                }

                val value = when (options.metricType) {
                    MetricType.LOW -> groupRecords.mapNotNull { it.minDataPoint?.bpm }.minOrNull() ?: 0.0
                    MetricType.AVG -> {
                        val groupTotalDuration = groupRecords.sumOf { it.metadata.durationMs }
                        val groupWeightedSum = groupRecords.sumOf { (it.metadata.avg ?: 0.0) * it.metadata.durationMs }
                        if (groupTotalDuration > 0L) groupWeightedSum / groupTotalDuration else 0.0
                    }
                    MetricType.HIGH -> groupRecords.mapNotNull { it.maxDataPoint?.bpm }.maxOrNull() ?: 0.0
                }
                
                TagRankingWithRecord(tag.name, value, topRecord?.metadata?.recordId)
            } ?: emptyList()
        } else emptyList()

        // Apply default sorting: LOW -> Ascending, AVG/HIGH -> Descending
        var rankings = when (options.metricType) {
            MetricType.LOW -> rawRankings.sortedBy { it.averageBpm }
            else -> rawRankings.sortedByDescending { it.averageBpm }
        }

        if (options.rankRev) {
            rankings = rankings.reversed()
        }

        // --- ENHANCED FILTER DESCRIPTION LOGIC ---
        // Instead of showing all active tags from records, show only what the user EXPLICITLY selected.
        val selectedTagIds = initialFilter.selectedTagIds
        val selectedTags = records.flatMap { it.tags }.filter { it.tagId in selectedTagIds }.distinctBy { it.tagId }
        val selectedCategoryIds = selectedTags.map { it.parentCategoryId }.toSet()
        val selectedCategories = allCategories.filter { it.categoryId in selectedCategoryIds }

        AnalysisUiState(
            minTrio = absoluteMin.toInt(),
            avgTrio = timeWeightedAverage.toInt(),
            maxTrio = absoluteMax.toInt(),
            records = sortedRecords,
            categoricalRankings = rankings,
            availableCategories = filteredCategories,
            currentCategoryId = effectiveCategoryId,
            dateRangeText = if (initialFilter.dateRange == null) "All Time" else "Custom Range",
            categoriesText = if (selectedCategories.isEmpty()) "All" else selectedCategories.joinToString(", ") { it.name },
            tagsText = if (selectedTags.isEmpty()) "All" else selectedTags.joinToString(", ") { it.name },
            isEmpty = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalysisUiState())

    /**
     * Updates the selected metric for analysis.
     */
    fun setSelectedMetric(metric: MetricType) { _selectedMetric.value = metric }

    /**
     * Toggles the reverse state of the records list.
     */
    fun toggleRecordsReverse() { _isRecordsReversed.value = !_isRecordsReversed.value }

    /**
     * Toggles the reverse state of the categorical rankings list.
     */
    fun toggleRankingsReverse() { _isRankingsReversed.value = !_isRankingsReversed.value }

    /**
     * Updates the selected category tab.
     */
    fun setSelectedCategoryTab(categoryId: Long) { _selectedCategoryTabId.value = categoryId }

    /**
     * Types of heart rate metrics that can be used for analysis.
     */
    enum class MetricType { LOW, AVG, HIGH }

    /**
     * Factory class for creating instances of [AnalysisViewModel].
     */
    class Factory(
        private val repository: LibraryRepository,
        private val filteredRecords: Flow<List<BpmRecord>>,
        private val initialFilter: LibraryViewModel.FilterState = LibraryViewModel.FilterState()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AnalysisViewModel(repository, filteredRecords, initialFilter) as T
        }
    }
}

/**
 * Data representing the UI state of the Analysis Screen.
 */
data class AnalysisUiState(
    val minTrio: Int = 0,
    val avgTrio: Int = 0,
    val maxTrio: Int = 0,
    val records: List<BpmRecord> = emptyList(),
    val categoricalRankings: List<TagRankingWithRecord> = emptyList(),
    val availableCategories: List<CategoryEntity> = emptyList(),
    val currentCategoryId: Long? = null,
    val dateRangeText: String = "",
    val categoriesText: String = "",
    val tagsText: String = "",
    val isEmpty: Boolean = true
)

/**
 * Enhanced tag ranking data class that includes a reference to a specific record.
 * 
 * @property tagName The name of the tag.
 * @property averageBpm The value for this ranking (can be min, average, or max depending on context).
 * @property topRecordId The ID of the record that generated this specific value.
 */
data class TagRankingWithRecord(
    val tagName: String,
    val averageBpm: Double,
    val topRecordId: Long?
)
