package inga.bpmetrics.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.LibraryRepository
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

    /**
     * A flow that emits the list of records after applying the current filter state.
     * This is shared to avoid redundant filtering when consumed by multiple components (like Analysis).
     */
    val filteredRecords: Flow<List<BpmRecord>> = combine(
        repository.records,
        _filterState
    ) { records, filter ->
        // Build a mapping of Tag ID -> Category ID from all available records
        val tagToCategoryMap = records.flatMap { it.tags }.associate { it.tagId to it.parentCategoryId }

        records.filter { record ->
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

            dateMatch && tagMatch && bpmMatch
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

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
        val maxBpm: Double? = null
    )

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
