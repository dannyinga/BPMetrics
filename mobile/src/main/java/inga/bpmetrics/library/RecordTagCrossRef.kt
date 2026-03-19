package inga.bpmetrics.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction table representing a many-to-many relationship between [BpmRecordEntity] and [TagEntity].
 *
 * @property recordId The ID of the record.
 * @property tagId The ID of the tag.
 */
@Entity(
    tableName = "record_tag_cross_ref",
    primaryKeys = ["recordId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = BpmRecordEntity::class,
            parentColumns = ["recordId"],
            childColumns = ["recordId"],
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
data class RecordTagCrossRef(
    val recordId: Long,
    val tagId: Long
)
