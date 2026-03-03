package inga.bpmetrics.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing the state and interactions of a single BPM record detail screen.
 *
 * This ViewModel handles loading the record data from the repository, updating the title,
 * and deleting the record. It uses [MutableStateFlow] to expose the current record state to the UI.
 *
 * @property repository The [LibraryRepository] used to interact with the database.
 * @property recordId The unique ID of the record this ViewModel is responsible for.
 */
class BpmRecordViewModel(
    private val repository: LibraryRepository,
    private val recordId: Long
) : ViewModel() {

    private val _record = MutableStateFlow<BpmRecord?>(null)
    /**
     * A [StateFlow] emitting the current [BpmRecord] details, or null if the record is still loading.
     */
    val record: StateFlow<BpmRecord?> = _record

    init {
        loadRecord()
    }

    /**
     * Fetches the record from the repository and updates the UI state.
     */
    private fun loadRecord() {
        viewModelScope.launch {
            val fetchedRecord = repository.getRecordWithId(recordId)
            _record.value = fetchedRecord
        }
    }

    /**
     * Deletes the current record from the database.
     */
    fun deleteRecord() {
        viewModelScope.launch {
            repository.deleteRecordWithId(recordId)
        }
    }

    /**
     * Updates the user-defined title of the record.
     *
     * @param newTitle The new string title to assign to the record.
     */
    fun updateTitle(newTitle: String) {
        viewModelScope.launch {
            _record.value?.let {
                repository.updateRecordTitle(it, newTitle)
                // Refresh local state to reflect the title change immediately
                loadRecord()
            }
        }
    }

    /**
     * Manually triggers a reload of the record from the database.
     */
    fun refresh() {
        loadRecord()
    }

    /**
     * Factory class for creating instances of [BpmRecordViewModel].
     * 
     * Required because the ViewModel takes custom parameters ([LibraryRepository] and [recordId])
     * that are not provided by the default ViewModel factory.
     */
    class Factory(
        private val repository: LibraryRepository,
        private val recordId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BpmRecordViewModel::class.java)) {
                return BpmRecordViewModel(repository, recordId) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
