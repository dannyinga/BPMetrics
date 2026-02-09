package inga.bpmetrics.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BpmRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBpmRecordGetId(record: BpmRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBpmDataPoint(dataPoint: BpmDataPointEntity): Long

    @Query("SELECT * FROM bpm_data_points WHERE recordOwnerId = :id")
    suspend fun getAllDataPointsForRecord(id: Long) : List<BpmDataPointEntity>

    @Query("SELECT * FROM bpm_data_points WHERE dataPointId = :id")
    suspend fun getDataPoint(id: Long) : BpmDataPointEntity

    @Query("SELECT * FROM bpm_records")
    suspend fun getAllRecordEntities() : List<BpmRecordEntity>

    @Query("SELECT * FROM bpm_records WHERE recordId = :id")
    suspend fun getRecordEntity(id: Long) : BpmRecordEntity

    @Transaction
    @Query("SELECT * FROM bpm_records WHERE recordId = :id")
    suspend fun getRecord(id: Long) : BpmRecord

    @Transaction
    @Query("SELECT * FROM bpm_records ORDER BY date DESC")
    fun getAllRecordsFlow() : Flow<List<BpmRecord>>

    @Delete
    suspend fun deleteRecord(record: BpmRecordEntity)

    @Query("DELETE FROM bpm_records WHERE recordId = :id")
    suspend fun deleteRecordById(id: Long)

    @Query("DELETE FROM bpm_records ")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM bpm_data_points ")
    suspend fun deleteAllDataPoints()

}