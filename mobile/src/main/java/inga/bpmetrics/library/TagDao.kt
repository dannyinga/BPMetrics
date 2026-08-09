package inga.bpmetrics.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for managing categories, tags, and record-tag assignments.
 */
@Dao
interface TagDao {

    // --- Category Operations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getAllCategoriesFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE categoryId = :id")
    suspend fun getCategoryById(id: Long): CategoryEntity?

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Delete
    suspend fun deleteCategory(category: CategoryEntity)

    // --- Tag Operations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE parentCategoryId = :categoryId ORDER BY name ASC")
    fun getTagsByCategoryFlow(categoryId: Long): Flow<List<TagEntity>>

    /** Every tag in the library, as a flow. */
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTagsFlow(): Flow<List<TagEntity>>

    /** Every tag in the library. A restore matches a backup's tag names against these. */
    @Query("SELECT * FROM tags")
    suspend fun getAllTags(): List<TagEntity>

    /** Every category. See [getAllTags]. */
    @Query("SELECT * FROM categories")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT * FROM tags WHERE tagId = :id")
    suspend fun getTagById(id: Long): TagEntity?

    @Update
    suspend fun updateTag(tag: TagEntity)

    @Delete
    suspend fun deleteTag(tag: TagEntity)

    // --- Record-Tag Assignment Operations ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecordTagCrossRef(crossRef: RecordTagCrossRef)

    @Delete
    suspend fun deleteRecordTagCrossRef(crossRef: RecordTagCrossRef)

    @Query("DELETE FROM record_tag_cross_ref WHERE recordId = :recordId AND tagId = :tagId")
    suspend fun untagRecord(recordId: Long, tagId: Long)

    /**
     * Retrieves all tags associated with a specific record ID.
     */
    @Transaction
    @Query("""
        SELECT tags.* FROM tags 
        INNER JOIN record_tag_cross_ref ON tags.tagId = record_tag_cross_ref.tagId 
        WHERE record_tag_cross_ref.recordId = :recordId
    """)
    fun getTagsForRecordFlow(recordId: Long): Flow<List<TagEntity>>

    // --- Event and group tags ---
    //
    // Applied at the level they are true at and resolved downward on read by
    // [EffectiveTagsResolver]. Nothing here writes a tag onto a recording; see §2.5 of the
    // Library and Analysis Hardening doc for why copying them down is simpler and wrong.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventTagCrossRef(crossRef: EventTagCrossRef)

    @Query("DELETE FROM event_tag_cross_ref WHERE eventId = :eventId AND tagId = :tagId")
    suspend fun untagEvent(eventId: Long, tagId: Long)

    @Query("""
        SELECT tags.* FROM tags
        INNER JOIN event_tag_cross_ref ON tags.tagId = event_tag_cross_ref.tagId
        WHERE event_tag_cross_ref.eventId = :eventId
    """)
    fun getTagsForEventFlow(eventId: Long): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroupTagCrossRef(crossRef: EventGroupTagCrossRef)

    @Query("DELETE FROM event_group_tag_cross_ref WHERE groupId = :groupId AND tagId = :tagId")
    suspend fun untagGroup(groupId: Long, tagId: Long)

    @Query("""
        SELECT tags.* FROM tags
        INNER JOIN event_group_tag_cross_ref ON tags.tagId = event_group_tag_cross_ref.tagId
        WHERE event_group_tag_cross_ref.groupId = :groupId
    """)
    fun getTagsForGroupFlow(groupId: Long): Flow<List<TagEntity>>

    /**
     * Every event's tags at once, for resolving a whole library in one query.
     *
     * The library screen needs effective tags for every visible recording. Asking per recording
     * would be one query per row while scrolling; this is one query for the lot, joined in memory.
     */
    @Query("""
        SELECT event_tag_cross_ref.eventId AS ownerId, tags.*
        FROM tags
        INNER JOIN event_tag_cross_ref ON tags.tagId = event_tag_cross_ref.tagId
    """)
    fun getAllEventTagsFlow(): Flow<List<OwnedTag>>

    @Query("""
        SELECT event_group_tag_cross_ref.groupId AS ownerId, tags.*
        FROM tags
        INNER JOIN event_group_tag_cross_ref ON tags.tagId = event_group_tag_cross_ref.tagId
    """)
    fun getAllGroupTagsFlow(): Flow<List<OwnedTag>>

    // --- Group Analysis Operations ---

    /**
     * Retrieves all tags in a category along with their average BPM across all records.
     * 
     * @param categoryId The ID of the category to analyze.
     * @return A Flow of [TagRanking] results, ordered by average BPM (descending).
     */
    @Query("""
        SELECT tags.name as tagName, AVG(bpm_records.avg) as averageBpm
        FROM tags
        INNER JOIN record_tag_cross_ref ON tags.tagId = record_tag_cross_ref.tagId
        INNER JOIN bpm_records ON record_tag_cross_ref.recordId = bpm_records.recordId
        WHERE tags.parentCategoryId = :categoryId
        GROUP BY tags.tagId
        ORDER BY averageBpm DESC
    """)
    fun getCategoryRankingFlow(categoryId: Long): Flow<List<TagRanking>>

    /**
     * Retrieves tags along with their aggregated BPM statistics within a date range.
     * 
     * This query calculates the average session-avg, the average session-max, 
     * and the average session-min for each tag.
     *
     * @param tagIds The list of tag IDs to include in the analysis.
     * @param startDate The start of the date range in milliseconds.
     * @param endDate The end of the date range in milliseconds.
     * @return A list of [AdvancedTagAnalysis] results.
     */
    @Query("""
        SELECT 
            tags.name as tagName, 
            AVG(bpm_records.avg) as averageBpm,
            AVG(max_points.bpm) as avgMaxBpm,
            AVG(min_points.bpm) as avgMinBpm
        FROM tags
        INNER JOIN record_tag_cross_ref ON tags.tagId = record_tag_cross_ref.tagId
        INNER JOIN bpm_records ON record_tag_cross_ref.recordId = bpm_records.recordId
        LEFT JOIN bpm_data_points AS max_points ON bpm_records.maxId = max_points.dataPointId
        LEFT JOIN bpm_data_points AS min_points ON bpm_records.minId = min_points.dataPointId
        WHERE tags.tagId IN (:tagIds) AND bpm_records.date BETWEEN :startDate AND :endDate
        GROUP BY tags.tagId
    """)
    suspend fun getAdvancedTagAnalysis(tagIds: List<Long>, startDate: Long, endDate: Long): List<AdvancedTagAnalysis>
}

/**
 * Data class to hold results for advanced tag analysis.
 */
data class AdvancedTagAnalysis(
    val tagName: String,
    val averageBpm: Double,
    val avgMaxBpm: Double?,
    val avgMinBpm: Double?
)

/**
 * Data class to hold the result of a tag ranking query.
 *
 * @property tagName The name of the tag.
 * @property averageBpm The average BPM calculated across all records assigned this tag.
 */
data class TagRanking(
    val tagName: String,
    val averageBpm: Double
)
