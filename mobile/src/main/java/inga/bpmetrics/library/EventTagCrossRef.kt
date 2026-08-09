package inga.bpmetrics.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A tag applied to an event.
 *
 * Every recording filed under the event carries this tag for filtering, grouping and display —
 * **resolved at read time by [EffectiveTagsResolver], never written down onto the recordings**.
 * Copying it into `record_tag_cross_ref` would leave the copies behind when a recording moves to a
 * different event, and would make a tag someone applied by hand indistinguishable from one that
 * arrived by inheritance.
 */
@Entity(
    tableName = "event_tag_cross_ref",
    primaryKeys = ["eventId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["tagId"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tagId"])]
)
data class EventTagCrossRef(
    val eventId: Long,
    val tagId: Long
)

