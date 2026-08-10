package inga.bpmetrics.library

/**
 * A set of recordings, named by how it is chosen.
 *
 * Everything the app can show numbers for is one of these. A recording is a scope of one; an event
 * is its subtree; a collection is whatever it names plus whatever its rule selects; a filter is the
 * question currently in the bar. Sprint 5 folds four detail screens into one on the back of that —
 * a detail screen is a scope, its numbers, and a split — and the fold only works if there is one
 * answer to "what is in this".
 */
sealed interface ScopeRef {

    /** One recording. The narrowest scope there is. */
    data class Recording(val recordId: Long) : ScopeRef

    /** An event and everything beneath it. */
    data class Event(val eventId: Long) : ScopeRef

    /** A collection: members ∪ rule − exclusions. */
    data class Collection(val collectionId: Long) : ScopeRef

    /** A question not yet saved — whatever the filter bar currently says. */
    data class Query(val filter: FilterState) : ScopeRef

    /**
     * Recordings picked out by hand.
     *
     * "These three, which overlapped" is not a filter and never will be, so it gets its own way of
     * being named. Everything downstream treats it like any other scope — which is the point: once
     * a detail page is a scope, its numbers and a split, comparing a hand-picked set stops being a
     * separate feature with a separate screen.
     */
    data class Selection(val recordIds: Set<Long>) : ScopeRef
}

/**
 * Everything the resolver reads, gathered once.
 *
 * A snapshot rather than a repository, so [Scope] stays pure and testable and cannot accidentally
 * hit the database once per row.
 */
data class Library(
    val records: List<BpmRecord> = emptyList(),
    val events: List<EventEntity> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val collectionEvents: List<CollectionEventCrossRef> = emptyList(),
    val collectionRecords: List<CollectionRecordCrossRef> = emptyList(),
    val filterContext: FilterContext = FilterContext()
)

/**
 * What a scope contains, resolved now rather than when it was made.
 *
 * **The only implementation.** Membership had two: a `CollectionScope.recordsIn` used when a
 * collection was opened, and a branch of the library filter that compared a collection id against
 * an event's *parent event* id. They answered differently, which meant filtering by a collection
 * and opening that collection showed different recordings — and the filter's answer was nonsense,
 * because the two ids came from different tables. Pointing both at this is the fix, and having one
 * function is what stops it recurring.
 *
 * Pure, and pointed at the same [EventTree] the timeline and the export scope walk.
 */
object Scope {

    /**
     * The recordings in a scope.
     *
     * @param visiting Collections already being resolved further up the call chain. A collection's
     *   rule may name another collection, so resolution can loop; a collection reached twice
     *   contributes nothing rather than recursing forever. The editor refuses to *create* a cycle,
     *   the same discipline as [EventTree.wouldCycle] — this is the belt to that's braces, because
     *   a cycle arriving through a restored backup would otherwise hang the library.
     */
    fun recordsIn(
        ref: ScopeRef,
        library: Library,
        visiting: Set<Long> = emptySet()
    ): List<BpmRecord> = when (ref) {
        is ScopeRef.Recording ->
            library.records.filter { it.metadata.recordId == ref.recordId }

        is ScopeRef.Selection ->
            library.records.filter { it.metadata.recordId in ref.recordIds }

        is ScopeRef.Event -> {
            val within = EventTree.descendantsOf(library.events, ref.eventId)
            library.records.filter { it.metadata.eventId in within }
        }

        is ScopeRef.Query -> {
            // Any collection the question names is resolved here, recursively, rather than assumed
            // to be sitting in the context already. A caller filtering the whole library supplies
            // the whole map up front; a rule inside a collection does not, and resolving it lazily
            // is what lets one smart collection be defined in terms of another.
            val named = ref.filter.selectedGroupIds.associateWith { id ->
                recordsIn(ScopeRef.Collection(id), library, visiting)
                    .mapTo(mutableSetOf()) { it.metadata.recordId }
            }
            LibraryFilter.apply(
                library.records,
                ref.filter,
                library.filterContext.copy(
                    // Whatever the caller already knew wins, so the full map computed once for the
                    // library is not thrown away and recomputed per query.
                    recordIdsByCollection = named + library.filterContext.recordIdsByCollection
                )
            )
        }

        is ScopeRef.Collection -> {
            // A collection reached twice through its own rule contributes nothing the second time.
            if (ref.collectionId in visiting) emptyList() else {
                val set = library.collections.firstOrNull { it.collectionId == ref.collectionId }

                // Named events resolve through the tree, not to themselves. A set holding
                // Griztronics holds every day and every artist inside it, because references are
                // followed at the point of asking — freezing the descendants would silently leave
                // out a recording filed into one of its days afterwards.
                val fromEvents = library.collectionEvents
                    .asSequence()
                    .filter { it.collectionId == ref.collectionId }
                    .flatMap { EventTree.descendantsOf(library.events, it.eventId).asSequence() }
                    .toSet()

                val named = library.collectionRecords
                    .asSequence()
                    .filter { it.collectionId == ref.collectionId }
                    .map { it.recordId }
                    .toSet()

                // The rule, if it has one. This is what makes a smart collection — "every
                // Subtronics recording" — the same kind of object as a hand-made one, and lets a
                // collection be both: the ones the question finds, plus the ones you added anyway.
                val fromRule = set?.rule()
                    ?.let { rule ->
                        recordsIn(
                            ScopeRef.Query(rule),
                            library,
                            visiting + ref.collectionId
                        )
                    }
                    ?.mapTo(mutableSetOf()) { it.metadata.recordId }
                    .orEmpty()

                // Struck out by hand, after everything else. A rule you have to abandon because one
                // recording does not belong is a rule that stops being worth saving.
                val struck = set?.exclusions().orEmpty()

                library.records.filter {
                    val id = it.metadata.recordId
                    id !in struck &&
                        (id in named || id in fromRule || it.metadata.eventId in fromEvents)
                }
            }
        }
    }

