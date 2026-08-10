package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.FilterContext
import inga.bpmetrics.library.FilterState
import inga.bpmetrics.library.Scope
import inga.bpmetrics.library.ScopeRef
import inga.bpmetrics.library.displayName
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
import kotlinx.coroutines.Dispatchers
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
    /**
     * Whether these numbers are frozen, so the screen knows not to offer to save them again.
     *
     * A flow rather than a constructor flag: frozen-ness is a property of the *selection*
     * ([inga.bpmetrics.library.CollectionEntity.frozenAt]), not of how somebody referred to it, so
     * only the data can answer it. A caller passing the wrong answer would render a frozen
     * selection's live members — which is nothing at all — and look like an empty screen.
     */
    private val frozen: Flow<Boolean> = flowOf(false),
    scope: Flow<AnalysisScope> = flowOf(AnalysisScope.Unknown),
    /**
     * The tree, so excluding an event can exclude its subtree.
     *
     * Empty for a saved analysis, which has no live tree to walk — its rows are a snapshot, and
     * anything excluded was excluded when it was saved.
     */
    private val tree: Flow<List<inga.bpmetrics.library.EventEntity>> = flowOf(emptyList()),
    /**
     * Loads the readings for a set of recordings, for the chart and nothing else.
     *
     * A lambda rather than a repository, so this ViewModel stays able to render a frozen selection
     * with no database behind it at all. Defaults to none, which is exactly right for that case.
     */
    private val readings: suspend (List<Long>) -> List<inga.bpmetrics.library.BpmRecordWithPoints> =
        { emptyList() },
    /** Who and what the lanes are labelled and coloured by. */
    private val chartContext: Flow<ChartContext> = flowOf(ChartContext())
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


    /**
     * Whether the curves have been asked for.
     *
     * Only consulted for a scope big enough to be worth asking about — see [AUTO_DRAW_LIMIT_MS].
     */
    private val _chartRequested = MutableStateFlow(false)

    fun requestChart() { _chartRequested.value = true }

    /**
     * How much measured time is in scope, from the stored figures.
     *
     * Free: §9.2 put active duration on the row precisely so a summary needs no readings. That
     * makes it the right thing to decide *whether to load readings* by — the decision costs
     * nothing, which a decision about avoiding work has to.
     */
    private val scopeActiveMs: Flow<Long> = analysisRecords
        .map { rows -> rows.sumOf { it.activeDurationMs } }
        .distinctUntilChanged()

    /** Whether the chart is drawn without being asked. */
    val chartDrawsItself: StateFlow<Boolean> = combine(
        scopeActiveMs,
        _chartRequested
    ) { active, requested -> requested || active <= AUTO_DRAW_LIMIT_MS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * The curves, for the scope as refined.
     *
     * The one part of a detail page that genuinely needs the readings, so it is the one part that
     * loads them — for whatever the scope settled on, and never for the library. That is §9.3.
     *
     * **Not drawn unasked for a large scope.** A festival's subtree is tens of hours across several
     * people, which is hundreds of thousands of readings to fetch and merge for a chart two inches
     * tall that nobody may have come to look at. Under [AUTO_DRAW_LIMIT_MS] it simply draws, which
     * covers every single recording and most single sets; over it, the page offers.
     *
     * **Off the main thread.** Merging and sampling is real work, and doing it on the dispatcher
     * that draws the screen stalls the screen it is meant to draw. The event page used to say so in
     * a comment on a `flowOn` that this fold dropped — the freeze that came back was that, not the
     * loading.
     *
     * Driven by [analysisRecords] rather than the raw scope, so unticking a recording in the
     * refinement sheet takes its lane off the chart too.
     *
     * Empty for a frozen selection: its readings may be gone, which is why its numbers were copied
     * in the first place.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val curves: StateFlow<ConcurrentAnalysis> = combine(
        analysisRecords
            .map { rows -> rows.map { it.recordId } }
            .distinctUntilChanged(),
        chartContext,
        chartDrawsItself
    ) { ids, context, draw -> Triple(ids, context, draw) }
        .mapLatest { (ids, context, draw) ->
            if (ids.isEmpty() || !draw) ConcurrentAnalysis()
            else EventAnalysis.from(readings(ids), context.watches, context.people)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConcurrentAnalysis())

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
        scope,
        frozen
    ) { records, options, currentScope, isFrozen ->

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

        // What recorded it and what occasion it was are comparisons in their own right rather than
        // tags, so they are offered as categories alongside them.
        //
        // **Person is not among them.** It was, and it produced a "Wearer" tab that ranked people
        // against each other — which is what the Per person section already does, from the same
        // records, with more to say. Two answers to one question, on two tabs of one screen.
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

        // Event leads, being the comparison a scope is usually about; Watch is provenance and
        // matters less often; tags last, since there can be many. People are their own section.
        val filteredCategories = buildList {
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

    companion object {
        /**
         * Synthetic category ids for the comparisons that are not tags.
         *
         * Negative so they can never collide with a real category, whose ids are generated
         * positive by Room.
         */
        /**
         * How much measured time a scope may hold before its chart waits to be asked for.
         *
         * Six hours, which at roughly a reading a second is about twenty thousand of them. That
         * covers any single recording and most single sets, so the common case simply draws. A
         * festival subtree — tens of hours across several people — does not, and would otherwise
         * fetch and merge hundreds of thousands of readings for a chart nobody may have opened the
         * page to see.
         */
        const val AUTO_DRAW_LIMIT_MS = 6L * 60 * 60 * 1000

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
         * An analysis of whatever [ref] names.
         *
         * **The one factory.** There were three here — a filter, a collection, a frozen snapshot —
         * plus a separate ViewModel for an event and another for a recording: five ways of building
         * one object, because each arrived when its subject did. They differed only in how the
         * recordings were chosen, which is precisely what [ScopeRef] is.
         *
         * Sprint 5 folds the screens; this folds what feeds them. "A detail page is a scope, its
         * numbers and a split" is only true if every subject gets here the same way.
         */
        fun forScope(
            repository: LibraryRepository,
            ref: ScopeRef
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AnalysisViewModel(
                allInScope = repository.recordsInScope(ref),
                frozen = repository.scopeIsFrozen(ref),
                scope = repository.describeScope(ref),
                // A frozen selection has no live tree to walk: its rows are a snapshot, and
                // anything left out of it was left out when it was taken.
                tree = repository.scopeIsFrozen(ref).flatMapLatest { isFrozen ->
                    if (isFrozen) flowOf(emptyList()) else repository.allEventsInTree
                },
                // The readings, for the chart, for this scope only. A frozen selection asks for
                // none: it has none to ask for, which is why its numbers were copied.
                readings = { ids -> repository.recordsWithPoints(ids) },
                chartContext = combine(
                    repository.getAllWatches(),
                    repository.getAllPeople()
                ) { watches, people -> ChartContext(watches, people) }
            ) as T
        }
    }
}

/**
 * The recordings a scope resolves to, reduced to what an analysis needs.
 *
 * One path for every subject. A recording, an event, a collection and a question differ in *which*
 * rows they select and in nothing else, so they share the reduction, the name resolution and the
 * tag inheritance below.
 *
 * A **frozen** selection is the one exception, and it is not really an exception: its numbers were
 * copied when it was frozen precisely because they cannot be recomputed — the readings may be gone.
 * So it reads its own rows rather than the library's. The branch lives here, on the data, rather
 * than in a caller that would have to be told.
 */
private fun LibraryRepository.recordsInScope(ref: ScopeRef): Flow<List<AnalysisRecord>> =
    if (ref !is ScopeRef.Collection) liveRecordsInScope(ref) else
        getAllCollections()
            .map { sets -> sets.firstOrNull { it.collectionId == ref.collectionId }?.isFrozen == true }
            .distinctUntilChanged()
            .flatMapLatest { isFrozen ->
                if (!isFrozen) liveRecordsInScope(ref)
                else flow {
                    emit(
                        loadSavedAnalysis(ref.collectionId)
                            ?.records.orEmpty()
                            .map { AnalysisRecord.from(it) }
                    )
                }
            }

/** Whether [ref] names a selection whose numbers were frozen. */
private fun LibraryRepository.scopeIsFrozen(ref: ScopeRef): Flow<Boolean> =
    if (ref !is ScopeRef.Collection) flowOf(false) else
        getAllCollections()
            .map { sets -> sets.firstOrNull { it.collectionId == ref.collectionId }?.isFrozen == true }
            .distinctUntilChanged()

/**
 * The live resolution, through [Scope] — the same walk the library filter and the export use.
 *
 * Names are resolved here rather than stored, so renaming a person or a venue relabels every screen
 * that mentions them.
 */
private fun LibraryRepository.liveRecordsInScope(ref: ScopeRef): Flow<List<AnalysisRecord>> = combine(
    combine(
        records,
        allEventsInTree,
        getAllCollections(),
        allCollectionEventLinks(),
        allCollectionRecordLinks()
    ) { rows, tree, sets, eventLinks, recordLinks ->
        inga.bpmetrics.library.Library(
            records = rows,
            events = tree,
            collections = sets,
            collectionEvents = eventLinks,
            collectionRecords = recordLinks
        )
    },
    combine(getAllCategories(), getAllWatches(), getAllPeople(), getAllLocations()) {
        categories, watches, people, places -> listOf(categories, watches, people, places)
    },
    effectiveTags
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
        // A smart collection's rule reads tags too, so they go into the context rather than only
        // into the reduction — filtering by an event's tag has to reach everything under it here
        // as it does everywhere else.
        Scope.recordsIn(ref, snapshot.copy(filterContext = FilterContext(effectiveTags = tags))),
        categories,
        watches,
        people,
        snapshot.events,
        tags,
        places
    )
}

