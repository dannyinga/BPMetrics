package inga.bpmetrics.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import inga.bpmetrics.core.BpmWatchRecord

/**
 * Temporary storage for a single heart rate sample during an active recording.
 */
@Entity(tableName = "local_bpm_data_points")
data class LocalBpmDataPoint(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val bpm: Double
)

/**
 * Persistent storage for a completed record that is waiting to be synced to the phone.
 * 
 * @property id Unique ID for this pending record.
 * @property recordJson The serialized [BpmWatchRecord] JSON string.
 */
@Entity(tableName = "pending_records")
data class PendingRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordJson: String
)
