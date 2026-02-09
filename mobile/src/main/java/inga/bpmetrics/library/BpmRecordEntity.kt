package inga.bpmetrics.library

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.sql.Date
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Entity(tableName = "bpm_records")
data class BpmRecordEntity (
    @PrimaryKey(autoGenerate = true) val recordId: Long = 0,
    var title: String,
    val date: Long,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val maxId: Long? = 0,
    val avg: Double? = 0.0,
    val minId: Long? = 0
    ) {

    override fun toString(): String {
        val outputBuilder = StringBuilder()
        outputBuilder.appendLine("Record ID: $recordId")
        outputBuilder.appendLine("Title: $title")

        val dateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
        val dateText = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).format(dateFormatter)
        outputBuilder.appendLine("Date: $dateText")

        val timeFormatter = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.getDefault())
        outputBuilder.appendLine("Start Time: ${Instant.ofEpochMilli(startTime)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)}")
        outputBuilder.appendLine("End Time: ${Instant.ofEpochMilli(endTime)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)}")

        val durationMin = durationMs / (1000 * 60) % 60
        val durationMinText = if (durationMin < 1) ""
                                else "${durationMin}m "
        val durationSec = durationMs / 1000 % 60
        val durationSecText = if (durationSec < 1) ""
        else "${durationSec}s "

        val durationMillis = durationMs % 1000
        outputBuilder.append("Duration: ${durationMinText}${durationSecText}${durationMillis}ms")

        return outputBuilder.toString();
    }
}