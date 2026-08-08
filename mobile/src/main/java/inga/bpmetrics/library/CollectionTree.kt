package inga.bpmetrics.library

/**
 * The rules for nesting collections.
 *
 * Kept pure and separate from the repository because the failure modes here are not the kind you
 * notice: a cycle does not throw, it hangs — every walk of the tree runs forever, on a background
 * thread, and the app simply stops responding with nothing in the log to say why. That is worth
 * being able to test without a database.
 *
 * A recording still belongs to exactly one event, and events do not nest. Only the container does.
 * "Subtronics inside Coachella Day 1 inside Coachella" is strict containment, so nesting the
 * container expresses it exactly; letting a recording belong to three events would express it by
 * duplication, and duplication is what makes "which event is this recording's?" unanswerable.
 */
object CollectionTree {

    /**
     * How deep nesting may go, counting the top level as 1.
     *
     * Three covers the case this exists for — festival, day, and the events inside it — with a
     * level spare. A cap at all is what stops a mis-drag turning the library into a corridor, and
     * a browsable tree stops being browsable long before it stops being representable.
     */
    const val MAX_DEPTH = 4

    /** Whether [groupId] may be filed under [parentGroupId]. */
    fun canReparent(all: List<EventGroupEntity>, groupId: Long, parentGroupId: Long?): Boolean {
        if (parentGroupId == null) return true
        if (parentGroupId == groupId) return false
        if (all.none { it.groupId == groupId }) return false
        if (all.none { it.groupId == parentGroupId }) return false

        // The parent must not already be inside the thing being filed, or the two would enclose
        // each other and every walk from either would run forever.
        if (parentGroupId in descendantsOf(all, groupId)) return false

        // Depth of the parent, plus this subtree's own height, must still fit.
        val parentDepth = depthOf(all, parentGroupId)
        return parentDepth + heightOf(all, groupId) <= MAX_DEPTH
    }

    /**
     * A collection and everything nested inside it, itself included.
     *
     * Guarded against cycles even though [canReparent] should prevent them: this walks data that
     * may have been written by an older build, restored from a backup, or edited by hand, and
     * "should not happen" is not a reason to hang.
     */
    fun descendantsOf(all: List<EventGroupEntity>, groupId: Long): Set<Long> {
        val childrenByParent = all.groupBy { it.parentGroupId }
        val found = linkedSetOf(groupId)
        val queue = ArrayDeque(listOf(groupId))

        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            childrenByParent[next].orEmpty().forEach { child ->
                // `add` returning false means it has been seen, which is the cycle guard.
                if (found.add(child.groupId)) queue.addLast(child.groupId)
            }
        }
        return found
    }

    /** How many levels down [groupId] sits, the top level being 1. */
    fun depthOf(all: List<EventGroupEntity>, groupId: Long): Int {
        val byId = all.associateBy { it.groupId }
        var depth = 1
        var current = byId[groupId]?.parentGroupId
        val seen = mutableSetOf(groupId)

        while (current != null && seen.add(current) && depth <= MAX_DEPTH + 1) {
            depth++
            current = byId[current]?.parentGroupId
        }
        return depth
    }

    /** How many levels the subtree rooted at [groupId] occupies, itself being 1. */
    fun heightOf(all: List<EventGroupEntity>, groupId: Long): Int {
        val childrenByParent = all.groupBy { it.parentGroupId }
        val seen = mutableSetOf(groupId)

        fun walk(id: Long, depth: Int): Int {
            if (depth > MAX_DEPTH + 1) return depth
            val children = childrenByParent[id].orEmpty().filter { seen.add(it.groupId) }
            if (children.isEmpty()) return depth
            return children.maxOf { walk(it.groupId, depth + 1) }
        }
        return walk(groupId, 1)
    }

    /** The chain from the top down to [groupId], for a breadcrumb. */
    fun ancestryOf(all: List<EventGroupEntity>, groupId: Long): List<EventGroupEntity> {
        val byId = all.associateBy { it.groupId }
        val chain = ArrayDeque<EventGroupEntity>()
        var current = byId[groupId]
        val seen = mutableSetOf<Long>()

        while (current != null && seen.add(current.groupId)) {
            chain.addFirst(current)
            current = current.parentGroupId?.let { byId[it] }
        }
        return chain.toList()
    }

    /**
     * Every collection in reading order, each with how deep it sits.
     *
     * Depth-first from the top, siblings in the order given, so a list rendered from this reads as
     * the tree it is rather than needing the caller to work the shape out again.
     */
    fun flatten(all: List<EventGroupEntity>): List<Node> {
        val childrenByParent = all.groupBy { it.parentGroupId }
        val out = mutableListOf<Node>()
        val seen = mutableSetOf<Long>()

        fun emit(group: EventGroupEntity, depth: Int) {
            if (!seen.add(group.groupId) || depth > MAX_DEPTH) return
            out += Node(group, depth)
            childrenByParent[group.groupId].orEmpty().forEach { emit(it, depth + 1) }
        }

        // Anything whose parent is missing is treated as top level rather than dropped: a
        // collection that vanishes from the list because of a dangling id is unrecoverable
        // through the UI, which is a worse failure than showing it in the wrong place.
        val ids = all.map { it.groupId }.toSet()
        all.filter { it.parentGroupId == null || it.parentGroupId !in ids }.forEach { emit(it, 1) }
        // Anything left is inside a cycle. Shown at the top so it can be dragged back out.
        all.filterNot { it.groupId in seen }.forEach { emit(it, 1) }

        return out
    }

    /** A collection and how deep it sits, the top level being 1. */
    data class Node(val group: EventGroupEntity, val depth: Int)
}
