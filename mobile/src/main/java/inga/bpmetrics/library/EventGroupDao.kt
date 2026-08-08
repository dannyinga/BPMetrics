package inga.bpmetrics.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Access to event groups and what they contain.
 */
@Dao
interface EventGroupDao {

    /**
     * Every group, most recent first by what it contains.
     *
     * Ordered by the latest recording anywhere inside it, for the same reason events are: a list of
     * festivals should read in the order you went to them.
     */
    @Query(
        """
        SELECT g.* FROM event_groups g
        LEFT JOIN events e ON e.groupId = g.groupId
        LEFT JOIN bpm_records r ON r.eventId = e.eventId
        GROUP BY g.groupId
        ORDER BY MAX(COALESCE(r.startTime, g.createdAt)) DESC
        """
    )
    fun getAllGroupsFlow(): Flow<List<EventGroupEntity>>

    @Query("SELECT * FROM event_groups WHERE groupId = :groupId")
    suspend fun getGroup(groupId: Long): EventGroupEntity?

    @Insert
    suspend fun insertGroup(group: EventGroupEntity): Long

    @Query("UPDATE event_groups SET name = :name WHERE groupId = :groupId")
    suspend fun rename(groupId: Long, name: String)

    @Query("UPDATE event_groups SET notes = :notes WHERE groupId = :groupId")
    suspend fun updateNotes(groupId: Long, notes: String)

    /** See `EventDao.updateCover`. Null across the board means inherit from the parent collection. */
    @Query(
        "UPDATE event_groups SET coverPath = :path, coverCropLeft = :left, coverCropTop = :top, " +
            "coverCropRight = :right, coverCropBottom = :bottom, coverBlur = :blur WHERE groupId = :groupId"
    )
    suspend fun updateCover(
        groupId: Long,
        path: String?,
        left: Float?,
        top: Float?,
        right: Float?,
        bottom: Float?,
        blur: Float?
    )

    @Query("SELECT coverPath FROM event_groups WHERE groupId = :groupId")
    suspend fun coverPathOf(groupId: Long): String?

    @Query("DELETE FROM event_groups WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: Long)

    /** Releases a group's events without deleting them, for when the group itself goes. */
    @Query("UPDATE events SET groupId = NULL WHERE groupId = :groupId")
    suspend fun ungroupEvents(groupId: Long)

    /** Every collection, unordered, for walking the tree. */
    @Query("SELECT * FROM event_groups")
    suspend fun getAllGroups(): List<EventGroupEntity>

    @Query("UPDATE event_groups SET parentGroupId = :parentGroupId WHERE groupId = :groupId")
    suspend fun setParent(groupId: Long, parentGroupId: Long?)

    /**
     * Lifts a deleted collection's children to the top rather than deleting them with it.
     *
     * Removing "Coachella" should not silently take both of its days and every event inside them.
     * The same reasoning as [ungroupEvents], one level up — and the reason the parent column is
     * not a foreign key with a cascade.
     */
    @Query("UPDATE event_groups SET parentGroupId = NULL WHERE parentGroupId = :groupId")
    suspend fun orphanChildren(groupId: Long)

    @Query("SELECT COUNT(*) FROM events WHERE groupId = :groupId")
    suspend fun countEventsForGroup(groupId: Long): Int

    /** How many recordings sit anywhere inside a group, across all of its events. */
    @Query(
        """
        SELECT COUNT(*) FROM bpm_records r
        JOIN events e ON e.eventId = r.eventId
        WHERE e.groupId = :groupId
        """
    )
    suspend fun countRecordsForGroup(groupId: Long): Int

    /** The span a group covers, from the earliest recording in it to the latest. */
    @Query(
        """
        SELECT MIN(r.startTime) AS startMs, MAX(r.endTime) AS endMs
        FROM bpm_records r
        JOIN events e ON e.eventId = r.eventId
        WHERE e.groupId = :groupId
        """
    )
    suspend fun getGroupSpan(groupId: Long): NullableSpan?

    /** Every recording under a group, for aggregate analysis across a whole festival. */
    @Query(
        """
        SELECT r.* FROM bpm_records r
        JOIN events e ON e.eventId = r.eventId
        WHERE e.groupId = :groupId
        ORDER BY r.startTime ASC
        """
    )
    fun getRecordsForGroupFlow(groupId: Long): Flow<List<BpmRecordEntity>>
}
