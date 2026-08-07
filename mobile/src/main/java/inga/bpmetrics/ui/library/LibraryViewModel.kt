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
        _filterState
    ) { records, filter ->
        applyFilter(records, filter)
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

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
        val selectedWatchIds: Set<String> = emptySet()
    )

    companion object {
        /**
         * Applies a filter to a set of records.
         *
         * Lives here rather than inside [filteredRecords] because Analysis filters independently:
         * choosing what to analyse must not disturb what the Library is showing.
         */
        fun applyFilter(records: List<BpmRecord>, filter: FilterState): List<BpmRecord> {
            // Build a mapping of Tag ID -> Category ID from all available records
            val tagToCategoryMap = records.flatMap { it.tags }.associate { it.tagId to it.parentCategoryId }

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

                    val recordTagIds = record.tags.map { it.tagId }.toSet()

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

                dateMatch && tagMatch && bpmMatch && wearerMatch && watchMatch
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
