package inga.bpmetrics.library

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A Room entity that represents a single BPM recording session's metadata.
 *
 * This table stores core information about a heart rate recording, such as its title, 
 * date, and duration. It also stores pointers to specific data points (min/max) and 
 * the calculated average BPM.
 *
 * @property recordId Unique identifier for the record (auto-generated).
 * @property title The title given to the recording (e.g., a timestamp-based string or custom name).
 * @property description A user-provided description of the recording session.
 * @property date The date of the recording in milliseconds since the epoch.
 * @property startTime The start timestamp in milliseconds.
 * @property endTime The end timestamp in milliseconds.
 * @property durationMs The total duration of the recording in milliseconds.
 * @property maxId The ID of the [BpmDataPointEntity] that contains the maximum heart rate.
 * @property avg The calculated average BPM for the session.
 * @property minId The ID of the [BpmDataPointEntity] that contains the minimum heart rate.
 */
@Entity(tableName = "bpm_records")
data class BpmRecordEntity (
    @PrimaryKey(autoGenerate = true) val recordId: Long = 0,
    var title: String,
    var description: String = "",
    val date: Long,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val maxId: Long? = 0,
    val avg: Double? = 0.0,
    val minId: Long? = 0
    ) {

    /**
     * Returns a human-readable summary of the record metadata, formatted for debugging or display.
     */
    override fun toString(): String {
        val outputBuilder = StringBuilder()
        outputBuilder.appendLine("Record ID: $recordId")
        outputBuilder.appendLine("Title: $title")
        outputBuilder.appendLine("Description: $description")

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
