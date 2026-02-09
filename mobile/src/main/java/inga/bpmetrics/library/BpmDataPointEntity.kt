package inga.bpmetrics.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.min

@Entity(
    tableName = "bpm_data_points"
)
data class BpmDataPointEntity(
    @PrimaryKey(autoGenerate = true) val dataPointId: Long = 0,
    val recordOwnerId: Long,
    val timestamp: Long,
    val bpm: Double
) {
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