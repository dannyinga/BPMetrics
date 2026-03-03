package inga.bpmetrics.library

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Room entity that represents a single heart rate reading (BPM) at a specific point in time.
 *
 * Each data point is linked to a [BpmRecordEntity] via the [recordOwnerId].
 *
 * @property dataPointId Unique identifier for the data point (auto-generated).
 * @property recordOwnerId The ID of the [BpmRecordEntity] that "owns" this data point.
 * @property timestamp The timestamp of the reading in milliseconds relative to the record's start.
 * @property bpm The heart rate in beats per minute recorded at this timestamp.
 */
@Entity(
    tableName = "bpm_data_points"
)
data class BpmDataPointEntity(
    @PrimaryKey(autoGenerate = true) val dataPointId: Long = 0,
    val recordOwnerId: Long,
    val timestamp: Long,
    val bpm: Double
) {
    /**
     * Returns a human-readable representation of the data point, formatting the timestamp 
     * relative to minutes, seconds, and milliseconds.
     */
    override fun toString(): String {
        val milliseconds = timestamp % 1000
        val seconds = timestamp / 1000 % 60
        val minutes = timestamp / (1000 * 60) % 60

        val minText = if (minutes < 1) ""
                        else "${minutes}m "

        val secText = if (seconds < 1 && minutes < 1) ""
                        else "${seconds}s "

        val formattedTimeStamp = "${minText}${secText}${milliseconds}ms"
        return "($formattedTimeStamp, $bpm BPM)"
    }
}
