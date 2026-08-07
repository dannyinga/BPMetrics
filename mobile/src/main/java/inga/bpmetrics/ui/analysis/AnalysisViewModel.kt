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
    scope: Flow<AnalysisScope> = flowOf(AnalysisScope.Unknown)
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
     * Which person is brought forward, or null for everyone.
     *
     * The same interaction as the event page's tap-to-isolate, applied to a list rather than a
     * chart: with eight people in a group, "which of these rows is Kyle" is the question the screen
     * spends most of its time answering.
     */
    private val _isolatedPersonId = MutableStateFlow<Long?>(null)
    val isolatedPersonId = _isolatedPersonId.asStateFlow()

    fun isolatePerson(personId: Long?) {
        _isolatedPersonId.value = if (personId == _isolatedPersonId.value) null else personId
    }

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
        },
        scope
    ) { records, options, currentScope ->

        if (records.isEmpty()) {
            return@combine AnalysisUiState(isEmpty = true, isFrozen = isFrozen, scope = currentScope)
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

        // Who, what recorded and what occasion are comparisons in their own right, not tags, so
        // they are offered as categories alongside them — this is how one wearer is ranked against
        // another, and one event against the next.
        val wearerGroups = records
            .filter { it.wearerName.isNotBlank() }
            .groupBy { it.wearerName }
        val watchGroups = records
            .filter { it.watchName.isNotBlank() }
            .groupBy { it.watchName }
        // The Event tab is what makes a group analysis answer "which set went hardest" — and
        // because it is derived from the records rather than from the scope, a filtered analysis
        // that happens to span several events gets the same answer without asking for it.
        val eventGroups = records
            .filter { it.eventName.isNotBlank() }
            .groupBy { it.eventName }

        val categoryGroups = buildMap {
            putAll(tagGroups)
            if (wearerGroups.size > 1) put(WEARER_CATEGORY_ID, wearerGroups)
            if (watchGroups.size > 1) put(WATCH_CATEGORY_ID, watchGroups)
            if (eventGroups.size > 1) put(EVENT_CATEGORY_ID, eventGroups)
        }

        // An event bar should open that event, which needs its id rather than its name.
        val eventIdsByName = records
            .filter { it.eventName.isNotBlank() }
            .associate { it.eventName to it.eventId }

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

        // Wearer and Event lead, being the comparisons a group is usually about; Watch is
        // provenance and matters less often; tags last, since there can be many.
        val filteredCategories = buildList {
            if (wearerGroups.size > 1) add(AnalysisCategory(WEARER_CATEGORY_ID, "Wearer"))
            if (eventGroups.size > 1) add(AnalysisCategory(EVENT_CATEGORY_ID, "Event"))
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

                TagRankingWithRecord(
                    tagName = tagName,
                    averageBpm = value,
                    topRecordId = topRecord?.recordId,
                    // An event bar opens the event; every other bar opens the recording that
                    // produced its number. Per §2.4 a ranking that cannot be followed is a
                    // dead end.
                    eventId = if (effectiveCategoryId == EVENT_CATEGORY_ID) {
                        eventIdsByName[tagName]
                    } else null,
                    recordCount = groupRecords.size
                )
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
            people = perPersonTotals(records),
            totalActiveDurationMs = totalActiveDuration,
            eventCount = eventGroups.size,
            dateRangeText = dateRangeText(records),
            scope = currentScope,
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
        const val EVENT_CATEGORY_ID = -3L

        /**
         * One row per person across the whole scope.
         *
         * The average is weighted by active time for the same reason the headline one is: someone
         * with a forty-second recording and someone with a three-hour one should not count equally
         * toward their own average, let alone toward each other's ranking.
         *
         * Grouped by name rather than id so a saved analysis — which stores names only — still
         * produces rows. The id comes along when it is there, for colour and for isolation.
         */
        internal fun perPersonTotals(records: List<AnalysisRecord>): List<PersonTotals> =
            records
                .filter { it.wearerName.isNotBlank() }
                .groupBy { it.wearerName }
                .map { (name, theirs) ->
                    val activeMs = theirs.sumOf { it.activeDurationMs }
                    val weighted = theirs.sumOf { (it.avgBpm ?: 0.0) * it.activeDurationMs }
                    PersonTotals(
                        personId = theirs.firstNotNullOfOrNull { it.personId },
                        name = name,
                        colorArgb = theirs.firstNotNullOfOrNull { it.personColorArgb },
                        recordCount = theirs.size,
                        minBpm = theirs.mapNotNull { it.minBpm }.minOrNull() ?: 0.0,
                        avgBpm = if (activeMs > 0L) weighted / activeMs else 0.0,
                        maxBpm = theirs.mapNotNull { it.maxBpm }.maxOrNull() ?: 0.0,
                        activeDurationMs = activeMs
                    )
                }
                .sortedByDescending { it.maxBpm }

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
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AnalysisViewModel(
                    analysisRecords = repository.analysisRecords { library ->
                        LibraryViewModel.applyFilter(library, filter)
                    },
                    savedAnalysisId = null,
                    scope = flowOf(AnalysisScope.Filter(filter))
                ) as T
        }

        /**
         * A live analysis of everything filed under a group.
         *
         * Deliberately the same ViewModel as a filtered analysis. A group and a filter are two ways
         * of naming a set of recordings, and the questions worth asking of that set — who went
         * hardest, which event was the peak, how do the tags compare — do not change with how it
         * was named. Two implementations would be two chances to answer differently.
         */
        fun groupFactory(
            repository: LibraryRepository,
            groupId: Long
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                // Membership is resolved before the mapping, not after. Reducing a record computes
                // its active duration, which walks every data point it has — doing that for the
                // whole library to keep a dozen recordings would be most of the work wasted.
                val records = combine(
                    repository.records,
                    repository.getAllCategories(),
                    repository.getAllWatches(),
                    repository.getAllPeople(),
                    repository.getAllEvents()
                ) { library, categories, watches, people, events ->
                    val ids = events.filter { it.groupId == groupId }.map { it.eventId }.toSet()
                    AnalysisRecord.from(
                        library.filter { it.metadata.eventId in ids },
                        categories,
                        watches,
                        people,
                        events
                    )
                }

                val scope = combine(
                    repository.getAllEventGroups(),
                    repository.getAllEvents()
                ) { groups, events ->
                    groups.firstOrNull { it.groupId == groupId }
                        ?.let { group ->
                            AnalysisScope.Group(group, events.count { it.groupId == groupId })
                        }
                        ?: AnalysisScope.Unknown
                }

                return AnalysisViewModel(
                    analysisRecords = records,
                    savedAnalysisId = null,
                    scope = scope
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
                    scope = saved.map { loaded ->
                        loaded?.metadata?.name
                            ?.let { AnalysisScope.Saved(it) }
                            ?: AnalysisScope.Unknown
                    }
                ) as T
            }
        }
    }
}

