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
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.TimeSpan
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.RecordMerge
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
        applyFilter(records, filter, tags, events.associate { it.eventId to it.parentId })
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
    private val _viewMode = MutableStateFlow(LibraryViewMode.TIMELINE)
    val viewMode: StateFlow<LibraryViewMode> = _viewMode.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = repository.getLibraryViewMode()
            // Falls back to TIMELINE, which also catches the stored "EVENTS" from before it was
            // replaced — an unrecognised name should land on the primary view rather than on
            // whichever one happens to be listed first.
            _viewMode.value = LibraryViewMode.entries.firstOrNull { it.name == saved }
                ?: LibraryViewMode.TIMELINE

            // Read once, for the same reason as the view mode: this is where the user left off,
            // not a live value. Collecting it would re-sort the list under their hands every time
            // the preference emitted.
            repository.getDefaultSort()
                ?.let { name -> SortOption.entries.firstOrNull { it.name == name } }
                ?.let { _sortOption.value = it }
        }
    }

    fun setViewMode(mode: LibraryViewMode) {
        _viewMode.value = mode
        viewModelScope.launch { repository.setLibraryViewMode(mode.name) }
    }

    /**
     * The cover for every recording, resolved once for the whole library.
     *
     * One map rather than a walk per row. Every tile needs to know which picture stands for it, and
     * that answer involves its event, that event's collection and every parent above it — done per
     * row it is a tree walk on every frame of a scroll, and done in two places it is two screens
     * quietly disagreeing about the same recording.
     */
    val coversByRecord: StateFlow<Map<Long, inga.bpmetrics.library.Cover>> = combine(
        repository.records,
        repository.allEventsInTree
    ) { records, events ->
        val eventCovers = events.associate { it.eventId to it.ownCover }

        records.mapNotNull { record ->
            inga.bpmetrics.library.CoverResolver.forRecording(
                directCover = record.metadata.ownCover,
                eventId = record.metadata.eventId,
                eventCovers = eventCovers,
                events = events
            )?.let { record.metadata.recordId to it.cover }
        }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The cover for every event, collections included, resolved the same way a recording's is.
     *
     * An event with no picture of its own shows the one above it — otherwise "set a cover on
     * Coachella" would decorate the Coachella card and leave every day inside it blank, which is
     * not what inheritance means anywhere else in the app.
     *
     * This was three flows before the fold: one for recordings, one for events walking up to their
     * collection, and one for collections walking up their parents. Three walks, three chances to
     * disagree. Collections are events, so it is one walk over one tree, and [coversByGroup] is now
     * literally the same map read under its old name.
     */
    val coversByEvent: StateFlow<Map<Long, inga.bpmetrics.library.Cover>> =
        repository.allEventsInTree.map { events ->
            val own = events.associate { it.eventId to it.ownCover }
            events.mapNotNull { event ->
                inga.bpmetrics.library.EventTree.ancestryOf(events, event.eventId)
                    .firstNotNullOfOrNull { own[it.eventId] }
                    ?.let { event.eventId to it }
            }.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Collections are events, so their covers come from the same map. */
    val coversByGroup: StateFlow<Map<Long, inga.bpmetrics.library.Cover>> get() = coversByEvent

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
        val byGroup = summaries.groupBy { it.event.parentId }
        groups.map { group ->
            // The whole subtree, not just what this collection holds directly. A festival that
            // holds nothing but days has no events of its own, and reporting that as "0 events,
            // 0 recordings" describes the row rather than the thing the row stands for.
            val subtree = inga.bpmetrics.library.EventTree.descendantsOf(groups, group.eventId)
            GroupSummary(
                group = group,
                // `eventId`, not `groupId`. A collection is an event, so its own id is `eventId`;
                // `groupId` is the legacy column, null on every collection — and `byGroup` is keyed
                // by parent, so looking up null handed every top-level event to every collection.
                events = byGroup[group.eventId].orEmpty(),
                allEvents = subtree.toList().flatMap { byGroup[it].orEmpty() },
                // Itself excluded — a collection is not inside itself.
                nestedCollectionCount = subtree.size - 1
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Events belonging to no group, shown alongside the groups so they are not lost. */
    val ungroupedEvents: StateFlow<List<EventSummary>> = events
        .map { summaries -> summaries.filter { it.event.parentId == null } }
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

    /** Types already used, offered by the editor so a vocabulary forms instead of scattering. */
    val eventTypesInUse: StateFlow<List<String>> = repository.eventTypesInUse()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Why the last window edit was refused, or null.
     *
     * Held rather than delivered as a one-shot message so the editor can keep it on screen next to
     * the field that caused it. A toast would be gone before the person finished reading the dates.
     */
    private val _windowError = MutableStateFlow<String?>(null)
    val windowError: StateFlow<String?> = _windowError.asStateFlow()

    fun clearWindowError() { _windowError.value = null }

    /**
     * Saves everything the editor holds, and reports whether it took.
     *
     * The window goes last: name and type always apply, and a refused window should not also throw
     * away a rename the user made in the same dialog.
     *
     * @param onDone true when the dialog can close. False leaves it open showing [windowError].
     */
    fun applyEventEdit(eventId: Long, edit: EventEdit, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            repository.renameEvent(eventId, edit.name)
            repository.setEventType(eventId, edit.type)

            when (val result = repository.setEventWindow(
                eventId, edit.windowStart, edit.windowEnd, edit.windowPeople
            )) {
                is LibraryRepository.WindowResult.Saved -> {
                    _windowError.value = null
                    onDone(true)
                }
                is LibraryRepository.WindowResult.Collides -> {
                    _windowError.value =
                        "That window overlaps \"${result.withName}\" for the same people. " +
                            "Name who each one applies to, or change the times."
                    onDone(false)
                }
                is LibraryRepository.WindowResult.Backwards -> {
                    _windowError.value = "A window needs a start and an end, in that order."
                    onDone(false)
                }
            }
        }
    }

    /** Who an event's window applies to, for the editor to show as already chosen. */
    fun windowPeople(eventId: Long): Flow<Set<Long>> = repository.windowPeople(eventId)

    // --- Collections, as sets ---

    /**
     * Every collection with what it resolves to today.
     *
     * Resolved rather than stored, through the same [inga.bpmetrics.library.EventTree] walk the
     * timeline and the export scope use. A set naming a festival covers whatever that festival
     * holds now — file a recording into one of its days and the set grows, with nothing re-added.
     */
    val collections: StateFlow<List<CollectionSummary>> = combine(
        repository.getAllCollections(),
        repository.allEventsInTree,
        repository.records,
        repository.allCollectionEventLinks(),
        repository.allCollectionRecordLinks(),
        peopleById
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val sets = args[0] as List<inga.bpmetrics.library.CollectionEntity>
        @Suppress("UNCHECKED_CAST")
        val events = args[1] as List<EventEntity>
        @Suppress("UNCHECKED_CAST")
        val records = args[2] as List<BpmRecord>
        @Suppress("UNCHECKED_CAST")
        val eventLinks = args[3] as List<inga.bpmetrics.library.CollectionEventCrossRef>
        @Suppress("UNCHECKED_CAST")
        val recordLinks = args[4] as List<inga.bpmetrics.library.CollectionRecordCrossRef>
        @Suppress("UNCHECKED_CAST")
        val people = args[5] as Map<Long, PersonEntity>
        val byId = records.associateBy { it.metadata.recordId }
        sets.map { set ->
            val within = inga.bpmetrics.library.CollectionScope.recordsIn(
                collectionId = set.collectionId,
                events = events,
                records = records.map { it.metadata },
                eventLinks = eventLinks,
                recordLinks = recordLinks
            )
            CollectionSummary(
                collection = set,
                people = within.mapNotNull { r -> r.personId?.let { people[it] } }.distinct(),
                events = inga.bpmetrics.library.CollectionScope.eventsIn(
                    set.collectionId, events, eventLinks
                ),
                records = within.mapNotNull { byId[it.recordId] }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createCollection(name: String, withRecords: Set<Long> = emptySet()) {
        viewModelScope.launch {
            val id = repository.createCollection(name)
            if (withRecords.isNotEmpty()) repository.addRecordsToCollection(id, withRecords)
            clearSelection()
        }
    }

    fun renameCollection(collectionId: Long, name: String) {
        viewModelScope.launch { repository.renameCollection(collectionId, name) }
    }

    fun deleteCollection(collectionId: Long) {
        viewModelScope.launch { repository.deleteCollection(collectionId) }
    }

    fun addSelectionToCollection(collectionId: Long) {
        viewModelScope.launch {
            repository.addRecordsToCollection(collectionId, _selectedRecordIds.value)
            clearSelection()
        }
    }

    /** Puts an event in a set, or takes it out. The timeline is unaffected either way. */
    fun toggleEventInCollection(collectionId: Long, eventId: Long, member: Boolean) {
        viewModelScope.launch {
            if (member) repository.addEventToCollection(collectionId, eventId)
            else repository.removeEventFromCollection(collectionId, eventId)
        }
    }

    fun collectionsHoldingEvent(eventId: Long): Flow<Set<Long>> =
        repository.collectionsHoldingEvent(eventId)

    /** Containers the user has opened in the timeline. */
    private val _expandedInTimeline = MutableStateFlow<Set<Long>>(emptySet())
    val expandedInTimeline: StateFlow<Set<Long>> = _expandedInTimeline.asStateFlow()

    fun toggleTimelineExpansion(eventId: Long) {
        _expandedInTimeline.value = if (eventId in _expandedInTimeline.value) {
            _expandedInTimeline.value - eventId
        } else {
            _expandedInTimeline.value + eventId
        }
    }

    /**
     * The library in chronological order, at whatever depth is open.
     *
     * Built by [LibraryTimeline], which is pure and is the only thing that decides this order.
     * Assembled here only because the rows need the recordings and the tree together.
     */
    val timeline: StateFlow<List<inga.bpmetrics.library.TimelineRow>> = combine(
        repository.allEventsInTree,
        repository.records,
        _expandedInTimeline
    ) { events, records, expanded ->
        inga.bpmetrics.library.LibraryTimeline.build(
            events = events,
            records = records.map { it.metadata },
            expandedIds = expanded
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Everything a timeline row needs about an event, keyed by id.
     *
     * The rows carry entities; the cards want counts, spans, people and readings. Resolved once for
     * the library rather than per row, and — importantly — through the same subtree walk the export
     * and analysis scopes use, so a card saying "12 recordings" and an export of that card contain
     * the same twelve.
     */
    val eventSummariesById: StateFlow<Map<Long, EventSummary>> = combine(
        repository.allEventsInTree,
        repository.records,
        peopleById
    ) { events, records, people ->
        val byEvent = records.groupBy { it.metadata.eventId }
        events.associate { event ->
            val within = inga.bpmetrics.library.EventTree.descendantsOf(events, event.eventId)
            val mine = within.flatMap { byEvent[it].orEmpty() }
            event.eventId to EventSummary(
                event = event,
                records = mine,
                people = mine.mapNotNull { r -> r.metadata.personId?.let { people[it] } }.distinct()
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Everything a recording can be filed into, in reading order with its depth.
     *
     * The whole tree, collections included. The picker used to offer only leaf events, so a
     * recording that belonged to a festival rather than to any particular set — the walk between
     * stages, the queue — had nowhere to go but unfiled. A container is a place a thing can be.
     */
    val eventPickerRows: StateFlow<List<Pair<EventEntity, Int>>> = repository.allEventsInTree
        .map { events ->
            inga.bpmetrics.library.EventTree.flatten(events).map { it.event to it.depth }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    fun restoreFromBackup(
        backup: LibraryBackup,
        context: android.content.Context,
        onDone: (RestoreResult) -> Unit
    ) {
        viewModelScope.launch {
            onDone(restoreBackup(backup, repository, context))
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
        context: android.content.Context,
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
                    settings = repository.getSettingsForBackup(),
                    // The whole tree, collections included — they are events carrying
                    // `type: "Collection"`, so they restore through the same path as everything
                    // else. `eventGroups` stays in the format only to read files written before
                    // the fold; nothing writes it any more.
                    events = repository.allEventsInTree.first(),
                    eventGroups = emptyList(),
                    // Tags on events, which since the fold includes tags on collections. These were
                    // never exported, so a restored library came back with every recording and none
                    // of the organisation above it.
                    eventTags = repository.getEventTagsForBackup(),
                    groupTags = emptyMap(),
                    // Cover and photograph bytes, inlined. A stored file name means nothing on the
                    // device a backup is restored onto.
                    readImage = { name ->
                        runCatching {
                            inga.bpmetrics.library.CoverStore.fileFor(context, name)
                                .takeIf { it.isFile }
                                ?.readBytes()
                        }.getOrNull()
                    }
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
     * Clears the cover on the event the selected recordings share.
     *
     * The counterpart to [setCoverForSelection], acting on the same place it wrote to. Refuses on
     * the same terms too: if the selection spans several events there is no one cover to remove,
     * and clearing all of them from a multi-selection is not what anyone means by this.
     */
    fun clearCoverForSelection(context: android.content.Context, onResult: (String) -> Unit) {
        val ids = _selectedRecordIds.value.toList()
        viewModelScope.launch {
            val eventId = repository.sharedEventOf(ids)
            if (eventId == null) {
                onResult("These recordings are not all in the same event.")
                return@launch
            }
            val event = repository.getEvent(eventId)
            if (event?.coverPath == null) {
                // Its recordings may still show a picture — the collection's — and removing that
                // is a decision about the collection, made where the collection is.
                onResult("${event?.displayName ?: "That event"} has no cover of its own.")
                return@launch
            }
            repository.clearCover(context, LibraryRepository.CoverOwner.Event(eventId))
            onResult("Cover removed from ${event.displayName}")
        }
    }

    /**
     * A picture for a whole collection, inherited by every event and recording nested under it.
     *
     * The broadest place a cover can go: set on "Coachella", every day and every set inside it
     * carries it until one of them sets its own. Nothing is written downward — see `CoverResolver`.
     */
    fun setGroupCover(
        context: android.content.Context,
        groupId: Long,
        source: android.net.Uri,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val hint = repository.getEventGroup(groupId)?.displayName ?: "collection"
            onResult(
                repository.setCover(
                    context = context,
                    owner = LibraryRepository.CoverOwner.Collection(groupId),
                    source = source,
                    nameHint = hint
                )
            )
        }
    }

    /** Re-frames the picture a collection already has, leaving the file alone. */
    fun setGroupCoverCrop(groupId: Long, cover: inga.bpmetrics.library.Cover) {
        viewModelScope.launch {
            repository.setCoverCrop(LibraryRepository.CoverOwner.Collection(groupId), cover)
        }
    }

    fun clearGroupCover(context: android.content.Context, groupId: Long) {
        viewModelScope.launch {
            repository.clearCover(context, LibraryRepository.CoverOwner.Collection(groupId))
        }
    }

    /**
     * Gives the selected recordings a cover, by putting it on the event they share.
     *
     * Not on each recording. Covers live on the event so that "everything from that night looks the
     * same" stays true without repeating the operation every time a recording arrives late from a
     * watch — see `CoverResolver`.
     *
     * That means this can only act when there *is* a shared event, and it says so rather than
     * quietly falling back to setting the cover on each recording individually. A bulk action that
     * does something other than what it says is worse than one that declines.
     */
    fun setCoverForSelection(
        context: android.content.Context,
        source: android.net.Uri,
        onResult: (String) -> Unit
    ) {
        val ids = _selectedRecordIds.value.toList()
        viewModelScope.launch {
            val eventId = repository.sharedEventOf(ids)
            if (eventId == null) {
                onResult(
                    "These recordings are not all in the same event. File them into one first, " +
                        "and its cover will apply to every recording in it."
                )
                return@launch
            }

            val name = repository.getEvent(eventId)?.displayName ?: "cover"
            val stored = repository.setCover(
                context = context,
                owner = LibraryRepository.CoverOwner.Event(eventId),
                source = source,
                nameHint = name
            )
            onResult(
                if (stored) "Cover set on $name" else "That image could not be read"
            )
            if (stored) clearSelection()
        }
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
    /**
     * Joins the current selection into one recording.
     *
     * Here rather than on a recording's own page, where it briefly lived: merging is inherently
     * about *several* recordings, and asking one of them to nominate the others meant a picker
     * that duplicated the multi-select the library already has.
     *
     * @param onDone called with what happened, since a refusal has a reason worth reading.
     */
    fun mergeSelectedRecords(deleteOriginals: Boolean, onDone: (String) -> Unit) {
        val chosen = repository.records.value.filter { it.metadata.recordId in _selectedRecordIds.value }
        val refusal = RecordMerge.refusal(chosen)
        if (refusal != null) {
            onDone(refusal)
            return
        }
        viewModelScope.launch {
            val merged = repository.mergeRecords(chosen, deleteOriginals)
            clearSelection()
            onDone(
                if (merged == null) {
                    "Could not merge those recordings."
                } else if (deleteOriginals) {
                    "Merged ${chosen.size} recordings."
                } else {
                    "Merged ${chosen.size} recordings; the originals were kept."
                }
            )
        }
    }

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
/**
 * Which library view is showing.
 *
 * [TIMELINE] is first and is the default: everything that happened, in the order it happened, at
 * whatever depth is open. It replaced an events list that sorted by container first and time
 * second, so a recording nobody had filed dropped out of the chronology into a section at the
 * bottom — even though the app knew exactly when it happened.
 *
 * The other two are ways of *not* reading chronologically: [RECORDINGS] is the flat list to filter
 * and multi-select in, [GROUPS] is the arbitrary sets a timeline cannot express.
 */
enum class LibraryViewMode { TIMELINE, RECORDINGS, GROUPS }

/**
 * A collection with what it resolves to, for a card to describe it without a second query.
 *
 * [events] are the ones the set *names*; [records] is everything those references reach, descendants
 * included. A card says "2 events · 47 recordings" from these, and both numbers come from the one
 * tree walk — so the card and an export of the same set contain the same forty-seven.
 */
data class CollectionSummary(
    val collection: inga.bpmetrics.library.CollectionEntity,
    val events: List<EventEntity>,
    val records: List<BpmRecord>,
    val people: List<PersonEntity> = emptyList()
) {
    val eventCount: Int get() = events.size
    val recordCount: Int get() = records.size
    /** May span months, which is the point of a set rather than a problem with one. */
    val span: TimeSpan? get() = records.spanOrNull()

    // Derived exactly as an event card derives them, so a set of one festival and that festival
    // report the same numbers.
    val peakBpm: Int? get() = records.mapNotNull { it.maxDataPoint?.bpm }.maxOrNull()?.toInt()
    val avgBpm: Int? get() = records.mapNotNull { it.metadata.avg }
        .takeIf { it.isNotEmpty() }?.average()?.toInt()
}

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

    /**
     * The peak anyone reached, and their average across the event.
     *
     * On the card because it is the one thing a heart rate app can say about a night that no other
     * app can, and because "Coachella Day 1, 4 people, peaked at 186" is a reason to open a card —
     * where a date and a count are only a label.
     *
     * Averaged over the recordings rather than over every data point: an event where one person
     * wore a watch all day and another for twenty minutes should not have the long recording decide
     * the number on its own.
     */
    val peakBpm: Int? get() = records.mapNotNull { it.maxDataPoint?.bpm }.maxOrNull()?.toInt()
    val avgBpm: Int? get() = records.mapNotNull { it.metadata.avg }
        .takeIf { it.isNotEmpty() }
        ?.average()
        ?.toInt()
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
    val group: EventEntity,
    val events: List<EventSummary>,
    /** Everything in the subtree, this collection's own events included. */
    val allEvents: List<EventSummary> = events,
    /** How many collections sit inside this one, at any depth. */
    val nestedCollectionCount: Int = 0
) {
    val eventCount: Int get() = allEvents.size
    val recordCount: Int get() = allEvents.sumOf { it.recordCount }
    val people: List<PersonEntity> get() = allEvents.flatMap { it.people }.distinct()

    /** The same figures as an event's, over everything nested inside this collection. */
    val peakBpm: Int? get() = allEvents.mapNotNull { it.peakBpm }.maxOrNull()
    val avgBpm: Int? get() = allEvents.mapNotNull { it.avgBpm }
        .takeIf { it.isNotEmpty() }
        ?.average()
        ?.toInt()
    val span: TimeSpan?
        get() = allEvents.mapNotNull { it.span }.takeIf { it.isNotEmpty() }
            ?.let { spans -> TimeSpan(spans.minOf { it.startMs }, spans.maxOf { it.endMs }) }
}

/** The span a set of recordings covers, or null when there are none. */
fun List<BpmRecord>.spanOrNull(): TimeSpan? =
    if (isEmpty()) null
    else TimeSpan(minOf { it.metadata.startTime }, maxOf { it.metadata.endTime })
