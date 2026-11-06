package inga.bpmetrics.library

/**
 * Which recording, event or collection: anything with a page of its own.
 *
 * Replaces `LibraryRepository.CoverOwner`, which named the same three things for covers alone.
 * Maps 1:1 onto [ScopeRef], so going from "this item" to "what it covers" is one call.
 */
sealed interface ItemRef {
    data class Recording(val recordId: Long) : TreeItemRef
    data class Event(val eventId: Long) : TreeItemRef
    data class Collection(val collectionId: Long) : ItemRef

    /** The scope this item stands for: itself, or itself and everything beneath it. */
    val scope: ScopeRef
        get() = when (this) {
            is Recording -> ScopeRef.Recording(recordId)
            is Event -> ScopeRef.Event(eventId)
            is Collection -> ScopeRef.Collection(collectionId)
        }
}

/**
 * A recording or an event: the things that hold tags and a place. A collection can't, so a call
 * like `addTag` takes this and a collection doesn't type-check.
 */
sealed interface TreeItemRef : ItemRef

/**
 * Anything with a page of its own: a recording, an event, a collection
 * (Model and Export Consolidation §2).
 *
 * What the three share, said once, so a function about any of them can take this instead of three
 * overloads. Implemented by the Room entities themselves; the members are getters over their own
 * columns, so nothing about the tables changes.
 */
sealed interface LibraryItem {
    val ref: ItemRef
    val displayName: String
    val notes: String
    val ownCover: Cover?
}

/**
 * Things that live in the event tree and inherit from it: recordings and events (Taxonomy §8.1).
 *
 * Collections are a [LibraryItem] but not this, which is the type system saying what the taxonomy
 * work decided: a collection holds no tags and no place of its own.
 */
sealed interface TreeItem : LibraryItem {
    override val ref: TreeItemRef

    /** A recording's event, or an event's parent. */
    val parentEventId: Long?
    val ownLocationId: Long?
}