/**
 * Library records reduced to what an analysis needs, with names resolved live.
 *
 * @param select Which of the library's records to include.
 */
private fun LibraryRepository.analysisRecords(
    select: (List<inga.bpmetrics.library.BpmRecord>) -> List<inga.bpmetrics.library.BpmRecord>
): Flow<List<AnalysisRecord>> = combine(
    records,
    getAllCategories(),
    getAllWatches(),
    getAllPeople(),
    getAllEvents()
) { library, categories, watches, people, events ->
    AnalysisRecord.from(select(library), categories, watches, people, events)
}

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
    /** One row per person across the whole scope. */
    val people: List<PersonTotals> = emptyList(),
    val totalActiveDurationMs: Long = 0L,
    val eventCount: Int = 0,
    val dateRangeText: String = "",
    val scope: AnalysisScope = AnalysisScope.Unknown,
    val isEmpty: Boolean = true,
    val isFrozen: Boolean = false
) {
    val recordCount: Int get() = records.size
}

/**
 * One person's numbers across everything in scope.
 *
 * @property personId Null on a saved analysis, which stores names rather than ids — so colour and
 *   isolation are unavailable there, but the row still appears.
 */
data class PersonTotals(
    val personId: Long?,
    val name: String,
    val colorArgb: Int?,
    val recordCount: Int,
    val minBpm: Double,
    val avgBpm: Double,
    val maxBpm: Double,
    val activeDurationMs: Long
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
    val topRecordId: Long?,
    /** Set only on the Event tab, where the bar should open the event rather than a recording. */
    val eventId: Long? = null,
    /** How many recordings went into this bar, so a one-recording bar is not read as a trend. */
    val recordCount: Int = 0
)
