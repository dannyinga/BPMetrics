package inga.bpmetrics.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.TagEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for managing the state and interactions of a single BPM record detail screen.
 *
 * This ViewModel handles loading the record data from the repository, updating the title
 * and description, managing assigned tags, and deleting the record.
 */
class BpmRecordViewModel(
    private val repository: LibraryRepository,
    private val recordId: Long
) : ViewModel() {

    private val _record = MutableStateFlow<BpmRecord?>(null)
    /**
     * A [kotlinx.coroutines.flow.StateFlow] emitting the current [BpmRecord] details, or null if the record is still loading.
     */
    val record: StateFlow<BpmRecord?> = _record

    /**
     * The given name of the watch this recording came from, or null if it has none.
     *
     * Resolved live rather than stored on the record: a watch's name describes hardware that
     * still exists, so renaming it updates every recording it made. What is fixed at ingest is
     * *which* watch and *which* person, not what either of them is called.
     */
    val watchName: StateFlow<String?> = _record
        .map { rec ->
            rec?.metadata?.watchId?.let { repository.getWatch(it)?.deviceName?.takeIf { n -> n.isNotBlank() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Everyone available to attribute this recording to. */
    val people: StateFlow<List<PersonEntity>> = repository.getAllPeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * This recording's tags, including what it inherits from its event and group.
     *
     * Read from the library's resolved map rather than from `_record.tags`, which only knows about
     * tags applied directly. Filing the recording into a tagged event has to change what is shown
     * here without anything having been written to the recording — that is the point of §2.5.
     */
    val effectiveTags: StateFlow<List<EffectiveTag>> = repository.effectiveTags
        .map { all -> all[recordId].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            repository.updateRecordTitle(recordId, newTitle)
            loadRecord()
        }
    }

    /**
     * Updates the user-defined description of the record.
     *
     * @param newDescription The new description to assign to the record.
     */
    fun updateDescription(newDescription: String) {
        viewModelScope.launch {
            repository.updateRecordDescription(recordId, newDescription)
            loadRecord()
        }
    }

    /**
     * Corrects who this recording belongs to, and the device it reports.
     *
     * A per-record override, for the recording that arrived before its watch had anyone assigned.
     */
    fun updateDeviceAndWearer(deviceId: String, personId: Long?) {
        viewModelScope.launch {
            repository.updateRecordDeviceAndWearer(recordId, deviceId, personId)
            loadRecord()
        }
    }

    /**
     * Assigns a tag to the current record.
     *
     * @param tagId The ID of the tag to assign.
     */
    fun addTag(tagId: Long) {
        viewModelScope.launch {
            repository.addTagToRecord(recordId, tagId)
            loadRecord()
        }
    }

    /**
     * Removes a tag from the current record.
     *
     * @param tagId The ID of the tag to remove.
     */
    fun removeTag(tagId: Long) {
        viewModelScope.launch {
            repository.removeTagFromRecord(recordId, tagId)
            loadRecord()
        }
    }

    /**
     * Saves a split portion of a record as a new entry.
     * Inherits tags from the original record.
     */
    fun splitRecord(newRecord: BpmWatchRecord, title: String) {
        viewModelScope.launch {
            val tagsToCopy = _record.value?.tags ?: emptyList()
            val newRecordId = repository.saveWatchRecordToLibrary(newRecord, title)
            
            // Copy tags to the new record
            tagsToCopy.forEach { tag ->
                repository.addTagToRecord(newRecordId, tag.tagId)
            }
        }
    }

    /**
     * Returns a flow of all available categories.
     */
    fun getAllCategories(): Flow<List<CategoryEntity>> = repository.getAllCategories()

    /**
     * Returns a flow of all tags within a specific category.
     *
     * @param categoryId The ID of the category.
     */
    fun getTagsByCategory(categoryId: Long): Flow<List<TagEntity>> = repository.getTagsByCategory(categoryId)

    /**
     * Manually triggers a reload of the record from the database.
     */
    fun refresh() {
        loadRecord()
    }

    /**
     * Factory class for creating instances of [BpmRecordViewModel].
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
