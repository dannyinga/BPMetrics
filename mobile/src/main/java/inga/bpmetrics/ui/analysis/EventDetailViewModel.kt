package inga.bpmetrics.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.EffectiveTagsResolver
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.TimeSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val group: EventEntity? = null,
    /** With readings, because this page draws a merged curve over them. */
    /** Everything in the subtree, without readings — the header counts them, it does not draw them. */
    val records: List<inga.bpmetrics.library.BpmRecord> = emptyList(),
    val people: Map<Long, PersonEntity> = emptyMap(),
    /** How many distinct people are in it, for the header's one-line summary. */
    val personCount: Int = 0,
    /** What it actually covers, from its contents rather than its window. */
    val span: TimeSpan? = null,
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
    private val eventFlow = repository.getAllEvents()
        .map { events -> events.firstOrNull { it.eventId == eventId } }

    /**
     * This event's recordings, with their readings.
     *
     * The event page draws a merged curve, so it is one of the four places that genuinely needs
     * them. Which recordings is answered from the library stream — a question about rows — and
     * only then are the readings for those rows loaded. Loading the library's readings to find one
     * evening's is what §9 of the product doc is about.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val recordsFlow = repository.records
        .map { records ->
            records.filter { it.metadata.eventId == eventId }.map { it.metadata.recordId }
        }
        .distinctUntilChanged()
        .mapLatest { ids -> repository.recordsWithPoints(ids) }

    /**
     * The subject, and nothing about the analysis.
     *
     * The readings, the curves and the numbers left with Sprint 5: they belong to the *scope*, and
     * every subject's scope is served by one [AnalysisViewModel]. What is left here is what makes
     * an event an event rather than a set or a recording — its name, its window, where it sits in
     * the tree, and the things you can do to it.
     *
     * Points-free, so opening an event no longer loads a single reading to draw its header.
     */
    val state: StateFlow<EventDetailState> = combine(
        eventFlow,
        repository.records,
        // The whole tree. This read the collections flow, which returns nothing since sets
        // arrived, so the breadcrumb never resolved and every event looked top-level.
        repository.allEventsInTree,
        repository.getAllPeople()
    ) { event, rows, tree, people ->
        val within = if (event == null) emptySet()
            else inga.bpmetrics.library.EventTree.descendantsOf(tree, event.eventId)
        val mine = rows.filter { it.metadata.eventId in within }

        EventDetailState(
            event = event,
            // `parentId`/`eventId`, not the legacy `groupId` on either side — that column is null
            // on anything created since the fold, so the breadcrumb would stop appearing.
            group = event?.parentId?.let { id -> tree.firstOrNull { it.eventId == id } },
            records = mine.sortedBy { it.metadata.startTime },
            people = people.associateBy { it.personId },
            // The subtree, not the direct children: a festival's count has to include the
            // recordings inside its days, which is what its analysis covers.
            personCount = mine.mapNotNull { it.metadata.personId }.distinct().size,
            span = mine.takeIf { it.isNotEmpty() }?.let { rows2 ->
                TimeSpan(
                    rows2.minOf { it.metadata.startTime },
                    rows2.maxOf { it.metadata.endTime }
                )
            },
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailState())

    /**
     * Tags applied to this event, and the ones it inherits from its group.
     *
     * A group tag is shown here for the same reason it is shown on a recording: it applies, and
     * hiding it would leave someone wondering why their filter matched. It is not removable here.
     */
    val tags: StateFlow<List<EffectiveTag>> = combine(
        repository.getTagsForEvent(eventId),
        // Observed rather than read once, so tagging an event above this one refreshes this page
        // rather than waiting for something else to reload it.
        repository.allEventTags,
        repository.allEventsInTree
    ) { own, eventTags, events ->
        EffectiveTagsResolver.resolve(
            // The event's own tags are direct *here*: this is the level they were applied at. The
            // same resolver, asked from one rung down the hierarchy.
            directTags = own,
            // The full ancestry, this event included, rather than only what is above it. Its own
            // tags are already in `directTags` and the resolver takes the nearest attribution, so
            // they stay direct — while dropping the first entry would put its *parent* at depth
            // zero, where the resolver labels tags "this event". That reads as a tag applied here
            // and removable here, and it is neither.
            ancestry = inga.bpmetrics.library.EventTree.ancestryOf(events, eventId)
                .map { it.eventId },
            eventTags = eventTags
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Makes a tag where it is applied, creating its axis if new. */
    fun createTag(categoryName: String, tagName: String, onMade: (Long) -> Unit) {
        viewModelScope.launch { repository.findOrCreateTag(categoryName, tagName)?.let(onMade) }
    }

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
