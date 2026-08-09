package inga.bpmetrics.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.TagEntity
import inga.bpmetrics.ui.analysis.ConcurrentAnalysis
import inga.bpmetrics.ui.analysis.EventAnalysis
import inga.bpmetrics.ui.analysis.RecordAnalysis
import inga.bpmetrics.ui.analysis.RecordInsights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where a recording sits in the hierarchy, for the breadcrumb. Both null when it is unfiled. */
data class RecordPlacement(
    val event: EventEntity? = null,
    val group: EventEntity? = null
)

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

    /**
     * This recording as a one-lane analysis, so the chart here is the chart on the event page.
     *
     * [EventAnalysis] at N=1 rather than a second chart implementation — same gap threshold, same
     * active duration, same zone split, same drawing code. There used to be two charts and only
     * one of them could zoom or scrub.
     */
    val analysis: StateFlow<ConcurrentAnalysis> = combine(
        _record,
        repository.getAllPeople(),
        repository.getAllWatches()
    ) { rec, people, watches ->
        rec?.let { EventAnalysis.from(listOf(it), watches = watches, people = people) }
            ?: ConcurrentAnalysis()
    }
        // Merging and sampling is real work for a long recording, and it has no business on the
        // thread that draws the result.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConcurrentAnalysis())

    /** Which event and group this belongs to, for the breadcrumb. */
    val placement: StateFlow<RecordPlacement> = combine(
        _record,
        repository.allEventsInTree
    ) { rec, events ->
        val event = rec?.metadata?.eventId?.let { id -> events.firstOrNull { it.eventId == id } }
        RecordPlacement(
            event = event,
            // The event above it, whatever kind of thing that is. Before the fold the breadcrumb
            // could only name a collection, so a set nested inside a day showed no context at all.
            group = event?.parentId?.let { id -> events.firstOrNull { it.eventId == id } }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordPlacement())

    /**
     * The picture that stands for this recording, whether it set one or inherited it.
     *
     * Resolved rather than read off the record, so the page shows the same cover the library tile
     * does — including the event's, which the record itself knows nothing about.
     */
    val cover: StateFlow<inga.bpmetrics.library.Cover?> = combine(
        _record,
        repository.allEventsInTree
    ) { rec, events ->
        if (rec == null) return@combine null
        inga.bpmetrics.library.CoverResolver.forRecording(
            directCover = rec.metadata.ownCover,
            eventId = rec.metadata.eventId,
            eventCovers = events.associate { it.eventId to it.ownCover },
            events = events
        )?.cover
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * What this recording says about itself, and how it compares to that person's others.
     *
     * The comparison needs the rest of the library, which is why it lives here rather than on the
     * record: "their third highest" is not a property of one recording.
     */
    val insights: StateFlow<RecordInsights> = combine(
        _record,
        analysis,
        repository.records
    ) { rec, current, library ->
        if (rec == null) return@combine RecordInsights()
        val theirs = rec.metadata.personId?.let { personId ->
            library.filter {
                it.metadata.personId == personId && it.metadata.recordId != rec.metadata.recordId
            }
        }.orEmpty()
        RecordAnalysis.from(current.series.firstOrNull(), rec, theirs)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordInsights())

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
     * Gives this recording a picture of its own.
     *
     * Works whether or not the recording is filed under anything. Covers normally live on the event
     * so that everything from one night matches without repeating the operation — but a recording
     * that is not part of anything has no event to hang one on, and "file it into an event first"
     * is not an answer when the recording is a one-off.
     *
     * Set here it overrides whatever an event would have supplied, which is also what makes this
     * the way to give one recording in a set its own picture.
     */
    fun setOwnCover(context: android.content.Context, source: android.net.Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val hint = _record.value?.metadata?.title?.takeIf { it.isNotBlank() } ?: "recording"
            val stored = repository.setCover(
                context = context,
                owner = LibraryRepository.CoverOwner.Recording(recordId),
                source = source,
                nameHint = hint
            )
            if (stored) loadRecord()
            onResult(stored)
        }
    }

    /** Re-frames this recording's own cover. */
    fun setOwnCoverCrop(cover: inga.bpmetrics.library.Cover) {
        viewModelScope.launch {
            repository.setCoverCrop(LibraryRepository.CoverOwner.Recording(recordId), cover)
            loadRecord()
        }
    }

    /**
     * Removes this recording's own picture.
     *
     * Hands it back to its event's cover rather than leaving it with none — which is why the column
     * stores null rather than an empty string.
     */
    fun clearOwnCover(context: android.content.Context) {
        viewModelScope.launch {
            repository.clearCover(context, LibraryRepository.CoverOwner.Recording(recordId))
            loadRecord()
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
