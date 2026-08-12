package inga.bpmetrics.library

/**
 * The event timeline, as a tree, and the one definition of what is inside what.
 *
 * Every walk of containment in this app used to be written where it was needed. Four separate bugs
 * came from that: a count of a collection's contents disagreeing with its contents, most recently
 * the export picker reporting "0 events" for a collection of collections while the resolution two
 * hundred lines away walked the subtree correctly and exported everything.
 *
 * Both were answering "what is inside this?". This is that answer, once.
 *
 * Pure — no Room, no Android — so the rules can be checked without a device. That matters more than
 * usual here, because the failure mode of a containment bug is a plausible number rather than a
 * crash, and a plausible number is one nobody looks at twice.
 */
object EventTree {

    /**
     * A node and how deep it sits, for rendering a tree as a list.
     *
     * [hasChildren] is about the *tree*, not about what is currently on screen: a row needs to know
     * whether opening it would reveal anything, and a collapsed row has no visible children to
     * count. A chevron that expands into nothing reads as a broken row.
     */
    data class Node(val event: EventEntity, val depth: Int, val hasChildren: Boolean = false)

    /**
     * The direct children of [parentId], in the order they happened.
     *
     * By window where one exists and by creation otherwise, because a list of a festival's days
     * should read as the festival did.
     */
    fun childrenOf(
        all: List<EventEntity>,
        parentId: Long?,
        /**
         * When each event actually happened, from [startsOf].
         *
         * Without it the fallback is `createdAt`, which is the order events were *typed in* — and
         * most events have no window, so a picker ordered that way reads as creation order however
         * chronological it claims to be. The library's timeline has always used the real answer;
         * this is what lets everything else use the same one.
         */
        startBy: Map<Long, Long> = emptyMap()
    ): List<EventEntity> =
        all.filter { it.parentId == parentId }
            .sortedBy { startBy[it.eventId] ?: it.windowStart ?: it.createdAt }

    /**
     * When each event happened, for ordering: its window, else the earliest thing beneath it.
     *
     * A window wins where there is one — a day that starts at nine starts at nine, whatever time
     * the first watch happened to be switched on. Otherwise an event is only ever as early as the
     * first recording inside it, at any depth, because a festival's own recordings usually sit on
     * its days rather than on the festival.
     *
     * An empty event with no window sorts to the *end* rather than to the epoch: as far as the
     * library can tell it has not happened, and 1970 would bury it under everything.
     */
    fun startsOf(
        all: List<EventEntity>,
        records: List<BpmRecordEntity>
    ): Map<Long, Long> {
        val childEvents = all.groupBy { it.parentId }
        val ownRecords = records.groupBy { it.eventId }
        val keys = mutableMapOf<Long, Long>()

        fun keyOf(eventId: Long, guard: Set<Long>): Long = keys.getOrPut(eventId) {
            // Cycle-guarded. A cycle should be impossible, and this is the code that would hang
            // rather than throw if one existed — while a list is being drawn.
            if (eventId in guard) return@getOrPut Long.MAX_VALUE
            all.firstOrNull { it.eventId == eventId }?.windowStart?.let { return@getOrPut it }

            val own = ownRecords[eventId].orEmpty().minOfOrNull { it.startTime }
            val beneath = childEvents[eventId].orEmpty()
                .minOfOrNull { keyOf(it.eventId, guard + eventId) }

            listOfNotNull(own, beneath).minOrNull() ?: Long.MAX_VALUE
        }

        all.forEach { keyOf(it.eventId, emptySet()) }
        return keys
    }

