package inga.bpmetrics.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.export.JsonExporter
import inga.bpmetrics.export.LibraryBackup
import inga.bpmetrics.export.RestoreResult
import inga.bpmetrics.export.restoreBackup
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.EventGroupEntity
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.EventSuggestion
import inga.bpmetrics.library.suggestEvents
import inga.bpmetrics.library.TimeSpan
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.WatchEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the [inga.bpmetrics.ui.LibraryScreen], now supporting advanced sorting and filtering.
 *
 * @property repository The repository used to fetch and filter BPM records.
 */
class LibraryViewModel(val repository: LibraryRepository) : ViewModel() {

    private val _sortOption = MutableStateFlow(SortOption.DATE)
    /**
     * The current sorting option selected by the user.
     */
    val sortOption = _sortOption.asStateFlow()

    private val _isReversed = MutableStateFlow(false)
    /**
     * Whether the current sorting should be reversed.
     */
    val isReversed = _isReversed.asStateFlow()

    private val _filterState = MutableStateFlow(FilterState())
    /**
     * The current filtering state applied to the record library.
     */
    val filterState = _filterState.asStateFlow()

    private val _selectedRecordIds = MutableStateFlow<Set<Long>>(emptySet())
    /**
     * The set of selected record IDs for bulk actions.
     */
    val selectedRecordIds = _selectedRecordIds.asStateFlow()

