package inga.bpmetrics.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the [LibraryScreen].
 *
 * This ViewModel manages the state of the library, including fetching and
 * providing the list of BPM records from the [LibraryRepository].
 *
 * @param repository The repository used to access BPM record data.
 */
class LibraryViewModel(repository: LibraryRepository) : ViewModel() {
    
    /**
     * A [StateFlow] representing the current UI state of the library.
     *
     * The state is derived from the repository's records flow and includes
     * the list of records and a loading indicator.
     */
    val uiState: StateFlow<LibraryUIState> =
        repository.records
            .map { LibraryUIState(records = it, isLoading = false) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = LibraryUIState(),
            )
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
