package inga.bpmetrics.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.EffectiveTagsResolver
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.EventGroupEntity
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the event page needs, assembled once.
 *
 * @property missing True when the event has been deleted out from under the screen, so it can
 *   leave rather than sit on an empty page.
 */
data class EventDetailState(
    val event: EventEntity? = null,
    val group: EventGroupEntity? = null,
    val records: List<BpmRecord> = emptyList(),
    val people: Map<Long, PersonEntity> = emptyMap(),
    val analysis: ConcurrentAnalysis = ConcurrentAnalysis(),
    val isLoading: Boolean = true
) {
    val missing: Boolean get() = !isLoading && event == null
}

/**
 * Drives one event's page.
 *
 * The analysis is rebuilt off the library rather than frozen at any point: an event is a live
 * grouping, and filing one more recording into it should change the chart. That is the opposite of
 * a saved analysis, which is deliberately a snapshot.
 */
class EventDetailViewModel(
    private val repository: LibraryRepository,
    private val eventId: Long
) : ViewModel() {

    /**
     * Which lane is brought forward, or null for all of them.
     *
     * Kept here rather than in the composable so the chart, the readout legend and the summary rows
     * all read one answer. Three copies of "who is selected" is three chances to disagree.
     */
    private val _isolatedId = MutableStateFlow<String?>(null)
    val isolatedId: StateFlow<String?> = _isolatedId.asStateFlow()

    fun isolate(id: String?) {
        _isolatedId.value = if (id == _isolatedId.value) null else id
    }

    private val eventFlow = repository.getAllEvents()
        .map { events -> events.firstOrNull { it.eventId == eventId } }

    private val recordsFlow = repository.records
        .map { records -> records.filter { it.metadata.eventId == eventId } }

    val state: StateFlow<EventDetailState> = combine(
        eventFlow,
        recordsFlow,
        repository.getAllEventGroups(),
        repository.getAllPeople(),
        repository.getAllWatches()
    ) { event, records, groups, people, watches ->
        EventDetailState(
            event = event,
            group = event?.groupId?.let { id -> groups.firstOrNull { it.groupId == id } },
            records = records.sortedBy { it.metadata.startTime },
            people = people.associateBy { it.personId },
            analysis = EventAnalysis.from(records, watches = watches, people = people),
            isLoading = false
        )
    }
        // Merging and sampling every reading in an event is real work — an evening across four
        // watches is tens of thousands of points — and doing it on the main thread stalls the
        // screen it is meant to draw.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailState())

    /**
     * Tags applied to this event, and the ones it inherits from its group.
     *
     * A group tag is shown here for the same reason it is shown on a recording: it applies, and
     * hiding it would leave someone wondering why their filter matched. It is not removable here.
     */
    val tags: StateFlow<List<EffectiveTag>> = combine(
        repository.getTagsForEvent(eventId),
        eventFlow,
        // Observed rather than read once, so tagging the collection refreshes this page rather
        // than waiting for something else to reload it.
        repository.allGroupTags,
        repository.getAllEventGroups()
    ) { own, event, groupTags, groups ->
        EffectiveTagsResolver.resolve(
            // The event's own tags are direct *here*: this is the level they were applied at. The
            // same resolver, asked from one rung down the hierarchy.
            directTags = own,
            eventId = null,
            // Every collection above this event, nearest first — its own, then the one that holds
            // that, and so on. A tag set on a festival reaches the sets inside its days.
            groupChain = event?.groupId
                ?.let { inga.bpmetrics.library.CollectionTree.ancestryOf(groups, it) }
                ?.map { it.groupId }
                ?.reversed()
                .orEmpty(),
            eventTags = emptyMap(),
            groupTags = groupTags
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setTags(tagIds: List<Long>) {
        viewModelScope.launch {
            val current = repository.getTagsForEvent(eventId).first().map { it.tagId }
            current.filterNot { it in tagIds }.forEach { repository.removeTagFromEvent(eventId, it) }
            tagIds.filterNot { it in current }.forEach { repository.addTagToEvent(eventId, it) }
        }
    }

    fun removeTag(tagId: Long) {
        viewModelScope.launch { repository.removeTagFromEvent(eventId, tagId) }
    }

    val categories = repository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun tagsInCategory(categoryId: Long) = repository.getTagsByCategory(categoryId)

    fun rename(name: String) {
        viewModelScope.launch { repository.renameEvent(eventId, name) }
    }

    fun setNotes(notes: String) {
        viewModelScope.launch { repository.setEventNotes(eventId, notes) }
    }

    /**
     * A picture for this event, and so for every recording filed under it.
     *
     * Including ones that arrive from a watch afterwards — nothing is written onto the recordings,
     * they resolve upward to find it. See `CoverResolver`.
     */
    fun setCover(
        context: android.content.Context,
        source: android.net.Uri,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val hint = repository.getEvent(eventId)?.displayName ?: "event"
            onResult(
                repository.setCover(
                    context = context,
                    owner = LibraryRepository.CoverOwner.Event(eventId),
                    source = source,
                    nameHint = hint
                )
            )
        }
    }

    /** Re-frames the picture this event already has, leaving the file alone. */
    fun setCoverCrop(cover: inga.bpmetrics.library.Cover) {
        viewModelScope.launch {
            repository.setCoverCrop(LibraryRepository.CoverOwner.Event(eventId), cover)
        }
    }

    /**
     * Takes this event's own picture off.
     *
     * Its recordings fall back to the collection above it rather than to nothing, which is why the
     * column stores null rather than an empty string.
     */
    fun clearCover(context: android.content.Context) {
        viewModelScope.launch {
            repository.clearCover(context, LibraryRepository.CoverOwner.Event(eventId))
        }
    }

    /** Takes a recording out of the event. It goes back to Unfiled; it is never deleted. */
    fun removeRecord(recordId: Long) {
        viewModelScope.launch { repository.assignRecordsToEvent(listOf(recordId), null) }
    }

    fun deleteEvent(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteEvent(eventId)
            onDone()
        }
    }

    class Factory(
        private val repository: LibraryRepository,
        private val eventId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EventDetailViewModel(repository, eventId) as T
    }
}
