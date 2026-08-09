package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An arbitrary set of events and recordings, held **by reference**.
 *
 * The second way of looking at the library, and deliberately not a second place to keep things.
 * "Festivals" holds Griztronics at the Gorge and Bass Canyon, months apart, while both stay exactly
 * where they are on the timeline. Deleting the set removes a grouping and nothing else.
 *
 * What it does not have is the point:
 *
 * - **No window.** A set is not a stretch of time and makes no claim about membership. Where a
 *   recording lives is decided by [EventMembership] and by nothing else.
 * - **No parent.** Sets do not nest. Nesting a set is a fair thing to want eventually, but a tree
 *   already exists for hierarchy, and adding a second one is how this whole rework started.
 * - **No ownership.** Membership is many-to-many, so an event can be in Festivals *and* 2026 *and*
 *   With Kyle at once. That is the property the old tier-based collection could not have, and the
 *   reason arbitrary grouping previously had nowhere to live.
 *
 * Not to be confused with the events carrying `type = "Collection"` that migration 23→24 produced.
 * Those were tiers being used as containers, and containers are what the event tree is for; 24→25
 * clears that marker so one word does not mean two things in one library.
 */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val collectionId: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "") val notes: String = "",
    val createdAt: Long = 0L,

    /** See [EventEntity.coverPath]. A set has no parent, so it inherits from nothing. */
    @ColumnInfo(defaultValue = "NULL") val coverPath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropLeft: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropTop: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropRight: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropBottom: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverBlur: Float? = null
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Untitled collection"

    val ownCover: Cover? get() = Cover.of(
        coverPath, coverCropLeft, coverCropTop, coverCropRight, coverCropBottom, coverBlur
    )
}

/**
 * An event in a collection.
 *
 * The event, not its contents. A set holding Griztronics holds the whole festival — every day and
 * every set inside it — because scope resolves through [EventTree] at the point of asking. Storing
 * the descendants instead would freeze the answer, and adding a recording to a day afterwards would
 * silently leave it out of "compare every festival".
 */
@Entity(
    tableName = "collection_events",
    primaryKeys = ["collectionId", "eventId"],
    indices = [Index("eventId")]
)
data class CollectionEventCrossRef(
    val collectionId: Long,
    val eventId: Long
)

/**
 * A recording in a collection, filed there directly.
 *
 * Separate from [CollectionEventCrossRef] rather than one table with two nullable columns: a row
 * that must have exactly one of two ids set is a constraint no schema here can express, and the
 * first query to forget it would silently return half the set.
 */
@Entity(
    tableName = "collection_records",
    primaryKeys = ["collectionId", "recordId"],
    indices = [Index("recordId")]
)
data class CollectionRecordCrossRef(
    val collectionId: Long,
    val recordId: Long
)
