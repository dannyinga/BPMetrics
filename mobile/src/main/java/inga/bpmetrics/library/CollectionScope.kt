package inga.bpmetrics.library

/**
 * What a collection actually contains, once its references are followed.
 *
 * A set names events and recordings; it does not name their contents. "Festivals" holds Griztronics,
 * and Griztronics holds two days holding six sets holding forty recordings — so resolving a set has
 * to walk the tree, and it has to walk it *now* rather than at the moment something was added.
 * Storing the descendants would freeze the answer, and filing a recording into a day afterwards
 * would silently leave it out of "compare every festival".
 *
 * Pure, and pointed at the same [EventTree] the timeline, the export scope and the analysis scope
 * use. That is the entire discipline of this rework: a set is a different question asked of one
 * walk, not a second walk.
 */
object CollectionScope {

    /**
     * Every recording in a collection.
     *
     * The union of: recordings named directly, and every recording anywhere beneath a named event.
     * A recording reachable both ways appears once — an event can be in a set alongside one of its
     * own recordings, and counting it twice would make a set report more than the library holds.
     */
    fun recordsIn(
        collectionId: Long,
        events: List<EventEntity>,
        records: List<BpmRecordEntity>,
        eventLinks: List<CollectionEventCrossRef>,
        recordLinks: List<CollectionRecordCrossRef>
    ): List<BpmRecordEntity> {
        val within = eventLinks
            .asSequence()
            .filter { it.collectionId == collectionId }
            .flatMap { EventTree.descendantsOf(events, it.eventId).asSequence() }
            .toSet()

        val named = recordLinks
            .asSequence()
            .filter { it.collectionId == collectionId }
            .map { it.recordId }
            .toSet()

        return records.filter { it.recordId in named || it.eventId in within }
    }

    /**
     * The events a collection names, in the order they happened.
     *
     * The named ones only, not their descendants — a card listing "Griztronics, Bass Canyon" is
     * describing the set, and expanding that to every day and every artist would describe the
     * library instead.
     */
    fun eventsIn(
        collectionId: Long,
        events: List<EventEntity>,
        eventLinks: List<CollectionEventCrossRef>
    ): List<EventEntity> {
        val named = eventLinks.filter { it.collectionId == collectionId }.map { it.eventId }.toSet()
        return events.filter { it.eventId in named }
            .sortedByDescending { it.windowStart ?: it.createdAt }
    }

    /**
     * The span a collection covers, from its earliest recording to its latest.
     *
     * Null when it holds nothing with a time. A set spanning months is normal and is rather the
     * point — this is a fact about the set, not a claim that anything happened in between.
     */
    fun spanOf(records: List<BpmRecordEntity>): TimeSpan? =
        if (records.isEmpty()) null
        else TimeSpan(records.minOf { it.startTime }, records.maxOf { it.endTime })

    /** Whether a collection names this event directly. What a picker shows as ticked. */
    fun holdsEvent(
        collectionId: Long,
        eventId: Long,
        eventLinks: List<CollectionEventCrossRef>
    ): Boolean = eventLinks.any { it.collectionId == collectionId && it.eventId == eventId }
}
