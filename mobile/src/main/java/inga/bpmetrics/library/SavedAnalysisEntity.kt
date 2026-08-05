package inga.bpmetrics.library

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * A named analysis, frozen at the moment it was saved.
 *
 * "Coachella 2026 Analysis" means *those* recordings with *those* numbers, permanently. Importing
 * more recordings, re-tagging, or deleting one does not change a saved analysis — which is the
 * whole reason it is stored rather than re-derived from a filter.
 *
 * @property name What the user called it.
 * @property createdAt When it was taken.
 * @property filterDescription A readable note of what produced it, for display only.
 */
@Entity(tableName = "saved_analyses")
data class SavedAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val analysisId: Long = 0,
    val name: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "") val filterDescription: String = ""
)

/**
 * One recording's contribution to a saved analysis, captured at save time.
 *
 * These values are copied rather than referenced so the analysis stands on its own. [recordId]
 * is kept only so the user can jump to the recording if it still exists.
 *
 * Tags are flattened to `categoryId:categoryName:tagName`, one per entry, which avoids a third
 * table for something only ever read back as a whole.
 */
@Entity(
    tableName = "saved_analysis_records",
    foreignKeys = [
        ForeignKey(
            entity = SavedAnalysisEntity::class,
            parentColumns = ["analysisId"],
            childColumns = ["analysisId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("analysisId")]
)
data class SavedAnalysisRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: Long,
    val recordId: Long,
    val title: String,
    val date: Long,
    val minBpm: Double?,
    val avgBpm: Double?,
    val maxBpm: Double?,
    val activeDurationMs: Long,
    @ColumnInfo(defaultValue = "") val tagsEncoded: String = "",
    @ColumnInfo(defaultValue = "") val wearerName: String = "",
    @ColumnInfo(defaultValue = "") val watchName: String = ""
)

/**
 * A saved analysis together with the recordings it was taken from.
 */
data class SavedAnalysis(
    @Embedded val metadata: SavedAnalysisEntity,
    @Relation(parentColumn = "analysisId", entityColumn = "analysisId")
    val records: List<SavedAnalysisRecordEntity>
)
