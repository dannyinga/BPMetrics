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
    val createdAt: Long = 0L
) {
    /** How to refer to this event when it has somehow been left unnamed. */
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Untitled event"
}

/**
 * A collection of events — a festival, a weekend, a tour.
 *
 * One level of nesting only. Anything that cuts across structure ("all the dubstep sets", "every
 * event it rained at") is a tag, applied at whichever level it is true, and inherited downwards.
 * That is what removes the need for a tree here.
 *
 * Its date range, like an event's, is derived from what it contains.
 */
@Entity(tableName = "event_groups")
data class EventGroupEntity(
    @PrimaryKey(autoGenerate = true) val groupId: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "") val notes: String = "",
    val createdAt: Long = 0L
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Untitled group"
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
