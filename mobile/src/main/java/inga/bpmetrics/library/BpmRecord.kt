package inga.bpmetrics.library

import androidx.compose.material3.Text
import androidx.room.Embedded
import androidx.room.Relation
import kotlin.math.min

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
    val maxDataPoint: BpmDataPointEntity?
) {

    override fun toString(): String {
        val outputBuilder = StringBuilder()

        outputBuilder.appendLine(metadata)

        outputBuilder.appendLine("Max: $maxDataPoint")
        outputBuilder.appendLine("Avg: ${metadata.avg}")
        outputBuilder.appendLine("Min: $minDataPoint")

        outputBuilder.appendLine("Data Points:")
        for (dataPoint in dataPoints) {
            outputBuilder.appendLine("$dataPoint")
        }

        return outputBuilder.toString()
    }
}
