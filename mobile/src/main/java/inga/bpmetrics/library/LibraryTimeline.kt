package inga.bpmetrics.library

/** One thing in the library timeline: a container, or a recording sitting loose in one. */
sealed interface TimelineEntry {
    val sortKey: Long

    data class Event(val event: EventEntity, override val sortKey: Long) : TimelineEntry
    data class Recording(val record: BpmRecordEntity) : TimelineEntry {
        override val sortKey: Long get() = record.startTime
    }
}

/**
 * A row of the timeline, and how deep it sits.
 *
 * @property hasChildren Whether opening it would reveal anything. The chevron is drawn from this
 * rather than from "is it an event", so an empty event does not offer to expand into nothing.
 */
data class TimelineRow(
    val entry: TimelineEntry,
    val depth: Int,
    val hasChildren: Boolean = false
)

/**
 * The library as one chronological list.
 *
 * The primary view. Everything that happened, in the order it happened, at whatever depth the user
 * has opened — a festival, its days, an artist, and the recordings inside; with a loose recording
 * from the same afternoon sitting between them at the level it belongs to rather than exiled to an
 * "unfiled" section at the bottom.
 *
 * That exile was the actual complaint. A recording nobody had filed was not *missing* information —
 * it happened at a known time, in a known order relative to everything else — but the old view
 * sorted by container first and time second, so it fell out of the timeline entirely.
 *
 * **Pure, and the only thing that decides this order.** It takes the tree, the recordings and what
 * is open, and returns rows. No I/O, no Compose, so the ordering rules below can be asserted
 * directly — which matters because "why is this above that" is a question a list cannot answer for
 * itself, and because a second implementation of this ordering is precisely how the app has gone
 * wrong four times.
 */
object LibraryTimeline {

    /**
     * Rows to draw, in order.
     *
     * @param events The whole tree, collections included. A filtered list breaks the chain in the
     *   middle and orphans everything below the gap.
     * @param records Every recording. Membership is read from `eventId`, which
     *   [LibraryRepository.reconcileMembership] is the only writer of.
     * @param expandedIds Containers the user has opened. Children of anything closed are omitted
     *   rather than hidden, so the list length matches what is on screen.
     */
    fun build(
        events: List<EventEntity>,
        records: List<BpmRecordEntity>,
        expandedIds: Set<Long> = emptySet()
    ): List<TimelineRow> {
        val childEvents = events.groupBy { it.parentId }
        val childRecords = records.groupBy { it.eventId }

        // Once, for the whole tree, rather than per row. An event's key is its window if it has
        // one and the span of its contents otherwise — a day that ends at midnight ends at
        // midnight however long a watch was left running, but an event with no window is only ever
        // as early as the first thing inside it.
        val keys = mutableMapOf<Long, Long>()
        fun keyOf(eventId: Long, guard: Set<Long> = emptySet()): Long = keys.getOrPut(eventId) {
            if (eventId in guard) return@getOrPut Long.MAX_VALUE
            val event = events.firstOrNull { it.eventId == eventId }
                ?: return@getOrPut Long.MAX_VALUE
            event.windowStart?.let { return@getOrPut it }

            val own = childRecords[eventId].orEmpty().minOfOrNull { it.startTime }
            val beneath = childEvents[eventId].orEmpty()
                .minOfOrNull { keyOf(it.eventId, guard + eventId) }

            // An empty event with no window sorts to the end rather than to the epoch. It has not
            // happened yet as far as the library can tell, and 1970 would bury it below everything.
            listOfNotNull(own, beneath).minOrNull() ?: Long.MAX_VALUE
        }

        // Which events the tree can reach from the top, regardless of what is open.
        //
        // Distinct from what gets *drawn*, and the distinction matters: a child of a collapsed
        // container is not emitted but is perfectly reachable. Conflating the two made the orphan
        // rescue below treat every collapsed child as lost and re-emit it at the top level, so
        // closing a festival scattered its days across the library.
        val reachable = mutableSetOf<Long>()
        run {
            fun mark(parentId: Long?) {
                childEvents[parentId].orEmpty().forEach {
                    if (reachable.add(it.eventId)) mark(it.eventId)
                }
            }
            mark(null)
        }

        val out = mutableListOf<TimelineRow>()

        fun emit(parentId: Long?, depth: Int, seen: MutableSet<Long>) {
            val here = buildList {
                childEvents[parentId].orEmpty()
                    .filter { seen.add(it.eventId) }
                    .forEach { add(TimelineEntry.Event(it, keyOf(it.eventId))) }
                childRecords[parentId].orEmpty()
                    .forEach { add(TimelineEntry.Recording(it)) }
            }

            // Newest first at the top, oldest first once inside something. Both are what someone
            // is actually doing: scanning the library means looking for the most recent night,
            // and opening a festival means reading Day 1 then Day 2. The app already ordered its
            // two lists this way, so this preserves it rather than inventing a third convention.
            val ordered =
                if (depth == 0) here.sortedByDescending { it.sortKey }
                else here.sortedBy { it.sortKey }

            ordered.forEach { entry ->
                when (entry) {
                    is TimelineEntry.Recording -> out += TimelineRow(entry, depth)
                    is TimelineEntry.Event -> {
                        val id = entry.event.eventId
                        val hasChildren = childEvents[id].orEmpty().isNotEmpty() ||
                            childRecords[id].orEmpty().isNotEmpty()
                        out += TimelineRow(entry, depth, hasChildren)
                        if (id in expandedIds) emit(id, depth + 1, seen)
                    }
                }
            }
        }

        val seen = mutableSetOf<Long>()
        emit(null, 0, seen)

        // An event whose parent is missing, or caught in a cycle, is never reached from the root.
        // Shown at the top level rather than dropped: unreachable in the list means unrepairable
        // through the UI, which is worse than appearing in the wrong place.
        events.filter { it.eventId !in reachable }
            .sortedByDescending { keyOf(it.eventId) }
            .forEach { orphan ->
                if (!seen.add(orphan.eventId)) return@forEach
                val id = orphan.eventId
                out += TimelineRow(
                    TimelineEntry.Event(orphan, keyOf(id)),
                    depth = 0,
                    hasChildren = childEvents[id].orEmpty().isNotEmpty() ||
                        childRecords[id].orEmpty().isNotEmpty()
                )
                if (id in expandedIds) emit(id, 1, seen)
            }

        return out
    }

    /**
     * Every container above [eventId], for opening the list straight to something.
     *
     * Following a link to a set has to open the festival and the day as well, or the row is
     * rendered into a list that does not show it.
     */
    fun expansionFor(events: List<EventEntity>, eventId: Long): Set<Long> =
        EventTree.ancestryOf(events, eventId).mapTo(mutableSetOf()) { it.eventId }
}
