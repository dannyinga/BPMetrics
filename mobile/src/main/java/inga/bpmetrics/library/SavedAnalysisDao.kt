package inga.bpmetrics.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Access to saved analyses.
 */
@Dao
interface SavedAnalysisDao {

    /** Saved analyses, newest first. */
    @Query("SELECT * FROM saved_analyses ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<SavedAnalysisEntity>>

    @Transaction
    @Query("SELECT * FROM saved_analyses WHERE analysisId = :analysisId")
    suspend fun getAnalysis(analysisId: Long): SavedAnalysis?

    @Insert
    suspend fun insertAnalysis(analysis: SavedAnalysisEntity): Long

    @Insert
    suspend fun insertRecords(records: List<SavedAnalysisRecordEntity>)

    @Query("UPDATE saved_analyses SET name = :name WHERE analysisId = :analysisId")
    suspend fun rename(analysisId: Long, name: String)

    /** Cascades to the captured records via the foreign key. */
    @Query("DELETE FROM saved_analyses WHERE analysisId = :analysisId")
    suspend fun deleteAnalysis(analysisId: Long)

    @Query("SELECT COUNT(*) FROM saved_analysis_records WHERE analysisId = :analysisId")
    suspend fun countRecords(analysisId: Long): Int

    /**
     * How many of a saved analysis's recordings are still in the library.
     *
     * A frozen analysis stays valid when its recordings are deleted, but the UI should say so
     * rather than silently offering links that go nowhere.
     */
    @Query(
        """
        SELECT COUNT(*) FROM saved_analysis_records
        WHERE analysisId = :analysisId
          AND recordId IN (SELECT recordId FROM bpm_records)
        """
    )
    suspend fun countRecordsStillPresent(analysisId: Long): Int
}
