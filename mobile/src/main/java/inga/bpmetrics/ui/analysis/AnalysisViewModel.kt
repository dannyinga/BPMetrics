package inga.bpmetrics.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.ui.library.LibraryViewModel
import kotlinx.coroutines.flow.*

/**
 * ViewModel for the Analysis screen.
 *
 * Works from a stream of [AnalysisRecord] rather than library records, so the same statistics and
 * the same screen serve both a live analysis and one that was saved and frozen. Nothing here knows
 * which it is looking at.
 */
class AnalysisViewModel(
    analysisRecords: Flow<List<AnalysisRecord>>,
    private val savedAnalysisId: Long? = null,
    initialFilterDescription: FilterDescription = FilterDescription()
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

    /** True when viewing a stored analysis, which cannot be re-saved or re-filtered. */
    val isFrozen: Boolean = savedAnalysisId != null

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
     *
     * Every metric is derived from the record list on demand, which is what allows a saved
     * analysis to remain interactive — switching between low, average and high, or reversing a
     * sort, needs no access to the library.
     */
    val uiState: StateFlow<AnalysisUiState> = combine(
        analysisRecords,
        combine(_selectedMetric, _isRecordsReversed, _isRankingsReversed, _selectedCategoryTabId) { metric, recRev, rankRev, catId ->
            AnalysisOptions(metric, recRev, rankRev, catId)
        }
    ) { records, options ->

        if (records.isEmpty()) {
            return@combine AnalysisUiState(isEmpty = true, isFrozen = isFrozen)
        }

        // Calculate Trio values: Absolute lowest min, Time-weighted Average, Absolute highest max
        val absoluteMin = records.mapNotNull { it.minBpm }.minOrNull() ?: 0.0

        val totalActiveDuration = records.sumOf { it.activeDurationMs }
        val weightedSum = records.sumOf { (it.avgBpm ?: 0.0) * it.activeDurationMs }
        val timeWeightedAverage = if (totalActiveDuration > 0L) weightedSum / totalActiveDuration else 0.0

        val absoluteMax = records.mapNotNull { it.maxBpm }.maxOrNull() ?: 0.0

        // Filtered Records based on the SELECTED metric
        var sortedRecords = when (options.metricType) {
            MetricType.LOW -> records.sortedBy { it.minBpm ?: Double.MAX_VALUE }
            MetricType.AVG -> records.sortedByDescending { it.avgBpm ?: 0.0 }
            MetricType.HIGH -> records.sortedByDescending { it.maxBpm ?: 0.0 }
        }

        if (options.recRev) {
            sortedRecords = sortedRecords.reversed()
        }

        // Categorize records for ranking analysis
        val tagGroups = records
            .flatMap { record -> record.tags.map { it to record } }
            .groupBy({ it.first.categoryId }, { it.first to it.second })
            .mapValues { (_, tagRecordPairs) ->
                tagRecordPairs.groupBy({ it.first.tagName }, { it.second })
            }

        // Who and what recorded are comparisons in their own right, not tags, so they are offered
        // as categories alongside them — this is how one wearer is ranked against another.
        val wearerGroups = records
            .filter { it.wearerName.isNotBlank() }
            .groupBy { it.wearerName }
        val watchGroups = records
            .filter { it.watchName.isNotBlank() }
            .groupBy { it.watchName }

        val categoryGroups = buildMap {
            putAll(tagGroups)
            if (wearerGroups.size > 1) put(WEARER_CATEGORY_ID, wearerGroups)
            if (watchGroups.size > 1) put(WATCH_CATEGORY_ID, watchGroups)
        }

        // Categories come from the records themselves rather than the library, so a saved analysis
        // still shows the right tabs after a category has been renamed or removed.
        val tagCategoryNames = records
            .flatMap { it.tags }
            .associate { it.categoryId to it.categoryName }

        // Only offer a tab where there is more than one thing to compare
        val tagCategories = tagCategoryNames
            .filter { (id, _) -> (tagGroups[id]?.size ?: 0) > 1 }
            .map { (id, name) -> AnalysisCategory(id, name) }
            .sortedBy { it.name }

        // Wearer and Watch lead, being the comparisons a multi-watch session is usually about.
        val filteredCategories = buildList {
            if (wearerGroups.size > 1) add(AnalysisCategory(WEARER_CATEGORY_ID, "Wearer"))
            if (watchGroups.size > 1) add(AnalysisCategory(WATCH_CATEGORY_ID, "Watch"))
            addAll(tagCategories)
        }

        // Determine which category is actually being viewed (fallback to first available if needed)
        val effectiveCategoryId = if (options.categoryId != null && filteredCategories.any { it.categoryId == options.categoryId }) {
            options.categoryId
        } else {
            filteredCategories.firstOrNull()?.categoryId
        }

        // Categorical Rankings for the effective category
        val rawRankings = if (effectiveCategoryId != null) {
            categoryGroups[effectiveCategoryId]?.map { (tagName, groupRecords) ->
                // Identify the specific record that achieved the "Top" value for this tag
                val topRecord = when (options.metricType) {
                    MetricType.LOW -> groupRecords.minByOrNull { it.minBpm ?: Double.MAX_VALUE }
                    MetricType.AVG -> groupRecords.maxByOrNull { it.avgBpm ?: 0.0 }
                    MetricType.HIGH -> groupRecords.maxByOrNull { it.maxBpm ?: 0.0 }
                }

                val value = when (options.metricType) {
                    MetricType.LOW -> groupRecords.mapNotNull { it.minBpm }.minOrNull() ?: 0.0
                    MetricType.AVG -> {
                        val groupTotalActiveDuration = groupRecords.sumOf { it.activeDurationMs }
                        val groupWeightedSum = groupRecords.sumOf { (it.avgBpm ?: 0.0) * it.activeDurationMs }
                        if (groupTotalActiveDuration > 0L) groupWeightedSum / groupTotalActiveDuration else 0.0
                    }
                    MetricType.HIGH -> groupRecords.mapNotNull { it.maxBpm }.maxOrNull() ?: 0.0
                }

                TagRankingWithRecord(tagName, value, topRecord?.recordId)
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

        AnalysisUiState(
            minTrio = absoluteMin.toInt(),
            avgTrio = timeWeightedAverage.toInt(),
            maxTrio = absoluteMax.toInt(),
            records = sortedRecords,
            categoricalRankings = rankings,
            availableCategories = filteredCategories,
            currentCategoryId = effectiveCategoryId,
            dateRangeText = initialFilterDescription.dateRangeText,
            categoriesText = initialFilterDescription.categoriesText,
            tagsText = initialFilterDescription.tagsText,
            isEmpty = false,
            isFrozen = isFrozen
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalysisUiState(isFrozen = isFrozen))

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

    companion object {
        /**
         * Synthetic category ids for the comparisons that are not tags.
         *
         * Negative so they can never collide with a real category, whose ids are generated
         * positive by Room.
         */
        const val WEARER_CATEGORY_ID = -1L
        const val WATCH_CATEGORY_ID = -2L

        /**
         * A live analysis of the records matching [filter].
         *
         * Filtering happens here rather than reusing the Library's filtered stream, so choosing
         * what to analyse does not disturb what the Library is showing.
         */
        fun liveFactory(
            repository: LibraryRepository,
            filter: LibraryViewModel.FilterState
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val records = combine(
                    repository.records,
                    repository.getAllCategories(),
                    repository.getAllWatches(),
                    repository.getAllPeople()
                ) { library, categories, watches, people ->
                    AnalysisRecord.from(
                        LibraryViewModel.applyFilter(library, filter),
                        categories,
                        watches,
                        people
                    )
                }
                return AnalysisViewModel(
                    analysisRecords = records,
                    savedAnalysisId = null,
                    initialFilterDescription = describe(filter)
                ) as T
            }
        }

        /**
         * A stored analysis, rendered entirely from what was captured when it was saved.
         */
        fun savedFactory(
            repository: LibraryRepository,
            analysisId: Long
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val saved = flow { emit(repository.loadSavedAnalysis(analysisId)) }
                return AnalysisViewModel(
                    analysisRecords = saved.map { loaded ->
                        loaded?.records.orEmpty().map { AnalysisRecord.from(it) }
                    },
                    savedAnalysisId = analysisId,
                    initialFilterDescription = FilterDescription()
                ) as T
            }
        }

        /** Turns the Library's filter into the summary shown above an analysis. */
        private fun describe(filter: LibraryViewModel.FilterState) = FilterDescription(
            dateRangeText = if (filter.dateRange == null) "All Time" else "Custom Range",
            categoriesText = "All",
            tagsText = if (filter.selectedTagIds.isEmpty()) "All" else "${filter.selectedTagIds.size} selected"
        )
    }
}

/** Readable summary of what produced an analysis. */
data class FilterDescription(
    val dateRangeText: String = "All Time",
    val categoriesText: String = "All",
    val tagsText: String = "All"
)

/** A tag category present in the analysed records. */
data class AnalysisCategory(val categoryId: Long, val name: String)

/**
 * Data representing the UI state of the Analysis Screen.
 */
data class AnalysisUiState(
    val minTrio: Int = 0,
    val avgTrio: Int = 0,
    val maxTrio: Int = 0,
    val records: List<AnalysisRecord> = emptyList(),
    val categoricalRankings: List<TagRankingWithRecord> = emptyList(),
    val availableCategories: List<AnalysisCategory> = emptyList(),
    val currentCategoryId: Long? = null,
    val dateRangeText: String = "",
    val categoriesText: String = "",
    val tagsText: String = "",
    val isEmpty: Boolean = true,
    val isFrozen: Boolean = false
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