    /**
     * The events a collection names directly, in the order they happened.
     *
     * The named ones only, not their descendants — a card reading "Griztronics, Bass Canyon" is
     * describing the set, and expanding that to every day and every artist would describe the
     * library instead.
     */
    fun eventsIn(
        collectionId: Long,
        events: List<EventEntity>,
        links: List<CollectionEventCrossRef>
    ): List<EventEntity> {
        val named = links.filter { it.collectionId == collectionId }.mapTo(mutableSetOf()) { it.eventId }
        return events.filter { it.eventId in named }
            .sortedByDescending { it.windowStart ?: it.createdAt }
    }

    /**
     * What every collection holds, for the filter to match against.
     *
     * Resolved in one pass rather than per collection per record. The result is what
     * [FilterContext.recordIdsByCollection] carries, which is how the filter's collection term and
     * the collection screen come to agree by construction.
     */
    fun recordIdsByCollection(library: Library): Map<Long, Set<Long>> =
        library.collections.associate { set ->
            set.collectionId to recordsIn(ScopeRef.Collection(set.collectionId), library)
                .mapTo(mutableSetOf()) { it.metadata.recordId }
        }

    /**
     * The span a set of recordings covers.
     *
     * Null when it holds nothing. A set spanning months is normal and rather the point — this is a
     * fact about the set, not a claim that anything happened in between.
     */
    fun spanOf(records: List<BpmRecordEntity>): TimeSpan? =
        if (records.isEmpty()) null
        else TimeSpan(records.minOf { it.startTime }, records.maxOf { it.endTime })

    /** Whether a collection names this event directly. What a picker shows as ticked. */
    fun holdsEvent(
        collectionId: Long,
        eventId: Long,
        links: List<CollectionEventCrossRef>
    ): Boolean = links.any { it.collectionId == collectionId && it.eventId == eventId }

    /**
     * Whether pointing [collectionId]'s rule at [target] would close a loop.
     *
     * Asked before saving a rule. Resolution survives a cycle by refusing to revisit, but a library
     * where "Festivals" contains "Best of" contains "Festivals" is not something anyone meant, and
     * it is far easier to explain at the moment it is being created.
     */
    fun ruleWouldCycle(
        collectionId: Long,
        target: Long,
        collections: List<CollectionEntity>
    ): Boolean {
        if (collectionId == target) return true
        val seen = mutableSetOf<Long>()
        fun reaches(from: Long): Boolean {
            if (!seen.add(from)) return false
            val rule = collections.firstOrNull { it.collectionId == from }?.rule() ?: return false
            return rule.selectedGroupIds.any { it == collectionId || reaches(it) }
        }
        return reaches(target)
    }
}