/**
 * What the header calls this scope, and what it says underneath.
 *
 * Live, because the point of storing ids rather than names is that a rename reaches every screen
 * mentioning the thing renamed.
 */
private fun LibraryRepository.describeScope(ref: ScopeRef): Flow<AnalysisScope> = when (ref) {
    is ScopeRef.Query -> describeFilter(ref.filter)

    is ScopeRef.Selection -> records.map { rows ->
        val chosen = rows.count { it.metadata.recordId in ref.recordIds }
        AnalysisScope.Group(
            name = " recording" + if (chosen == 1) "" else "s",
            eventCount = 0,
            recordCount = chosen
        )
    }

    is ScopeRef.Recording -> records.map { rows ->
        rows.firstOrNull { it.metadata.recordId == ref.recordId }
            ?.let { AnalysisScope.Group(name = it.metadata.title, eventCount = 0, recordCount = 1) }
            ?: AnalysisScope.Unknown
    }

    is ScopeRef.Event -> combine(allEventsInTree, records) { tree, rows ->
        tree.firstOrNull { it.eventId == ref.eventId }?.let { event ->
            val within = inga.bpmetrics.library.EventTree.descendantsOf(tree, ref.eventId)
            AnalysisScope.Group(
                name = event.displayName,
                // The subtree minus itself: "3 events" under a festival means its days and sets,
                // not the festival counting itself as one of them.
                eventCount = within.size - 1,
                recordCount = rows.count { it.metadata.eventId in within }
            )
        } ?: AnalysisScope.Unknown
    }

    is ScopeRef.Collection -> combine(
        getAllCollections(),
        allEventsInTree,
        records,
        allCollectionEventLinks(),
        allCollectionRecordLinks()
    ) { sets, tree, rows, eventLinks, recordLinks ->
        sets.firstOrNull { it.collectionId == ref.collectionId }?.let { set ->
            if (set.isFrozen) AnalysisScope.Saved(set.displayName) else {
                val snapshot = inga.bpmetrics.library.Library(
                    records = rows,
                    events = tree,
                    collections = sets,
                    collectionEvents = eventLinks,
                    collectionRecords = recordLinks
                )
                AnalysisScope.Group(
                    name = set.displayName,
                    eventCount = Scope.eventsIn(ref.collectionId, tree, eventLinks).size,
                    // The same walk the card counts with, so a header cannot say "4 events" over
                    // an analysis of six.
                    recordCount = Scope.recordsIn(ref, snapshot).size,
                    isSmart = set.isSmart
                )
            }
        } ?: AnalysisScope.Unknown
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

/**
 * The registries a chart needs to label and colour its lanes.
 *
 * Bundled so [AnalysisViewModel] takes one parameter rather than two, and so the frozen case can
 * default the whole thing away.
 */
data class ChartContext(
    val watches: List<inga.bpmetrics.library.WatchEntity> = emptyList(),
    val people: List<inga.bpmetrics.library.PersonEntity> = emptyList()
)
