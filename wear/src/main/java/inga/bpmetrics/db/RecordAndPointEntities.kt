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
 * A completed record held on the watch until the phone has it.
 *
 * The row is kept until the phone confirms the save, not until the handover succeeds. Those are
 * different moments: handing a record to Play Services resolves immediately whether or not a
 * phone is anywhere nearby, so deleting on handover throws away the watch's only copy while the
 * record may still be sitting undelivered.
 *
 * @property id Unique ID for this pending record.
 * @property recordJson The serialized [BpmWatchRecord] JSON string.
 * @property dataItemPath Where this was handed to Play Services, or null if it never has been.
 *   Its disappearance from the data layer is the phone's acknowledgement, since the phone deletes
 *   each item once the recording is safely in its library.
 */
@Entity(tableName = "pending_records")
data class PendingRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordJson: String,
    val dataItemPath: String? = null
)
