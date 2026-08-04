package inga.bpmetrics.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Access to the watch registry and to the records attributed to each watch.
 */
@Dao
interface WatchDao {

    /** Every known watch, most recently used first. */
    @Query("SELECT * FROM watches ORDER BY lastSeen DESC")
    fun getAllWatchesFlow(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watches WHERE watchId = :watchId")
    suspend fun getWatch(watchId: String): WatchEntity?

    /**
     * Registers a watch, leaving an existing entry untouched.
     *
     * Ignoring conflicts matters: a record arriving from a known watch must never overwrite the
     * name someone gave it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWatch(watch: WatchEntity)

    @Query("UPDATE watches SET customName = :name WHERE watchId = :watchId")
    suspend fun updateName(watchId: String, name: String)

    @Query("UPDATE watches SET colorArgb = :colorArgb WHERE watchId = :watchId")
    suspend fun updateColor(watchId: String, colorArgb: Int?)

    /** Records that this watch was seen again, refreshing what is known about it. */
    @Query(
        """
        UPDATE watches
        SET lastSeen = :seenAt,
            lastKnownModel = CASE WHEN :model != '' THEN :model ELSE lastKnownModel END,
            lastKnownNodeId = CASE WHEN :nodeId != '' THEN :nodeId ELSE lastKnownNodeId END
        WHERE watchId = :watchId
        """
    )
    suspend fun touchWatch(watchId: String, seenAt: Long, model: String, nodeId: String)

    @Query("DELETE FROM watches WHERE watchId = :watchId")
    suspend fun deleteWatch(watchId: String)

    /** How many records are attributed to a watch, so the UI can warn before deleting it. */
    @Query("SELECT COUNT(*) FROM bpm_records WHERE watchId = :watchId")
    suspend fun countRecordsForWatch(watchId: String): Int

    /**
     * Re-stamps the wearer on records from one watch within a date range.
     *
     * The safety net for the case the snapshot model cannot cover on its own: a brand new watch
     * records before anyone has named it, so its first recordings carry the model name. Without
     * this, correcting them means editing each record by hand.
     */
    @Query(
        """
        UPDATE bpm_records
        SET wearerName = :wearerName
        WHERE watchId = :watchId AND date BETWEEN :fromDate AND :toDate
        """
    )
    suspend fun reattributeRecords(watchId: String, wearerName: String, fromDate: Long, toDate: Long): Int

    /** Moves every record from one watch onto another, for merging duplicate registrations. */
    @Query("UPDATE bpm_records SET watchId = :intoWatchId WHERE watchId = :fromWatchId")
    suspend fun reassignRecords(fromWatchId: String, intoWatchId: String)

    /** Distinct watch ids actually present on records, used to spot orphans. */
    @Query("SELECT DISTINCT watchId FROM bpm_records WHERE watchId IS NOT NULL")
    suspend fun getWatchIdsInUse(): List<String>
}
