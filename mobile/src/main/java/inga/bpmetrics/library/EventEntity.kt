package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Entity
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
    @ColumnInfo(defaultValue = "NULL") val coverCropBottom: Float? = null
) {
    /** How to refer to this event when it has somehow been left unnamed. */
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Untitled event"

    /** This event's own cover, or null if it has none of its own to offer. */
    val ownCover: Cover? get() = Cover.of(
        coverPath, coverCropLeft, coverCropTop, coverCropRight, coverCropBottom
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
    @ColumnInfo(defaultValue = "NULL") val coverCropBottom: Float? = null
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Untitled collection"

    /** This collection's own cover, or null if it has none of its own to offer. */
    val ownCover: Cover? get() = Cover.of(
        coverPath, coverCropLeft, coverCropTop, coverCropRight, coverCropBottom
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
