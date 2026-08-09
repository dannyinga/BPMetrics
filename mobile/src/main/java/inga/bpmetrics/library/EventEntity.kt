package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One thing that happened, and the recordings of it.
 *
 * A set, a run, a night out — whatever several people were doing while their watches were on. The
 * library could previously express only "these recordings exist"; an event is what lets it say
 * *this was Subtronics*, so the same group of recordings can be found, compared and exported as one
 * thing rather than hand-picked every time.
 *
 * **Start and end times are deliberately absent.** They are the earliest start and latest end of the
 * event's recordings, and storing them would leave a copy to go stale the moment a recording is
 * added, removed or re-attributed. Derive them.
 *
 * @property groupId The collection this belongs to, or null while it is unfiled.
 * @property createdAt When the event was made. Ordering only.
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "NULL") val groupId: Long? = null,
    @ColumnInfo(defaultValue = "") val notes: String = "",
    val createdAt: Long = 0L,

    /**
     * The event this one sits inside, or null at the top of the timeline.
     *
     * Events nest now: a festival holds days, a day holds sets, a set holds recordings. Which of
     * those an event *is* depends only on what it happens to hold — see the Taxonomy Consolidation
     * doc, §2.1.
     *
     * Not a foreign key, matching `EventGroupEntity.parentGroupId`: deleting a parent must orphan
     * its children to the top rather than cascade, because deleting "Coachella" should not
     * silently take both of its days and every set in them.
     */
    @ColumnInfo(defaultValue = "NULL") val parentId: Long? = null,

    /**
     * When this event happened, if it is the kind that has a time.
     *
     * A window is the **membership rule**, not a hint: a recording inside it belongs to this event,
     * and to the deepest such event where several nest. Null means the event has no time of its
     * own and takes its span from what it contains.
     */
    @ColumnInfo(defaultValue = "NULL") val windowStart: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val windowEnd: Long? = null,

    /**
     * What kind of thing this is — "Concert", "Festival", "Gaming session", "Run", "Raid".
     *
     * Free text, suggested from types already in use. This is what keeps the app out of any one
     * domain: the container is generic and the word on it is the user's. A fixed vocabulary is
     * exactly the part that would make this concert software.
     */
    @ColumnInfo(defaultValue = "NULL") val type: String? = null,

    /**
     * Whether this event's numbers are left out of its parent's.
     *
     * A camp break or a merch queue is genuinely part of the day and genuinely not part of what the
     * day's average should mean. Set once here rather than excluded by hand in every roll-up.
     * Affects analysis only — never the library, the timeline or an export.
     */
    @ColumnInfo(defaultValue = "0") val excludedFromParentAnalysis: Boolean = false,

    /**
     * The picture that stands for this event, as a file name inside `files/covers/`.
     *
     * Null means inherit from the collection above — see [CoverResolver]. A name rather than a path
     * because the app's own files directory moves between installs and backups, and an absolute
     * path written on one device is meaningless on another.
     */
    @ColumnInfo(defaultValue = "NULL") val coverPath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropLeft: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropTop: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropRight: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropBottom: Float? = null,
    /** See [Cover.blur]. For covers that are themselves made of type, like an event flyer. */
    @ColumnInfo(defaultValue = "NULL") val coverBlur: Float? = null
) {
    /** How to refer to this event when it has somehow been left unnamed. */
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Untitled event"

    /** This event's own cover, or null if it has none of its own to offer. */
    val ownCover: Cover? get() = Cover.of(
        coverPath, coverCropLeft, coverCropTop, coverCropRight, coverCropBottom, coverBlur
    )
}

/**
 * A collection of events, and optionally of other collections — a festival, a day of one, a tour.
 *
 * Collections nest; events do not, and a recording still belongs to exactly one event. That split
 * is the whole design. "Subtronics inside Coachella Day 1 inside Coachella" is strict containment,
 * and the alternative — letting a recording belong to several events — would express it by putting
 * the same recording in all three. That leaves "which event is this recording's?" unanswerable on
 * the record screen, breaks nearest-wins tag inheritance (with three parents there is no nearest),
 * and makes every total count it three times.
 *
 * Nesting the *container* costs one nullable column and a walk up the parents. Nesting the leaf
 * would cost the meaning of the leaf.
 *
 * Its date range, like an event's, is derived from what it contains.
 */
@Entity(tableName = "event_groups")
data class EventGroupEntity(
    @PrimaryKey(autoGenerate = true) val groupId: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "") val notes: String = "",
    val createdAt: Long = 0L,
    /**
     * The collection this one sits inside, or null at the top.
     *
     * Not a foreign key: deleting a parent must orphan its children to the top level rather than
     * cascade, because deleting "Coachella" should not silently take both of its days and every
     * event in them with it.
     */
    @ColumnInfo(defaultValue = "NULL") val parentGroupId: Long? = null,

    /** See [EventEntity.coverPath]. Null means inherit from the collection above this one. */
    @ColumnInfo(defaultValue = "NULL") val coverPath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropLeft: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropTop: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropRight: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val coverCropBottom: Float? = null,
    /** See [Cover.blur]. For covers that are themselves made of type, like an event flyer. */
    @ColumnInfo(defaultValue = "NULL") val coverBlur: Float? = null
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Untitled collection"

    /** This collection's own cover, or null if it has none of its own to offer. */
    val ownCover: Cover? get() = Cover.of(
        coverPath, coverCropLeft, coverCropTop, coverCropRight, coverCropBottom, coverBlur
    )
}

/**
 * The span an event or group covers, worked out from its recordings.
 *
 * Null when nothing in it has any recordings yet — an event can exist before it has been filled.
 */
data class TimeSpan(
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Which people an event's window applies to, where it applies to some of them.
 *
 * This is what lets two stages at one festival both be nine until half past. Kyle at Subtronics and
 * Ben at Excision overlap in time and not in people, so a recording still has exactly one answer —
 * the key is (time × person) rather than time alone. Without it the model would have to refuse
 * simultaneous events, which is wrong about how a festival works.
 *
 * **No rows means everyone.** That is the common case and it stays the simple one: an event with no
 * qualification claims every recording in its window, and two such siblings therefore cannot
 * overlap.
 */
@Entity(
    tableName = "event_window_people",
    primaryKeys = ["eventId", "personId"],
    indices = [Index("personId")]
)
data class EventWindowPersonCrossRef(
    val eventId: Long,
    val personId: Long
)
