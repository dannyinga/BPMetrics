package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.FilterState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.TagEntity
import inga.bpmetrics.library.TagSource
import inga.bpmetrics.library.BpmZones
import inga.bpmetrics.library.ZoneTime
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
    /** Everything the scope resolves to, before this analysis leaves anything out. */
    private val allInScope: Flow<List<AnalysisRecord>>,
    private val savedAnalysisId: Long? = null,
    scope: Flow<AnalysisScope> = flowOf(AnalysisScope.Unknown),
    /**
     * The tree, so excluding an event can exclude its subtree.
     *
     * Empty for a saved analysis, which has no live tree to walk — its rows are a snapshot, and
     * anything excluded was excluded when it was saved.
     */
    private val tree: Flow<List<inga.bpmetrics.library.EventEntity>> = flowOf(emptyList())
) : ViewModel() {

    // --- What this analysis leaves out (TX-3.6 to 3.8) ---

    private val _exclusions = MutableStateFlow(ScopeExclusions())
    val exclusions: StateFlow<ScopeExclusions> = _exclusions.asStateFlow()

    /**
     * The rows the refinement sheet lists, derived from the whole scope rather than the refined
     * one — a row has to stay on screen after it is unticked, or there is no way to tick it back.
     */
    val scopeEntries: StateFlow<List<ScopeEntry>> = combine(
        allInScope, tree, _exclusions
    ) { records, events, excluded ->
        // Through [ScopeRefinement.entriesFor], not a second copy of it here. The tree-less case —
        // a saved analysis — is that function's documented fallback rather than this screen's own
        // idea of what is in scope.
        ScopeRefinement.entriesFor(
            events = events,
            records = records.map {
                ScopeRefinement.ScopeItem(
                    recordId = it.recordId,
                    eventId = it.eventId,
                    title = it.title,
                    startTime = it.date,
                    eventLabel = it.eventLabel
                )
            },
            // No root: a collection scope holds unrelated branches, a filter holds whatever it
            // matched, and a saved analysis has no live tree. None of those has a level to take.
            rootId = null,
            exclusions = excluded
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleScopeEntry(entry: ScopeEntry, include: Boolean) {
        _exclusions.value = ScopeRefinement.toggle(_exclusions.value, entry, include)
    }

    fun clearExclusions() { _exclusions.value = ScopeExclusions() }

    /**
     * The scope after exclusions, which is what everything below analyses.
     *
     * Applied once, here, rather than at each figure — the totals, the lanes and the rankings all
     * read this, so a header cannot describe a different set of recordings from the rows under it.
     *
     * It is also how TX-3.8 is satisfied without a column to store exclusions in. Saving writes a
     * snapshot of `uiState.records`, which is this flow, so an excluded recording is simply not in
     * the saved analysis — persisted by being absent. Nothing else keeps them, so they are transient
     * everywhere else, which is the whole rule: "not part of this analysis" is a fact about the
     * analysis, and an analysis that was never saved is not a thing facts can be about.
     *
     * The consequence worth knowing: excluding, saving, then wanting it back means re-saving from
     * the live scope. That is the honest behaviour for a frozen snapshot — the alternative is a
     * saved analysis whose contents change when you tick a box, which is not frozen at all.
     */
    private val analysisRecords: Flow<List<AnalysisRecord>> = combine(
        allInScope, tree, _exclusions
    ) { records, events, excluded ->
        if (excluded.isEmpty) records else {
            val dropped = mutableSetOf<Long>()
            excluded.excludedEventIds.forEach {
                dropped += inga.bpmetrics.library.EventTree.descendantsOf(events, it)
            }
            events.forEach { event ->
                if (event.excludedFromParentAnalysis &&
                    event.eventId !in excluded.includedDespiteFlag
                ) {
                    dropped += inga.bpmetrics.library.EventTree.descendantsOf(events, event.eventId)
                }
            }
            records.filter {
                it.eventId !in dropped && it.recordId !in excluded.excludedRecordIds
            }
        }
    }

    // --- Comparing along an axis (TX-3.3 to 3.5) ---

    private val _splitAxisKey = MutableStateFlow<String?>(null)

    /** Only axes the scope can actually be compared along. See [AnalysisSplit.axesFor]. */
    val availableAxes: StateFlow<List<SplitAxis>> = analysisRecords
        .map { AnalysisSplit.axesFor(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedAxis: StateFlow<SplitAxis?> = combine(availableAxes, _splitAxisKey) { axes, key ->
        // Resolved against what is currently offered, so an axis that stops qualifying — the last
        // Hulk recording excluded, say — falls away rather than showing one lane.
        axes.firstOrNull { it.key == key }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lanes: StateFlow<List<SplitLane>> = combine(analysisRecords, selectedAxis) { records, axis ->
        axis?.let { AnalysisSplit.split(records, it) }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSplitAxis(key: String?) {
        _splitAxisKey.value = if (_splitAxisKey.value == key) null else key
    }

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

    /**
     * Tag editing, wired only for a group scope.
     *
     * A filter is not a thing that can carry a tag — it describes a selection, not an occasion —
     * and a saved analysis is frozen. Null on both, and the screen omits the section rather than
     * offering an action that would have nowhere to write.
     */
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
        // By the qualified name, not the short one. Two events called "Subtronics" from different
        // weekends would otherwise merge into one bar and average two nights together — and the
        // short name is what the library shows precisely so it can be reused across festivals.
        val eventGroups = records
            .filter { it.eventLabel.isNotBlank() }
            .groupBy { it.eventLabel }

        val categoryGroups = buildMap {
            putAll(tagGroups)
            if (wearerGroups.size > 1) put(WEARER_CATEGORY_ID, wearerGroups)
            if (watchGroups.size > 1) put(WATCH_CATEGORY_ID, watchGroups)
            if (eventGroups.size > 1) put(EVENT_CATEGORY_ID, eventGroups)
        }

        // An event bar should open that event, which needs its id rather than its name.
        val eventIdsByName = records
            .filter { it.eventLabel.isNotBlank() }
            .associate { it.eventLabel to it.eventId }

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
                    recordCount = groupRecords.size,
                    // Which artist kept people in the peak band, rather than only who touched the
                    // highest number once. Summed from the same per-record split every other level
                    // uses, so a row and the whole scope can never disagree.
                    zoneTimes = BpmZones.merge(groupRecords.map { it.zoneTimes }),
                    activeDurationMs = groupRecords.sumOf { it.activeDurationMs }
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
            zoneTimes = BpmZones.merge(records.map { it.zoneTimes }),
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
                        activeDurationMs = activeMs,
                        zoneTimes = BpmZones.merge(theirs.map { it.zoneTimes })
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
            filter: FilterState
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AnalysisViewModel(
                    // Tags passed to the filter as well as to the reduction, so filtering by a
                    // group's tag selects everything underneath it — §2.5.
                    allInScope = repository.analysisRecords { library, tags ->
                        inga.bpmetrics.library.LibraryFilter.apply(
                            library,
                            filter,
                            inga.bpmetrics.library.FilterContext(effectiveTags = tags)
                        )
                    },
                    savedAnalysisId = null,
                    scope = repository.describeFilter(filter),
                    tree = repository.allEventsInTree
                ) as T
        }

        /**
         * A live analysis of everything in a collection.
         *
         * Deliberately the same ViewModel as a filtered analysis. A collection and a filter are two
         * ways of naming a set of recordings, and the questions worth asking of that set — who went
         * hardest, which event was the peak, how do the tags compare — do not change with how it
         * was named. Two implementations would be two chances to answer differently.
         *
         * Membership comes from [inga.bpmetrics.library.Scope], which is also what the library
         * filter's collection term now calls. They were two walks before, and one of them compared
         * a collection id against an event's parent id.
         */
        fun groupFactory(
            repository: LibraryRepository,
            groupId: Long
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val library = combine(
                    combine(
                        repository.records,
                        repository.allEventsInTree,
                        repository.getAllCollections(),
                        repository.allCollectionEventLinks(),
                        repository.allCollectionRecordLinks()
                    ) { records, tree, sets, eventLinks, recordLinks ->
                        inga.bpmetrics.library.Library(
                            records = records,
                            events = tree,
                            collections = sets,
                            collectionEvents = eventLinks,
                            collectionRecords = recordLinks
                        )
                    },
                    repository.effectiveTags
                ) { snapshot, tags ->
                    // The rule of a smart collection reads tags too, so they go into the context
                    // rather than only into the reduction below — filtering a collection by an
                    // event's tag has to pick up everything under it, same as anywhere else.
                    snapshot.copy(
                        filterContext = inga.bpmetrics.library.FilterContext(effectiveTags = tags)
                    )
                }

                // Membership is resolved before the mapping, not after. Reducing a record computes
                // its active duration, which walks every data point it has — doing that for the
                // whole library to keep a dozen recordings would be most of the work wasted.
                val records = combine(
                    library,
                    combine(
                        repository.getAllCategories(),
                        repository.getAllWatches(),
                        repository.getAllPeople(),
                        repository.getAllLocations()
                    ) { categories, watches, people, places ->
                        listOf(categories, watches, people, places)
                    },
                    repository.effectiveTags
                ) { snapshot, registries, tags ->
                    @Suppress("UNCHECKED_CAST")
                    val categories = registries[0] as List<inga.bpmetrics.library.CategoryEntity>
                    @Suppress("UNCHECKED_CAST")
                    val watches = registries[1] as List<inga.bpmetrics.library.WatchEntity>
                    @Suppress("UNCHECKED_CAST")
                    val people = registries[2] as List<inga.bpmetrics.library.PersonEntity>
                    @Suppress("UNCHECKED_CAST")
                    val places = registries[3] as List<inga.bpmetrics.library.LocationEntity>

                    AnalysisRecord.from(
                        inga.bpmetrics.library.Scope.recordsIn(
                            inga.bpmetrics.library.ScopeRef.Collection(groupId),
                            snapshot
                        ),
                        categories,
                        watches,
                        people,
                        snapshot.events,
                        tags,
                        places
                    )
                }

                // The header and the analysis read the same walk. They were two walks over two
                // different lists, which is how the header could say "4 events" over an analysis
                // of six.
                val scope = library.map { snapshot ->
                    snapshot.collections.firstOrNull { it.collectionId == groupId }?.let { set ->
                        AnalysisScope.Group(
                            name = set.displayName,
                            eventCount = inga.bpmetrics.library.Scope
                                .eventsIn(groupId, snapshot.events, snapshot.collectionEvents).size,
                            recordCount = inga.bpmetrics.library.Scope.recordsIn(
                                inga.bpmetrics.library.ScopeRef.Collection(groupId), snapshot
                            ).size,
                            isSmart = set.isSmart
                        )
                    } ?: AnalysisScope.Unknown
                }

                return AnalysisViewModel(
                    allInScope = records,
                    savedAnalysisId = null,
                    scope = scope,
                    tree = repository.allEventsInTree
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
                    allInScope = saved.map { loaded ->
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
    select: (
        List<inga.bpmetrics.library.BpmRecord>,
        Map<Long, List<inga.bpmetrics.library.EffectiveTag>>
    ) -> List<inga.bpmetrics.library.BpmRecord>
): Flow<List<AnalysisRecord>> = combine(
    combine(records, getAllCategories(), getAllWatches(), getAllPeople(), allEventsInTree, ::Library),
    effectiveTags,
    getAllLocations()
) { library, tags, places ->
    AnalysisRecord.from(
        select(library.records, tags),
        library.categories,
        library.watches,
        library.people,
        library.events,
        tags,
        places
    )
}

/**
 * Turns a filter's ids into the names a header can read out.
 *
 * Live, because the point is that renaming a person or a tag updates every screen that mentions
 * them — the filter stores ids precisely so that renaming does not break it.
 */
private fun LibraryRepository.describeFilter(
    filter: FilterState
): Flow<AnalysisScope> = combine(
    getAllCategories(),
    getAllPeople(),
    getAllWatches(),
    getAllEvents(),
    // The whole tree. This read the collections flow, which since sets arrived returns nothing —
    // so a filter naming a container described it as unknown.
    allEventsInTree
) { categories, people, watches, events, groups ->
    // Tags are not held in one flow, so they are gathered from the categories in play. Only the
    // selected ones are looked up, which is a handful at most.
    val categoryNames = categories.associate { it.categoryId to it.name }
    val tagLabels = categories
        .flatMap { category -> getTagsByCategory(category.categoryId).first() }
        .filter { it.tagId in filter.selectedTagIds }
        .map { (categoryNames[it.parentCategoryId] ?: "Uncategorized") to it.name }

    AnalysisScope.Filter(
        filter = filter,
        labels = AnalysisScope.FilterLabels(
            tags = tagLabels,
            people = people.filter { it.personId in filter.selectedPersonIds }
                .map { it.displayName },
            watches = watches.filter { it.watchId in filter.selectedWatchIds }
                .map { it.displayName },
            events = events.filter { it.eventId in filter.selectedEventIds }
                .map { it.displayName },
            groups = groups.filter { it.eventId in filter.selectedGroupIds }
                .map { it.displayName }
        )
    )
}

/** The five tables an analysis reads, bundled so they can be combined with the resolved tags. */
private data class Library(
    val records: List<inga.bpmetrics.library.BpmRecord>,
    val categories: List<inga.bpmetrics.library.CategoryEntity>,
    val watches: List<inga.bpmetrics.library.WatchEntity>,
    val people: List<inga.bpmetrics.library.PersonEntity>,
    val events: List<inga.bpmetrics.library.EventEntity>
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
    /** One row per person across the whole scope. */
    val people: List<PersonTotals> = emptyList(),
    /** Measured time across heart rate bands for the whole scope. */
    val zoneTimes: List<ZoneTime> = emptyList(),
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
    val activeDurationMs: Long,
    /** Where their time went, summed across their recordings in this scope. */
    val zoneTimes: List<ZoneTime> = emptyList()
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
    val recordCount: Int = 0,
    /** Where this row's time went, so a ranking answers "who stayed up there", not only "who spiked". */
    val zoneTimes: List<ZoneTime> = emptyList(),
    val activeDurationMs: Long = 0L
)