    /**
     * A flow that emits the list of records after applying the current filter state.
     * This is shared to avoid redundant filtering when consumed by multiple components (like Analysis).
     */
    val filteredRecords: Flow<List<BpmRecord>> = combine(
        repository.records,
        _filterState,
        repository.effectiveTags,
        repository.getAllEvents()
    ) { records, filter, tags, events ->
        applyFilter(records, filter, tags, events.associate { it.eventId to it.groupId })
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    /**
     * Each recording's tags including what it inherits, for the tiles to show provenance.
     *
     * Resolved once here rather than per tile: the answer is the same for every recording in an
     * event, and asking per row would be three queries a tile while scrolling.
     */
    val effectiveTags: StateFlow<Map<Long, List<EffectiveTag>>> = repository.effectiveTags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Everyone who wears a watch, for the filter to offer and for the library to colour by.
     *
     * From the profiles rather than gathered off the records: a person is a real thing now, so
     * someone who has not recorded yet still appears, and someone whose name was spelled two ways
     * before profiles existed no longer appears twice.
     */
    val availablePeople: StateFlow<List<PersonEntity>> = repository.getAllPeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The same people keyed by id, which is how the list and its tiles look them up. */
    val peopleById: StateFlow<Map<Long, PersonEntity>> = availablePeople
        .map { people -> people.associateBy { it.personId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Watches known to the registry, for the filter to offer. */
    val availableWatches: StateFlow<List<WatchEntity>> = repository.getAllWatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Events and groups ---

    /**
     * Which of the three library views is showing, restored from settings.
     *
     * Read once at construction rather than collected: this is where the user left off, not a live
     * value, and treating it as a flow would fight their taps every time settings emitted.
     */
    private val _viewMode = MutableStateFlow(LibraryViewMode.RECORDINGS)
    val viewMode: StateFlow<LibraryViewMode> = _viewMode.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = repository.getLibraryViewMode()
            _viewMode.value = LibraryViewMode.entries.firstOrNull { it.name == saved }
                ?: LibraryViewMode.RECORDINGS
        }
    }

    fun setViewMode(mode: LibraryViewMode) {
        _viewMode.value = mode
        viewModelScope.launch { repository.setLibraryViewMode(mode.name) }
    }

    /**
     * Events with everything the list needs to describe them without a second query per row.
     *
     * A card wants the span, the recording count and who was there — all of which are joins. Doing
     * them per row would issue three queries per visible event while scrolling.
     */
    val events: StateFlow<List<EventSummary>> = combine(
        repository.getAllEvents(),
        repository.records,
        peopleById
    ) { events, records, people ->
        val byEvent = records.groupBy { it.metadata.eventId }
        events.map { event ->
            val its = byEvent[event.eventId].orEmpty()
            EventSummary(
                event = event,
                records = its.sortedByDescending { it.metadata.startTime },
                people = its.mapNotNull { r -> r.metadata.personId?.let { people[it] } }.distinct()
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Groups, built from the event summaries rather than re-queried.
     *
     * A group is exactly its events, so its span, count and people are theirs added up. Deriving it
     * a second way from the records would be a second definition of the same number, free to drift.
     */
    val eventGroups: StateFlow<List<GroupSummary>> = combine(
        repository.getAllEventGroups(),
        events
    ) { groups, summaries ->
        val byGroup = summaries.groupBy { it.event.groupId }
        groups.map { group ->
            // The whole subtree, not just what this collection holds directly. A festival that
            // holds nothing but days has no events of its own, and reporting that as "0 events,
            // 0 recordings" describes the row rather than the thing the row stands for.
            val subtree = inga.bpmetrics.library.CollectionTree.descendantsOf(groups, group.groupId)
            GroupSummary(
                group = group,
                events = byGroup[group.groupId].orEmpty(),
                allEvents = subtree.toList().flatMap { byGroup[it].orEmpty() },
                // Itself excluded — a collection is not inside itself.
                nestedCollectionCount = subtree.size - 1
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Events belonging to no group, shown alongside the groups so they are not lost. */
    val ungroupedEvents: StateFlow<List<EventSummary>> = events
        .map { summaries -> summaries.filter { it.event.groupId == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Recordings filed under no event.
     *
     * Pinned above the events list rather than hidden. Without it, a recording that has not been
     * filed simply disappears from the view whose purpose is organising it.
     */
    val unfiledRecords: StateFlow<List<BpmRecord>> = repository.records
        .map { records -> records.filter { it.metadata.eventId == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Clusters of unfiled recordings that look like one occasion, minus the ones already waved off.
     *
     * Dismissals are held in memory rather than persisted: they last as long as the session, which
     * is as long as the list they are decluttering. Storing them would mean a schema for "things the
     * user was not interested in once", and a suggestion worth making again after a relaunch.
     */
    private val _dismissedSuggestions = MutableStateFlow<Set<Set<Long>>>(emptySet())

    val suggestions: StateFlow<List<EventSuggestion>> = combine(
        unfiledRecords,
        _dismissedSuggestions,
        repository.dismissedSuggestionRecords
    ) { records, dismissedThisSession, dismissedForGood ->
        // Permanently dismissed recordings are taken out before clustering, not after. Filtering
        // whole clusters afterwards would let one dismissed recording drag its neighbours out of a
        // suggestion they were never dismissed from.
        val remaining = records.filter { it.metadata.recordId !in dismissedForGood }
        suggestEvents(remaining).filter {
            it.records.map { r -> r.metadata.recordId }.toSet() !in dismissedThisSession
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hides a suggestion until the app is next launched. */
    fun dismissSuggestion(suggestion: EventSuggestion) {
        val ids = suggestion.records.map { it.metadata.recordId }.toSet()
        _dismissedSuggestions.value = _dismissedSuggestions.value + setOf(ids)
    }

    /**
     * Never suggests an event for these recordings again.
     *
     * Recorded against the recordings rather than the cluster, because a cluster has no identity —
     * one more recording arriving would change its membership and bring the whole thing back as a
     * "new" suggestion to dismiss all over again.
     */
    fun dismissSuggestionForever(suggestion: EventSuggestion) {
        val ids = suggestion.records.map { it.metadata.recordId }.toSet()
        viewModelScope.launch { repository.dismissSuggestionRecords(ids) }
    }

    fun createEvent(name: String, recordIds: Set<Long> = emptySet()) {
        viewModelScope.launch {
            val id = repository.createEvent(name)
            if (recordIds.isNotEmpty()) repository.assignRecordsToEvent(recordIds, id)
            clearSelection()
        }
    }

    fun renameEvent(eventId: Long, name: String) {
        viewModelScope.launch { repository.renameEvent(eventId, name) }
    }

    fun deleteEvent(eventId: Long) {
        viewModelScope.launch { repository.deleteEvent(eventId) }
    }

    fun setEventGroup(eventId: Long, groupId: Long?) {
        viewModelScope.launch { repository.setEventGroup(eventId, groupId) }
    }

    fun createEventGroup(name: String) {
        viewModelScope.launch { repository.createEventGroup(name) }
    }

    /**
     * Files one collection inside another.
     *
     * The repository refuses a move that would make a collection its own ancestor or nest past the
     * cap, and logs when it does. Unreachable from here — the picker only offers legal targets —
     * but the guard lives there rather than only in the UI, because a cycle does not throw, it
     * hangs every walk of the tree, and no screen should be the last thing preventing that.
     */
    fun setCollectionParent(groupId: Long, parentGroupId: Long?) {
        viewModelScope.launch { repository.setEventGroupParent(groupId, parentGroupId) }
    }

    fun renameEventGroup(groupId: Long, name: String) {
        viewModelScope.launch { repository.renameEventGroup(groupId, name) }
    }

    fun deleteEventGroup(groupId: Long) {
        viewModelScope.launch { repository.deleteEventGroup(groupId) }
    }

    /** Files the current selection under an event, or unfiles it when [eventId] is null. */
    fun assignSelectedToEvent(eventId: Long?) {
        val ids = _selectedRecordIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.assignRecordsToEvent(ids, eventId)
            clearSelection()
        }
    }

    /**
     * The combined UI state, emitting a sorted and filtered list of records for the library list.
     */
    val uiState: StateFlow<LibraryUIState> = combine(
        filteredRecords,
        _sortOption,
        _isReversed
    ) { filtered, sort, reversed ->
        var sorted = when (sort) {
            SortOption.DATE -> filtered.sortedByDescending { it.metadata.startTime }
            SortOption.MAX_BPM -> filtered.sortedByDescending { it.maxDataPoint?.bpm ?: 0.0 }
            SortOption.AVG_BPM -> filtered.sortedByDescending { it.metadata.avg ?: 0.0 }
            SortOption.LOW_BPM -> filtered.sortedBy { it.minDataPoint?.bpm ?: Double.MAX_VALUE }
            SortOption.DURATION -> filtered.sortedByDescending { it.metadata.durationMs }
        }

        if (reversed) {
            sorted = sorted.reversed()
        }

        LibraryUIState(records = sorted, isLoading = false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUIState(),
    )

    /**
     * Updates the sorting option.
     */
    fun setSortOption(option: SortOption) { _sortOption.value = option }

    /**
     * Toggles the reversal of the sorted list.
     */
    fun toggleReverse() { _isReversed.value = !_isReversed.value }
    
    /**
     * Updates the filter state using the provided update function.
     */
    fun updateFilter(update: (FilterState) -> FilterState) {
        _filterState.value = update(_filterState.value)
    }

    /**
     * Resets all filters to their default (empty) state.
     */
    fun clearFilters() {
        _filterState.value = FilterState()
    }

    /**
     * Imports a record from a watch record object (e.g., from a CSV).
     */
    fun importRecord(watchRecord: BpmWatchRecord) {
        viewModelScope.launch {
            repository.saveWatchRecordToLibrary(watchRecord)
        }
    }

    /**
     * Restores a whole backup: people and watches first, then the recordings that point at them.
     *
     * Distinct from [importRecord], which takes one recording at a time and cannot recreate a
     * person. Importing a backup record by record would return the recordings and leave every one
     * of them attributed to nobody, because the ingest path can only *find* a person, not make one.
     */
    fun restoreFromBackup(backup: LibraryBackup, onDone: (RestoreResult) -> Unit) {
        viewModelScope.launch {
            onDone(restoreBackup(backup, repository))
        }
    }

    /**
     * Builds the backup JSON off the main thread and hands it back to be written.
     *
     * Saved analyses and settings are read here rather than collected into screen state: they are
     * needed once, at the moment of export, and holding them live for a button nobody has pressed
     * would keep two more queries running for the life of the screen.
     */
    fun buildBackupJson(
        records: List<BpmRecord>,
        people: List<PersonEntity>,
        watches: List<WatchEntity>,
        categories: List<CategoryEntity>,
        onReady: (String) -> Unit
    ) {
        viewModelScope.launch {
            onReady(
                JsonExporter.toBackupJson(
                    records = records,
                    people = people,
                    watches = watches,
                    categories = categories,
                    savedAnalyses = repository.getSavedAnalysesForBackup(),
                    settings = repository.getSettingsForBackup()
                    , events = repository.getAllEvents().first()
                    , eventGroups = repository.getAllEventGroups().first()
                )
            )
        }
    }

    /**
     * Toggles selection for a record.
     */
    fun toggleRecordSelection(recordId: Long) {
        _selectedRecordIds.value = if (_selectedRecordIds.value.contains(recordId)) {
            _selectedRecordIds.value - recordId
        } else {
            _selectedRecordIds.value + recordId
        }
    }

    /**
     * Clears current selection.
     */
    fun clearSelection() {
        _selectedRecordIds.value = emptySet()
    }

    /**
     * Selects all records.
     */
    fun selectAll(records: List<BpmRecord>) {
        _selectedRecordIds.value = records.map { it.metadata.recordId }.toSet()
    }

    /**
     * Deletes all selected records.
     */
    fun deleteSelectedRecords() {
        val idsToDelete = _selectedRecordIds.value
        viewModelScope.launch {
            idsToDelete.forEach { id ->
                repository.deleteRecordWithId(id)
            }
            clearSelection()
        }
    }

    /**
     * Adds selected tags to all selected records.
     */
    fun addTagsToSelectedRecords(tagIds: List<Long>) {
        val idsToTag = _selectedRecordIds.value
        viewModelScope.launch {
            idsToTag.forEach { recordId ->
                tagIds.forEach { tagId ->
                    repository.addTagToRecord(recordId, tagId)
                }
            }
            clearSelection()
        }
    }

    /**
     * Attributes every selected recording to one person, or to nobody.
     *
     * The batch correction for recordings that arrived before their watch had a wearer assigned.
     * Selection is cleared afterwards, as with the other bulk actions, so the result is visible
     * rather than hidden behind the selection highlight.
     */
    fun assignPersonToSelectedRecords(personId: Long?) {
        val ids = _selectedRecordIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.assignPersonToRecords(ids, personId)
            clearSelection()
        }
    }

    /**
     * Options for sorting the record list.
     */
    enum class SortOption { DATE, MAX_BPM, AVG_BPM, LOW_BPM, DURATION }
    
    /**
     * Represents the criteria used to filter the record list.
     */
    data class FilterState(
        val dateRange: Pair<Long, Long>? = null,
        val selectedTagIds: Set<Long> = emptySet(),
        val minBpm: Double = 0.0,
        val maxBpm: Double? = null,
        /**
         * People to include, matched against who was wearing the watch at the time.
         *
         * Answers "show me Kyle's recordings" — and because each record settled on a person when it
         * arrived, it keeps answering correctly after that watch has been handed to someone else.
         * Renaming Kyle does not disturb it either, since the match is on the profile rather than
         * on a copy of the name.
         */
        val selectedPersonIds: Set<Long> = emptySet(),
        /**
         * Watches to include, matched on the physical device rather than the name.
         *
         * Answers the other question: "show me everything this watch ever recorded", whoever was
         * wearing it and whatever it was called at the time.
         */
        val selectedWatchIds: Set<String> = emptySet(),
        /**
         * Events to include, matched on the event a recording is filed under.
         *
         * Distinct from filtering by a tag the event carries: this asks "what was at this
         * occasion", which is true regardless of how anything was tagged.
         */
        val selectedEventIds: Set<Long> = emptySet(),
        /** Groups to include, matched through the event each recording is filed under. */
        val selectedGroupIds: Set<Long> = emptySet()
    )

    companion object {
        /**
         * Applies a filter to a set of records.
         *
         * Lives here rather than inside [filteredRecords] because Analysis filters independently:
         * choosing what to analyse must not disturb what the Library is showing.
         */
        /**
         * @param effectiveTags Each recording's tags including the ones inherited from its event
         *   and group, keyed by record id. Empty means fall back to the recording's own tags,
         *   which is correct for callers that have not resolved inheritance.
         */
        /**
         * @param groupIdByEvent Which group each event belongs to, so a recording can be matched
         *   against a group through its event rather than storing a second link.
         */
        fun applyFilter(
            records: List<BpmRecord>,
            filter: FilterState,
            effectiveTags: Map<Long, List<EffectiveTag>> = emptyMap(),
            groupIdByEvent: Map<Long, Long?> = emptyMap()
        ): List<BpmRecord> {
            // Tag ids resolved through the hierarchy, so filtering by a group's tag returns every
            // recording underneath it — the point of §2.5. Falls back to the recording's own tags
            // where inheritance has not been resolved.
            fun tagIdsFor(record: BpmRecord): Set<Long> =
                effectiveTags[record.metadata.recordId]
                    ?.map { it.tag.tagId }
                    ?.toSet()
                    ?: record.tags.map { it.tagId }.toSet()

            // Category comes from the tags in play, which now include inherited ones — a tag that
            // only ever appears via a group would otherwise have no category and be skipped,
            // silently matching everything.
            val tagToCategoryMap = buildMap {
                records.forEach { record ->
                    record.tags.forEach { put(it.tagId, it.parentCategoryId) }
                    effectiveTags[record.metadata.recordId]?.forEach {
                        put(it.tag.tagId, it.tag.parentCategoryId)
                    }
                }
            }

            return records.filter { record ->
                // 1. Date Filter
                val dateMatch = filter.dateRange?.let { (start, end) ->
                    record.metadata.startTime in start..end
                } ?: true

                // 2. Cross-Category Tag Filter (Requirement: OR within categories, AND between categories)
                val tagMatch = if (filter.selectedTagIds.isNotEmpty()) {
                    val selectedTagsByCategory = filter.selectedTagIds
                        .mapNotNull { tagId -> tagToCategoryMap[tagId]?.let { catId -> catId to tagId } }
                        .groupBy({ it.first }, { it.second })

                    val recordTagIds = tagIdsFor(record)

                    selectedTagsByCategory.all { (_, selectedTagIds) ->
                        selectedTagIds.any { it in recordTagIds }
                    }
                } else true

                // 3. BPM Filter
                val avg = record.metadata.avg ?: 0.0
                val bpmMatch = (avg >= filter.minBpm) &&
                        (filter.maxBpm == null || avg <= filter.maxBpm)

                // 4. Wearer Filter — matched on who was wearing the watch when the recording was
                // made, so past recordings stay attributed to whoever actually made them.
                val wearerMatch = filter.selectedPersonIds.isEmpty() ||
                        record.metadata.personId in filter.selectedPersonIds

                // 5. Watch Filter — the physical device, independent of naming.
                val watchMatch = filter.selectedWatchIds.isEmpty() ||
                        record.metadata.watchId in filter.selectedWatchIds

                // 6. Event and group — the occasion rather than the hardware or the person.
                val eventMatch = filter.selectedEventIds.isEmpty() ||
                        record.metadata.eventId in filter.selectedEventIds

                // Reached through the event, not stored on the recording, so moving an event
                // between groups reclassifies everything in it with nothing to rewrite.
                val groupMatch = filter.selectedGroupIds.isEmpty() ||
                        record.metadata.eventId?.let { groupIdByEvent[it] } in filter.selectedGroupIds

                dateMatch && tagMatch && bpmMatch && wearerMatch && watchMatch &&
                        eventMatch && groupMatch
            }
        }
    }

    /**
     * Factory class for creating instances of [LibraryViewModel].
     */
    class Factory(private val repository: LibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                return LibraryViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

/**
 * Represents the UI state for the library screen.
 *
 * @property records The list of [BpmRecord]s to be displayed.
 * @property isLoading Whether the library is currently loading its data.
 */
data class LibraryUIState(
    val records: List<BpmRecord> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * Which of the library's three views is showing.
 *
 * The same recordings, organised three ways — flat, by what they were part of, and by the
 * collection that belonged to.
 */
enum class LibraryViewMode { RECORDINGS, EVENTS, GROUPS }

/**
 * An event with what a list row needs to describe it.
 *
 * [span] and [people] are derived rather than stored, for the reason events carry no times of their
 * own: both change the moment a recording is filed or unfiled.
 */
data class EventSummary(
    val event: EventEntity,
    val records: List<BpmRecord>,
    val people: List<PersonEntity>
) {
    val recordCount: Int get() = records.size
    val span: TimeSpan? get() = records.spanOrNull()
}

/**
 * A collection described entirely by what it contains, so the two can never disagree.
 *
 * Two lists, because a collection is now two different things depending on the question. [events]
 * is what it holds *directly* — the rows to show when the card is expanded, since anything nested
 * deeper appears as its own card. Everything else is counted over [allEvents], the whole subtree.
 *
 * Keeping only the direct list is what made a festival holding nothing but days report "0 events,
 * 0 recordings": every count was true of the collection itself and false of what it represents.
 */
data class GroupSummary(
    val group: EventGroupEntity,
    val events: List<EventSummary>,
    /** Everything in the subtree, this collection's own events included. */
    val allEvents: List<EventSummary> = events,
    /** How many collections sit inside this one, at any depth. */
    val nestedCollectionCount: Int = 0
) {
    val eventCount: Int get() = allEvents.size
    val recordCount: Int get() = allEvents.sumOf { it.recordCount }
    val people: List<PersonEntity> get() = allEvents.flatMap { it.people }.distinct()
    val span: TimeSpan?
        get() = allEvents.mapNotNull { it.span }.takeIf { it.isNotEmpty() }
            ?.let { spans -> TimeSpan(spans.minOf { it.startMs }, spans.maxOf { it.endMs }) }
}

/** The span a set of recordings covers, or null when there are none. */
fun List<BpmRecord>.spanOrNull(): TimeSpan? =
    if (isEmpty()) null
    else TimeSpan(minOf { it.metadata.startTime }, maxOf { it.metadata.endTime })
