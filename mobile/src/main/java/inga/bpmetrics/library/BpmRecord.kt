package inga.bpmetrics.library

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * A data class that represents a complete BPM record, combining metadata with its associated data points.
 *
 * This class uses Room's [@Relation] annotation to perform an automatic join between
 * the [BpmRecordEntity] and its associated [BpmDataPointEntity] records.
 *
 * @property metadata The core information about the BPM record session (title, date, duration, etc.).
 * @property dataPoints The full list of individual BPM readings recorded during the session.
 * @property minDataPoint The specific data point representing the minimum BPM recorded during the session.
 * @property maxDataPoint The specific data point representing the maximum BPM recorded during the session.
 */
data class BpmRecord(
    @Embedded val metadata: BpmRecordEntity,

    @Relation(
        parentColumn = "recordId",
        entityColumn = "recordOwnerId"
    )
    val dataPoints: List<BpmDataPointEntity>,

    @Relation(
        parentColumn = "minId",
        entityColumn = "dataPointId"
    )
    val minDataPoint: BpmDataPointEntity?,

    @Relation(
        parentColumn = "maxId",
        entityColumn = "dataPointId"
    )
    val maxDataPoint: BpmDataPointEntity?,

    @Relation(
    parentColumn = "recordId",
    entityColumn = "tagId",
    associateBy = Junction(RecordTagCrossRef::class)
    )
    val tags: List<TagEntity> = emptyList()
) {

    /**
     * Returns a string representation of the complete BPM record,
     * including its metadata and key analysis results (Max, Avg, Min).
     */
    override fun toString(): String {
        val outputBuilder = StringBuilder()

        outputBuilder.appendLine(metadata)

        outputBuilder.appendLine("Max: $maxDataPoint")
        outputBuilder.appendLine("Avg: ${metadata.avg}")
        outputBuilder.appendLine("Min: $minDataPoint")

        return outputBuilder.toString()
    }
}
