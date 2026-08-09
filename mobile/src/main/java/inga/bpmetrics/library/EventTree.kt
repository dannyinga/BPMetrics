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

    /** A node and how deep it sits, for rendering a tree as a list. */
    data class Node(val event: EventEntity, val depth: Int)

    /**
     * The direct children of [parentId], in the order they happened.
     *
     * By window where one exists and by creation otherwise, because a list of a festival's days
     * should read as the festival did.
     */
    fun childrenOf(all: List<EventEntity>, parentId: Long?): List<EventEntity> =
        all.filter { it.parentId == parentId }
            .sortedBy { it.windowStart ?: it.createdAt }

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
        separator: String = "  |  "
    ): String = ancestryOf(all, eventId).joinToString(separator) { it.displayName }

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
    fun flatten(all: List<EventEntity>): List<Node> {
        val result = mutableListOf<Node>()
        val seen = mutableSetOf<Long>()

        fun walk(parentId: Long?, depth: Int) {
            childrenOf(all, parentId).forEach { event ->
                if (!seen.add(event.eventId)) return@forEach
                result += Node(event, depth)
                walk(event.eventId, depth + 1)
            }
        }

        walk(null, 0)

        // Anything a cycle kept out of the walk still has to appear, or it becomes unreachable in
        // the UI and cannot be repaired by the person looking at it.
        all.filter { it.eventId !in seen }
            .sortedBy { it.windowStart ?: it.createdAt }
            .forEach { result += Node(it, 0) }

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
