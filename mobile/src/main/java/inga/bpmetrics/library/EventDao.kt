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
     * Events the library lists as events, most recent first by what they contain.
     *
     * Ordered by its latest recording rather than by `createdAt`, so the list reads chronologically
     * by *when things happened* — which is how anyone looks for a set they were at — rather than by
     * when someone got round to creating the entry.
     *
     * Collections are excluded because the screens show them in their own section, from
     * [getCollectionsFlow]. They are the same rows in the same table since the fold, so without
     * this each one would appear twice on one screen.
     *
     * **Never walk the tree with this.** Ancestry, descendants, spans and membership all need
     * [getAllEvents] or [getAllEventsFlowUnfiltered]: a chain with the collections cut out of it
     * breaks in the middle, and a recording two levels down stops being found — which is the exact
     * defect this whole initiative exists to end.
     */
    @Query(
        """
        SELECT e.* FROM events e
        LEFT JOIN bpm_records r ON r.eventId = e.eventId
        WHERE e.type IS NOT 'Collection'
        GROUP BY e.eventId
        ORDER BY MAX(COALESCE(r.startTime, e.createdAt)) DESC
        """
    )
    fun getAllEventsFlow(): Flow<List<EventEntity>>

    /** Every event, once. What a reconcile walks; a flow would restart it on its own writes. */
    @Query("SELECT * FROM events")
    suspend fun getAllEvents(): List<EventEntity>

    /**
     * Every event including the folded collections, as a flow.
     *
     * [getAllEventsFlow] hides collections so the screens do not show them twice while the old
     * table is still being read. Anything walking the tree needs the whole tree.
     */
    @Query("SELECT * FROM events")
    fun getAllEventsFlowUnfiltered(): Flow<List<EventEntity>>

    /** The events that were collections. Newest first, as the collections list always was. */
    @Query("SELECT * FROM events WHERE type = 'Collection' ORDER BY createdAt DESC")
    fun getCollectionsFlow(): Flow<List<EventEntity>>

    /** Who each window applies to. Empty for a window that names nobody, which is most of them. */
    @Query("SELECT * FROM event_window_people")
    suspend fun getAllWindowPeople(): List<EventWindowPersonCrossRef>

    @Query("SELECT * FROM events WHERE eventId = :eventId")
    suspend fun getEvent(eventId: Long): EventEntity?

    @Query(
        """
        SELECT e.* FROM events e
        LEFT JOIN bpm_records r ON r.eventId = e.eventId
        WHERE e.parentId = :groupId
        GROUP BY e.eventId
        ORDER BY MIN(COALESCE(r.startTime, e.createdAt)) ASC
        """
    )
    // `parentId`, not the legacy `groupId`: filing an event under a collection has written the
    // tree link since the fold, so reading the old column would show a collection frozen at
    // whatever it held before the migration.
    fun getEventsForGroupFlow(groupId: Long): Flow<List<EventEntity>>

    /**
     * Events not yet filed into a group.
     *
     * A collection has no `groupId` either, so it would read as ungrouped and show up beside the
     * collections section that already lists it. Same exclusion as [getAllEventsFlow].
     */
    @Query(
        "SELECT * FROM events WHERE groupId IS NULL AND type IS NOT 'Collection' " +
            "ORDER BY createdAt DESC"
    )
    fun getUngroupedEventsFlow(): Flow<List<EventEntity>>

    @Insert
    suspend fun insertEvent(event: EventEntity): Long

    @Query("UPDATE events SET name = :name WHERE eventId = :eventId")
    suspend fun rename(eventId: Long, name: String)

    @Query("UPDATE events SET notes = :notes WHERE eventId = :eventId")
    suspend fun updateNotes(eventId: Long, notes: String)

    /**
     * The picture that stands for this event, and how it is framed.
     *
     * All five together, because a path without a crop is a cover framed differently from how it
     * was set, and a crop without a path is nothing at all. Null across the board means inherit
     * from the collection above — see CoverResolver.
     */
    @Query(
        "UPDATE events SET coverPath = :path, coverCropLeft = :left, coverCropTop = :top, " +
            "coverCropRight = :right, coverCropBottom = :bottom, coverBlur = :blur WHERE eventId = :eventId"
    )
    suspend fun updateCover(
        eventId: Long,
        path: String?,
        left: Float?,
        top: Float?,
        right: Float?,
        bottom: Float?,
        blur: Float?
    )

    @Query("SELECT coverPath FROM events WHERE eventId = :eventId")
    suspend fun coverPathOf(eventId: Long): String?

    /**
     * The taxonomy fields, in one write.
     *
     * One statement rather than five, because these are set together — by a restore rebuilding an
     * event, or by an editor saving one — and five separate writes would let a reader observe an
     * event that has a window but not yet a parent, and place recordings by it.
     */
    @Query(
        """
        UPDATE events
        SET parentId = :parentId,
            windowStart = :windowStart,
            windowEnd = :windowEnd,
            type = :type,
            excludedFromParentAnalysis = :excluded
        WHERE eventId = :eventId
        """
    )
    suspend fun updateTaxonomy(
        eventId: Long,
        parentId: Long?,
        windowStart: Long?,
        windowEnd: Long?,
        type: String?,
        excluded: Boolean
    )

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
    /**
     * A picture for one recording, overriding whatever its event would give it.
     *
     * The exception rather than the rule — see `CoverResolver`. Null across the board hands the
     * recording back to its event's cover.
     */
    @Query(
        "UPDATE bpm_records SET coverPath = :path, coverCropLeft = :left, coverCropTop = :top, " +
            "coverCropRight = :right, coverCropBottom = :bottom, coverBlur = :blur WHERE recordId = :recordId"
    )
    suspend fun updateRecordCover(
        recordId: Long,
        path: String?,
        left: Float?,
        top: Float?,
        right: Float?,
        bottom: Float?,
        blur: Float?
    )

    @Query("SELECT coverPath FROM bpm_records WHERE recordId = :recordId")
    suspend fun recordCoverPathOf(recordId: Long): String?

    /**
     * The distinct events these recordings are filed under, ignoring any that are unfiled.
     *
     * Room will not put a null in a result, so a query that selected `eventId` across the lot
     * could not report the unfiled ones — they would arrive as zero and read as a real event.
     * Unfiled is asked separately, by [unfiledCountAmong], and the two answers combine at the
     * caller.
     */
    @Query(
        "SELECT DISTINCT eventId FROM bpm_records " +
            "WHERE recordId IN (:recordIds) AND eventId IS NOT NULL"
    )
    suspend fun distinctEventIdsFor(recordIds: List<Long>): List<Long>

    /** How many of these recordings are in no event at all. */
    @Query("SELECT COUNT(*) FROM bpm_records WHERE recordId IN (:recordIds) AND eventId IS NULL")
    suspend fun unfiledCountAmong(recordIds: List<Long>): Int

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
