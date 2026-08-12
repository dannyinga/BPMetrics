package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * One recording's contribution to a **frozen** selection, captured when it was frozen.
 *
 * These values are copied rather than referenced so the numbers stand on their own: importing more
 * recordings, re-tagging, or deleting one does not change them. [recordId] is kept only so the user
 * can jump to the recording if it still exists.
 *
 * Keyed by [collectionId] since the fold. A saved analysis used to be its own entity with its own
 * name and id; it is now a [CollectionEntity] with [CollectionEntity.frozenAt] set, because a saved
 * analysis and a collection were always the same thing — a named set of recordings — differing only
 * in whether membership was frozen. See §8 of the product doc.
 *
 * Tags are flattened to `categoryId:categoryName:tagName`, one per entry, which avoids a third table
 * for something only ever read back as a whole.
 */
@Entity(
    tableName = "saved_analysis_records",
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["collectionId"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("collectionId")]
)
data class SavedAnalysisRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val recordId: Long,
    val title: String,
    val date: Long,
    val minBpm: Double?,
    val avgBpm: Double?,
    val maxBpm: Double?,
    val activeDurationMs: Long,
    @ColumnInfo(defaultValue = "") val tagsEncoded: String = "",
    @ColumnInfo(defaultValue = "") val wearerName: String = "",
    @ColumnInfo(defaultValue = "") val watchName: String = "",
    /**
     * Who and which event, by id.
     *
     * Names alone were captured before, which meant a frozen analysis could group by wearer but
     * could not colour anyone or offer the Event tab — the ids were simply never written down.
     * Null on rows saved before this existed, and the screen falls back exactly as it did then.
     */
    @ColumnInfo(defaultValue = "NULL") val personId: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val personColorArgb: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val eventId: Long? = null,
    @ColumnInfo(defaultValue = "") val eventName: String = "",
    /**
     * Time in each heart rate band, as `name:ms`, one per entry, separated by newlines.
     *
     * Encoded rather than given a table for the same reason as [tagsEncoded]: it is only ever read
     * back whole. Without it a frozen selection loses the "where did the time go" breakdown that
     * makes a live one worth reading, and it cannot be recomputed — the data points are gone.
     */
    @ColumnInfo(defaultValue = "") val zonesEncoded: String = ""
)

/**
 * A selection together with the numbers it froze.
 *
 * Empty [records] on a live selection, which is the normal case — the numbers are recomputed from
 * the library instead.
 */
data class FrozenSelection(
    @Embedded val collection: CollectionEntity,
    @Relation(parentColumn = "collectionId", entityColumn = "collectionId")
    val records: List<SavedAnalysisRecordEntity>
)
