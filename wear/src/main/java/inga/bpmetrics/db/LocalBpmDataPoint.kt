package inga.bpmetrics.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Room entity for temporary storage of BPM data points on the watch.
 * This ensures data is not lost if the app or service is killed during a workout.
 */
@Entity(tableName = "local_bpm_data_points")
data class LocalBpmDataPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val bpm: Double
)
