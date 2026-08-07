package inga.bpmetrics.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Access to events and the recordings filed under them.
 */
@Dao
interface EventDao {

    /**
     * Every event, most recent first by what it contains.
     *
     * Ordered by its latest recording rather than by `createdAt`, so the list reads chronologically
     * by *when things happened* — which is how anyone looks for a set they were at — rather than by
     * when someone got round to creating the entry.
     */
    @Query(
        """
        SELECT e.* FROM events e
        LEFT JOIN bpm_records r ON r.eventId = e.eventId
        GROUP BY e.eventId
        ORDER BY MAX(COALESCE(r.startTime, e.createdAt)) DESC
        """
    )
    fun getAllEventsFlow(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE eventId = :eventId")
    suspend fun getEvent(eventId: Long): EventEntity?

    @Query(
        """
        SELECT e.* FROM events e
        LEFT JOIN bpm_records r ON r.eventId = e.eventId
        WHERE e.groupId = :groupId
        GROUP BY e.eventId
        ORDER BY MIN(COALESCE(r.startTime, e.createdAt)) ASC
        """
    )
    fun getEventsForGroupFlow(groupId: Long): Flow<List<EventEntity>>

    /** Events not yet filed into a group. */
    @Query("SELECT * FROM events WHERE groupId IS NULL ORDER BY createdAt DESC")
    fun getUngroupedEventsFlow(): Flow<List<EventEntity>>

    @Insert
    suspend fun insertEvent(event: EventEntity): Long

    @Query("UPDATE events SET name = :name WHERE eventId = :eventId")
    suspend fun rename(eventId: Long, name: String)

    @Query("UPDATE events SET notes = :notes WHERE eventId = :eventId")
    suspend fun updateNotes(eventId: Long, notes: String)

    @Query("UPDATE events SET groupId = :groupId WHERE eventId = :eventId")
    suspend fun setGroup(eventId: Long, groupId: Long?)

    @Query("DELETE FROM events WHERE eventId = :eventId")
    suspend fun deleteEvent(eventId: Long)

    // --- Recordings within an event ---

    @Query("SELECT * FROM bpm_records WHERE eventId = :eventId ORDER BY startTime ASC")
    fun getRecordsForEventFlow(eventId: Long): Flow<List<BpmRecordEntity>>

    @Query("SELECT COUNT(*) FROM bpm_records WHERE eventId = :eventId")
    suspend fun countRecordsForEvent(eventId: Long): Int

    /**
     * The span an event covers, from its earliest recording to its latest.
     *
     * Returns null for an event with no recordings, which is why the columns are nullable rather
     * than defaulted — an empty event has no span, and reporting zero would draw it at the epoch.
     */
    @Query(
        """
        SELECT MIN(startTime) AS startMs, MAX(endTime) AS endMs
        FROM bpm_records WHERE eventId = :eventId
        """
    )
    suspend fun getEventSpan(eventId: Long): NullableSpan?

    /** Recordings not filed under any event, shown in their own section of the events view. */
    @Query("SELECT * FROM bpm_records WHERE eventId IS NULL ORDER BY startTime DESC")
    fun getUnfiledRecordsFlow(): Flow<List<BpmRecordEntity>>

    /**
     * Which of these recordings are not in an event yet.
     *
     * Lets a bulk file skip the ones someone has already placed by hand, rather than reassigning
     * them and silently overruling a decision the user made.
     */
    @Query("SELECT recordId FROM bpm_records WHERE recordId IN (:recordIds) AND eventId IS NULL")
    suspend fun recordIdsWithoutEvent(recordIds: List<Long>): List<Long>

    @Query("SELECT COUNT(*) FROM bpm_records WHERE eventId IS NULL")
    fun countUnfiledRecordsFlow(): Flow<Int>

    /**
     * Files a batch of recordings under an event, or unfiles them when [eventId] is null.
     *
     * Callers must chunk the ids — Room expands `IN (:recordIds)` into one bind variable each and
     * SQLite caps those at 999, which select-all reaches. See `LibraryRepository`.
     */
    @Query("UPDATE bpm_records SET eventId = :eventId WHERE recordId IN (:recordIds)")
    suspend fun assignRecordsToEvent(recordIds: List<Long>, eventId: Long?): Int

    /** Unfiles everything under an event, used when the event itself is deleted. */
    @Query("UPDATE bpm_records SET eventId = NULL WHERE eventId = :eventId")
    suspend fun unfileRecordsForEvent(eventId: Long)
}

/**
 * A span whose ends are null when nothing was found.
 *
 * Room needs a class to project `MIN`/`MAX` into, and both are null for an empty event — so this
 * cannot be [TimeSpan], whose whole point is that it is only constructed when a span exists.
 */
data class NullableSpan(
    val startMs: Long?,
    val endMs: Long?
) {
    fun toSpan(): TimeSpan? =
        if (startMs != null && endMs != null) TimeSpan(startMs, endMs) else null
}
