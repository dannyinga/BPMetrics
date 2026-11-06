package inga.bpmetrics.library

/**
 * Where something that applies to a recording or event came from (Model and Export
 * Consolidation §3).
 *
 * One enum where there were three identical ones, `CoverSource`, `PlaceSource` and `TagSource`.
 * A cover, a place and a tag are all inherited down the event tree by the same rule, so where each
 * was found is said the same way.
 */
enum class Source {
    /** Set on the item itself. The only place it can be changed from. */
    OWN,

    /** Set on the event the item is in. */
    EVENT,

    /** Set on an event further up the tree: the festival rather than the day. */
    ANCESTOR
}

/** A value as it applies to something, and where it came from. */
data class Inherited<out T>(val value: T, val source: Source) {
    val isInherited: Boolean get() = source != Source.OWN
}

// Names for what's inside, so call sites read as what they hold.
val Inherited<TagEntity>.tag: TagEntity get() = value
val Inherited<Cover>.cover: Cover get() = value
val Inherited<LocationEntity>.location: LocationEntity get() = value

/** Where a value was found, given how far up the chain: 0 is the item's own event. */
private fun sourceAt(depth: Int): Source = if (depth == 0) Source.EVENT else Source.ANCESTOR

/**
 * The nearest value: the item's own, else the first one up the chain.
 *
 * The one walk behind covers and places. They each had their own, which agreed only because
 * someone had been careful.
 *
 * @param ancestry the events containing the item, innermost first, as [EventTree.ancestryOf] gives
 *   them. Passed in so a caller resolving many items can walk each chain once.
 */
fun <N, T : Any> nearest(own: T?, ancestry: List<N>, pick: (N) -> T?): Inherited<T>? {
    own?.let { return Inherited(it, Source.OWN) }
    ancestry.forEachIndexed { depth, node ->
        pick(node)?.let { return Inherited(it, sourceAt(depth)) }
    }
    return null
}

/**
 * Every value from the item and its chain, each attributed to the nearest place it appears.
 *
 * The walk behind tags. The same tag on a recording and on the festival above it is one tag, and
 * it's the recording's, so it stays removable where it was put.
 *
 * @param key what makes two values the same one.
 */
fun <N, T> gather(own: List<T>, ancestry: List<N>, pick: (N) -> List<T>, key: (T) -> Any?): List<Inherited<T>> {
    val seen = mutableSetOf<Any?>()
    val result = mutableListOf<Inherited<T>>()
    fun add(values: List<T>, source: Source) {
        values.forEach { if (seen.add(key(it))) result += Inherited(it, source) }
    }
    add(own, Source.OWN)
    ancestry.forEachIndexed { depth, node -> add(pick(node), sourceAt(depth)) }
    return result
}

/** The events above [item], innermost first: a recording's event, or an event's parent, and up. */
fun EventTree.ancestryAbove(item: TreeItem, events: List<EventEntity>): List<EventEntity> =
    item.parentEventId?.let { ancestryOf(events, it) }.orEmpty()

/** [nearest] for an item in the tree, picking the same thing from it and from each event above. */
fun <T : Any> EventTree.nearest(item: TreeItem, events: List<EventEntity>, pick: (TreeItem) -> T?): Inherited<T>? =
    nearest(pick(item), ancestryAbove(item, events), pick)

/** [gather] for an item in the tree. */
fun <T> EventTree.gather(
    item: TreeItem,
    events: List<EventEntity>,
    key: (T) -> Any?,
    pick: (TreeItem) -> List<T>
): List<Inherited<T>> = gather(pick(item), ancestryAbove(item, events), pick, key)