    /**
     * How many recordings each event holds, its whole subtree included.
     *
     * The count that has been got wrong four times in this codebase, each time the same way:
     * counting the recordings filed *directly* on an event. Recordings hang off the deepest thing
     * that claims them, so a festival almost never holds any itself — its days do, or its sets do.
     * Counting directly therefore reports a festival as empty while its own page, resolved through
     * [descendantsOf] two hundred lines away, shows forty.
     *
     * One pass over the recordings rather than a subtree walk per event: each recording credits
     * every event above it, which is the same answer and is linear in the depth of the tree.
     * [ancestryOf] is cycle-guarded, so a tangled parent cannot hang a list being drawn.
     */
    fun recordCountsByEvent(
        all: List<EventEntity>,
        records: List<BpmRecordEntity>
    ): Map<Long, Int> {
        val counts = mutableMapOf<Long, Int>()
        records.forEach { record ->
            val filedAs = record.eventId ?: return@forEach
            ancestryOf(all, filedAs).forEach { ancestor ->
                counts[ancestor.eventId] = (counts[ancestor.eventId] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * [eventId] and everything beneath it.
     *
     * Includes itself, because every caller wanting "the subtree" also wants the root of it — and
     * the one that did not was the export scope, which is how a collection holding only collections
     * came to export nothing.
     *
     * Cycle-guarded. A cycle should be impossible, but a walk up or down parents is the code that
     * *hangs* rather than throws if one exists, and this runs while a list is being drawn.
     */
    fun descendantsOf(all: List<EventEntity>, eventId: Long): Set<Long> {
        val byParent = all.groupBy { it.parentId }
        val found = linkedSetOf(eventId)
        val queue = ArrayDeque(listOf(eventId))

        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            byParent[next].orEmpty().forEach { child ->
                if (found.add(child.eventId)) queue.addLast(child.eventId)
            }
        }
        return found
    }

    /**
     * [eventId] and every event above it, innermost first.
     *
     * The order is what makes nearest-wins inheritance work: the first entry carrying a tag or a
     * cover is the one that applies.
     */
    /**
     * An event named by where it sits: "Subtronics | Day 1 | Griztronics at the Gorge".
     *
     * Short names are what make the library readable — every festival has a Day 1, and prefixing
     * each one with its festival turns a list of days into a list of paragraphs. But a short name
     * is only unique in its own branch, and an analysis puts branches side by side: two events both
     * called "Subtronics", from different weekends, are two different nights and reading as one is
     * a wrong answer rather than a cosmetic one.
     *
     * So the tree keeps the short name and anything comparing across it asks for this. Innermost
     * first, because the thing itself is what you are looking for and the ancestry is what tells
     * two of them apart.
     *
     * Cycle-guarded by [ancestryOf], which this is a rendering of rather than a second walk.
     */
    fun qualifiedNameOf(
        all: List<EventEntity>,
        eventId: Long,
        separator: String = "  |  ",
        /**
         * Outermost first, as a path, rather than innermost first.
         *
         * The two orders are for two jobs. A *comparison lane* wants the thing itself first,
         * because the thing is what you are looking for and the ancestry is only there to tell two
         * of them apart. A *title* wants the path: "Griz | Day 2 | Griztronics" reads as where you
         * were, and "Griztronics | Day 2 | Griz" reads as a breadcrumb printed backwards.
         */
        outermostFirst: Boolean = false
    ): String = ancestryOf(all, eventId)
        .let { if (outermostFirst) it.reversed() else it }
        .joinToString(separator) { it.displayName }

    fun ancestryOf(all: List<EventEntity>, eventId: Long): List<EventEntity> {
        val byId = all.associateBy { it.eventId }
        val chain = mutableListOf<EventEntity>()
        val seen = mutableSetOf<Long>()

        var current = byId[eventId]
        while (current != null && seen.add(current.eventId)) {
            chain += current
            current = current.parentId?.let { byId[it] }
        }
        return chain
    }

    /**
     * The innermost event containing every one of [eventIds], or null if they share none.
     *
     * What a caption needs when one thing covers recordings filed in different places — a clip that
     * ran across the end of one set and the start of the next. Naming it after whichever happened
     * to come first in the list is arbitrary and wrong half the time; naming it after nothing
     * throws away the day, or the festival, that they genuinely do share.
     *
     * A single unfiled recording in the group answers null for the whole group. That is not a
     * degenerate case being given up on: nothing contains all of them, and a title claiming
     * otherwise would be putting an event's name on a picture holding data from outside it.
     */
    fun commonAncestorOf(all: List<EventEntity>, eventIds: Collection<Long?>): Long? {
        val chains = eventIds.map { id ->
            id?.let { ancestryOf(all, it).map(EventEntity::eventId) }?.ifEmpty { null }
                ?: return null
        }
        if (chains.isEmpty()) return null

        // Ancestry is innermost first, so the first entry of any one chain that every other chain
        // also contains is the deepest shared container.
        val others = chains.drop(1).map { it.toSet() }
        return chains.first().firstOrNull { candidate -> others.all { candidate in it } }
    }

    /**
     * Whether [candidateParent] is [eventId] or sits beneath it.
     *
     * The guard against building a cycle in the first place: an event cannot be moved inside its
     * own descendant.
     */
    fun wouldCycle(all: List<EventEntity>, eventId: Long, candidateParent: Long?): Boolean =
        candidateParent != null && candidateParent in descendantsOf(all, eventId)

    /**
     * Every event in reading order, each with its depth.
     *
     * Depth-first from the top, siblings in time order, so a list rendered from this reads as the
     * tree it is rather than needing the caller to work the shape out again.
     */
    fun flatten(
        all: List<EventEntity>,
        /**
         * Which events are open, or null for all of them.
         *
         * Null is the old behaviour and stays the default, because two callers genuinely want the
         * whole tree at once — a parent picker has to offer every possible parent, and a scope walk
         * is not a list anybody is scrolling. A *picker* wants the opposite: a library of forty
         * events opening as forty rows is a wall, and the festivals people are choosing between are
         * the six at the top.
         */
        expanded: Set<Long>? = null,
        /** Reverses each level. The order within a level is chronological either way. */
        newestFirst: Boolean = false,
        /** When each event happened. See [startsOf] and [childrenOf]. */
        startBy: Map<Long, Long> = emptyMap()
    ): List<Node> {
        val result = mutableListOf<Node>()
        val seen = mutableSetOf<Long>()

        // Everything the tree can reach, whether or not it is currently on screen. Kept apart from
        // what was *emitted*, because collapsing legitimately leaves most of the tree unemitted —
        // and the recovery pass below would otherwise dump every collapsed child at depth 0, which
        // is the opposite of collapsing.
        val reachable = mutableSetOf<Long>()
        fun mark(parentId: Long?) {
            childrenOf(all, parentId, startBy)
                .forEach { if (reachable.add(it.eventId)) mark(it.eventId) }
        }
        mark(null)

        fun walk(parentId: Long?, depth: Int) {
            val children = childrenOf(all, parentId, startBy)
                .let { if (newestFirst) it.reversed() else it }
            children.forEach { event ->
                if (!seen.add(event.eventId)) return@forEach
                result += Node(event, depth, childrenOf(all, event.eventId).isNotEmpty())
                if (expanded == null || event.eventId in expanded) walk(event.eventId, depth + 1)
            }
        }

        walk(null, 0)

        // Anything a cycle kept out of the walk still has to appear, or it becomes unreachable in
        // the UI and cannot be repaired by the person looking at it.
        all.filter { it.eventId !in reachable }
            .sortedBy { startBy[it.eventId] ?: it.windowStart ?: it.createdAt }
            .let { if (newestFirst) it.reversed() else it }
            .forEach { if (seen.add(it.eventId)) result += Node(it, 0) }

        return result
    }

    /**
     * When an event happened: its own window, or the span of everything beneath it.
     *
     * A window wins where there is one — a day that ends at midnight ends at midnight, whatever
     * time the last watch happened to stop.
     */
    fun spanOf(
        all: List<EventEntity>,
        eventId: Long,
        recordings: List<BpmRecordEntity>,
        membership: Map<Long, Long?>
    ): TimeSpan? {
        val event = all.firstOrNull { it.eventId == eventId } ?: return null
        if (event.windowStart != null && event.windowEnd != null) {
            return TimeSpan(event.windowStart, event.windowEnd)
        }

        val inScope = descendantsOf(all, eventId)
        val its = recordings.filter { membership[it.recordId] in inScope }
        if (its.isEmpty()) return null

        return TimeSpan(its.minOf { it.startTime }, its.maxOf { it.endTime })
    }
}
