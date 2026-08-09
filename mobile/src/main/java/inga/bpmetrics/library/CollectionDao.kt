package inga.bpmetrics.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Access to collections and what they hold.
 *
 * Deliberately thin. A collection stores which events and recordings were *named*, and nothing
 * derived — no counts, no spans, no descendants. Those come from [EventTree] at the point of
 * asking, so a set holding a festival covers whatever that festival holds today rather than
 * whatever it held when someone added it.
 */
@Dao
interface CollectionDao {

    @Query("SELECT * FROM collections ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections")
    suspend fun getAll(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE collectionId = :collectionId")
    suspend fun get(collectionId: Long): CollectionEntity?

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE collectionId = :collectionId")
    suspend fun rename(collectionId: Long, name: String)

    @Query("UPDATE collections SET notes = :notes WHERE collectionId = :collectionId")
    suspend fun updateNotes(collectionId: Long, notes: String)

    /** See [EventDao.updateCover] for why all six move together. */
    @Query(
        "UPDATE collections SET coverPath = :path, coverCropLeft = :left, coverCropTop = :top, " +
            "coverCropRight = :right, coverCropBottom = :bottom, coverBlur = :blur " +
            "WHERE collectionId = :collectionId"
    )
    suspend fun updateCover(
        collectionId: Long,
        path: String?,
        left: Float?,
        top: Float?,
        right: Float?,
        bottom: Float?,
        blur: Float?
    )

    @Query("SELECT coverPath FROM collections WHERE collectionId = :collectionId")
    suspend fun coverPathOf(collectionId: Long): String?

    /**
     * Removes the set and its membership rows, and nothing else.
     *
     * The events and recordings it named are untouched — they live on the timeline, and a set was
     * only ever a second view over them. This is the whole difference from deleting an event.
     */
    @Query("DELETE FROM collections WHERE collectionId = :collectionId")
    suspend fun delete(collectionId: Long)

    @Query("DELETE FROM collection_events WHERE collectionId = :collectionId")
    suspend fun clearEvents(collectionId: Long)

    @Query("DELETE FROM collection_records WHERE collectionId = :collectionId")
    suspend fun clearRecords(collectionId: Long)

    // --- Membership ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addEvent(link: CollectionEventCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRecord(link: CollectionRecordCrossRef)

    @Query("DELETE FROM collection_events WHERE collectionId = :collectionId AND eventId = :eventId")
    suspend fun removeEvent(collectionId: Long, eventId: Long)

    @Query(
        "DELETE FROM collection_records WHERE collectionId = :collectionId AND recordId = :recordId"
    )
    suspend fun removeRecord(collectionId: Long, recordId: Long)

    /**
     * Every membership row in the library, in two queries rather than two per collection.
     *
     * The collections screen needs what every set holds at once. Asking per row would be a query
     * per card while scrolling, to answer something a single table scan covers.
     */
    @Query("SELECT * FROM collection_events")
    fun allEventLinksFlow(): Flow<List<CollectionEventCrossRef>>

    @Query("SELECT * FROM collection_records")
    fun allRecordLinksFlow(): Flow<List<CollectionRecordCrossRef>>

    @Query("SELECT * FROM collection_events")
    suspend fun allEventLinks(): List<CollectionEventCrossRef>

    @Query("SELECT * FROM collection_records")
    suspend fun allRecordLinks(): List<CollectionRecordCrossRef>

    /** Which collections something is already in, so a picker can show it as ticked. */
    @Query("SELECT collectionId FROM collection_events WHERE eventId = :eventId")
    fun collectionsHoldingEventFlow(eventId: Long): Flow<List<Long>>

    @Query("SELECT collectionId FROM collection_records WHERE recordId = :recordId")
    fun collectionsHoldingRecordFlow(recordId: Long): Flow<List<Long>>

    /** Cleans up membership pointing at something that has since been deleted. */
    @Query("DELETE FROM collection_events WHERE eventId = :eventId")
    suspend fun forgetEvent(eventId: Long)

    @Query("DELETE FROM collection_records WHERE recordId = :recordId")
    suspend fun forgetRecord(recordId: Long)

    // --- The rule, and pinning: what saved views were before the fold ---

    @Query("UPDATE collections SET filterJson = :filterJson WHERE collectionId = :collectionId")
    suspend fun updateRule(collectionId: Long, filterJson: String?)

    @Query(
        "UPDATE collections SET excludedRecordJson = :excluded WHERE collectionId = :collectionId"
    )
    suspend fun updateExclusions(collectionId: Long, excluded: String)

    @Query("UPDATE collections SET isPinned = :pinned WHERE collectionId = :collectionId")
    suspend fun setPinned(collectionId: Long, pinned: Boolean)

    /** Pinned selections, oldest first, so the bar does not reshuffle as more are added. */
    @Query("SELECT * FROM collections WHERE isPinned = 1 ORDER BY createdAt ASC")
    fun pinnedFlow(): Flow<List<CollectionEntity>>

    // --- Frozen numbers: what saved analyses were before the fold ---

    @Query("UPDATE collections SET frozenAt = :frozenAt WHERE collectionId = :collectionId")
    suspend fun setFrozenAt(collectionId: Long, frozenAt: Long?)

    @Transaction
    @Query("SELECT * FROM collections WHERE collectionId = :collectionId")
    suspend fun getWithFrozenRecords(collectionId: Long): FrozenSelection?

    @Insert
    suspend fun insertFrozenRecords(records: List<SavedAnalysisRecordEntity>)

    @Query("DELETE FROM saved_analysis_records WHERE collectionId = :collectionId")
    suspend fun clearFrozenRecords(collectionId: Long)

    @Query("SELECT COUNT(*) FROM saved_analysis_records WHERE collectionId = :collectionId")
    suspend fun countFrozenRecords(collectionId: Long): Int

    /**
     * How many of a frozen selection's recordings are still in the library.
     *
     * Frozen numbers stay valid when their recordings are deleted, but the screen should say so
     * rather than silently offering links that go nowhere.
     */
    @Query(
        """
        SELECT COUNT(*) FROM saved_analysis_records
        WHERE collectionId = :collectionId
          AND recordId IN (SELECT recordId FROM bpm_records)
        """
    )
    suspend fun countFrozenRecordsStillPresent(collectionId: Long): Int
}
