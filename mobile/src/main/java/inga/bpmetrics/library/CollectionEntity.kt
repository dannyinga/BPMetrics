package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A **selection**: a named set of recordings you want to look at again.
 *
 * The one entity behind what used to be three. A collection, a saved view and a saved analysis were
 * all "these recordings, named", differing only in *how membership is decided* — and that is one
 * axis with three settings, not three kinds of object. See §8 of the product doc.
 *
 * - **[members]** — hand-picked references. What a collection has always been.
 * - **[filterJson]** — a rule, re-asked every time. What a saved view was. A *smart* collection.
 * - **[excludedRecordJson]** — struck out by hand, so one bad recording does not force you to
 *   abandon a rule that is otherwise right.
 * - **[frozenAt]** — the numbers kept as they were. What a saved analysis was.
 *
 * Any combination is legal, and the useful ones all have names: rule only is "every Subtronics
 * recording"; members only is "these three, together"; both is "every Subtronics recording, plus
 * these three that belong with them anyway".
 *
 * What it still does not have is the point:
 *
 * - **No window.** A set is not a stretch of time and makes no claim about membership. Where a
 *   recording *lives* is decided by [EventMembership] and by nothing else.
 * - **No parent.** Sets do not nest. A rule may name another collection, which is composition
 *   rather than hierarchy — and a tree already exists for hierarchy.
 * - **No ownership.** Membership is many-to-many, so an event can be in Festivals *and* 2026 *and*
 *   With Kyle at once.
 * - **No tags.** A tag is a property of a thing; a collection is a set of things. Tagging one is
 *   the category error that had `addTagToGroup` writing collection ids into `event_tag_cross_ref`.
 *
 * Not to be confused with the events carrying `type = "Collection"` that migration 23→24 produced.
 * Those were tiers being used as containers, and containers are what the event tree is for.
 *
 * @property members Not a column — see [CollectionEventCrossRef] and [CollectionRecordCrossRef].
 */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val collectionId: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "") val notes: String = "",
    val createdAt: Long = 0L,

    /**
     * The rule, serialised as a [FilterState], or null for a hand-made set.
     *
     * Text rather than a column per dimension because the dimensions change — location arrived a
     * sprint after the rest — and a table that grows a column each time is a migration each time.
     * It cannot be queried in SQL, which nothing needs: [Scope] resolves selections in memory.
     */
    @ColumnInfo(defaultValue = "NULL") val filterJson: String? = null,

    /** Record ids struck out by hand, comma-separated. Applied after members and rule. */
    @ColumnInfo(defaultValue = "") val excludedRecordJson: String = "",

    /**
     * Whether it sits on the library rather than behind a menu.
     *
     * Pinning is what made saved views worth building — a view you have to go and find is only
     * marginally better than rebuilding the filter. Hand-made collections default to unpinned so
     * folding the two together does not fill the bar with every set anyone ever made.
     */
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,

    /**
     * When these numbers were frozen, or null for a live selection.
     *
     * Freezing survives the fold because a deleted recording's data points are gone and its numbers
     * cannot be recomputed. It is now a choice made about a selection rather than a separate kind
     * of thing, and the rows live in `saved_analysis_records` keyed by [collectionId].
     */
    @ColumnInfo(defaultValue = "NULL") val frozenAt: Long? = null,

    /** See [EventEntity.coverPath]. A set has no parent, so it inherits from nothing. */
    @ColumnInfo(defaultValue = "NULL") val coverPath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropLeft: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropTop: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropRight: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropBottom: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverBlur: Float? = null,
    /** How far to darken it. See [inga.bpmetrics.library.Cover.dim]. */
    @ColumnInfo(defaultValue = "NULL") val coverDim: Float? = null
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Untitled collection"

    /** Whether membership answers a question rather than listing names. */
    val isSmart: Boolean get() = filterJson != null

    /** Whether its numbers are kept as they were rather than recomputed. */
    val isFrozen: Boolean get() = frozenAt != null

    val ownCover: Cover? get() = Cover.of(
        coverPath, coverCropLeft, coverCropTop, coverCropRight, coverCropBottom, coverBlur,
        coverDim
    )
}

/**
 * The rule, parsed, or null if it has none.
 *
 * An unreadable rule reads as *no* rule rather than as an empty filter, because an empty
 * [FilterState] selects the entire library — a collection that quietly became "everything" would be
 * far harder to notice than one that quietly became "only what I added by hand".
 */
fun CollectionEntity.rule(): FilterState? = filterJson?.let(FilterCodec::parseOrNull)

/** Record ids struck out by hand. */
fun CollectionEntity.exclusions(): Set<Long> = excludedRecordJson
    .split(',')
    .mapNotNullTo(mutableSetOf()) { it.trim().toLongOrNull() }

/** [exclusions] on the way back to storage. */
fun Set<Long>.asExclusionJson(): String = sorted().joinToString(",")

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

/**
 * A selection and everything a backup needs to rebuild it.
 *
 * Members travel by name and by the recording's id at the time, because import reassigns ids —
 * the restore remaps them, since only it knows where each recording landed.
 */
data class CollectionBackup(
    val collection: CollectionEntity,
    val eventNames: List<String> = emptyList(),
    val recordIds: List<Long> = emptyList(),
    val frozenRecords: List<SavedAnalysisRecordEntity> = emptyList()
)
