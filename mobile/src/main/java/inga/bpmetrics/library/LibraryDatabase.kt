package inga.bpmetrics.library

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for managing [BpmRecordEntity] and [BpmDataPointEntity]
 * records in the Room database.
 */
@Dao
interface BpmRecordDao {

    /**
     * Inserts a new [BpmRecordEntity] and returns its generated record ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBpmRecordGetId(record: BpmRecordEntity): Long

    /**
     * Inserts a single [BpmDataPointEntity] and returns its generated ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBpmDataPoint(dataPoint: BpmDataPointEntity): Long

    /**
     * Batch inserts a list of [BpmDataPointEntity] records.
     *
     * @return A list of the generated IDs for the inserted data points.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllDataPoints(dataPoints: List<BpmDataPointEntity>): List<Long>

    /**
     * Updates only the title of a specific record.
     *
     * @param id The ID of the record to update.
     * @param newTitle The new title for the record.
     */
    @Query("UPDATE bpm_records SET title = :newTitle WHERE recordId = :id")
    suspend fun updateTitleOnly(id: Long, newTitle: String)

    /**
     * Updates only the description of a specific record.
     *
     * @param id The ID of the record to update.
     * @param newDescription The new description for the record.
     */
    @Query("UPDATE bpm_records SET description = :newDescription WHERE recordId = :id")
    suspend fun updateDescriptionOnly(id: Long, newDescription: String)

    /**
     * Updates a record with its calculated analysis results.
     *
     * @param id The ID of the record to update.
     * @param minId The ID of the minimum BPM data point.
     * @param maxId The ID of the maximum BPM data point.
     * @param avg The calculated average BPM.
     */
    @Query("UPDATE bpm_records SET minId = :minId, maxId = :maxId, avg = :avg WHERE recordId = :id")
    suspend fun updateAnalysis(id: Long, minId: Long?, maxId: Long?, avg: Double?)
    
    /**
     * Counts how many records have a title starting with the specified prefix.
     * Used for auto-incrementing titles like "Untitled 5" or "Spiderman 2".
     */
    @Query("SELECT COUNT(*) FROM bpm_records WHERE title LIKE :prefix || ' %' OR title = :prefix")
    suspend fun countRecordsWithTitlePrefix(prefix: String): Int

    /**
     * Retrieves all data points associated with a specific record ID.
     */
    @Query("SELECT * FROM bpm_data_points WHERE recordOwnerId = :id")
    suspend fun getAllDataPointsForRecord(id: Long) : List<BpmDataPointEntity>

    /**
     * Retrieves a single data point by its unique ID.
     */
    @Query("SELECT * FROM bpm_data_points WHERE dataPointId = :id")
    suspend fun getDataPoint(id: Long) : BpmDataPointEntity

    /**
     * Retrieves the metadata for all BPM records in the database.
     */
    @Query("SELECT * FROM bpm_records")
    suspend fun getAllRecordEntities() : List<BpmRecordEntity>

    /**
     * Retrieves the metadata for a specific BPM record by its ID.
     */
    @Query("SELECT * FROM bpm_records WHERE recordId = :id")
    suspend fun getRecordEntity(id: Long) : BpmRecordEntity

    /**
     * Retrieves a complete [BpmRecord] (including all associated data points and tags) by its ID.
     */
    @Transaction
    @Query("SELECT * FROM bpm_records WHERE recordId = :id")
    suspend fun getRecord(id: Long) : BpmRecord

    /**
     * Returns a [Flow] that emits an updated list of all complete [BpmRecord]s
     * whenever the database content changes.
     */
    @Transaction
    @Query("SELECT * FROM bpm_records ORDER BY date DESC")
    fun getAllRecordsFlow() : Flow<List<BpmRecord>>

    /**
     * Deletes a specific record metadata object.
     */
    @Delete
    suspend fun deleteRecord(record: BpmRecordEntity)

    /**
     * Deletes a record from the database by its ID.
     */
    @Query("DELETE FROM bpm_records WHERE recordId = :id")
    suspend fun deleteRecordById(id: Long)

    /**
     * Deletes all data points associated with a specific record ID.
     */
    @Query("DELETE FROM bpm_data_points WHERE recordOwnerId = :id")
    suspend fun deleteDataPointsByRecordId(id: Long)

    /**
     * Deletes all records from the [bpm_records] table.
     */
    @Query("DELETE FROM bpm_records ")
    suspend fun deleteAllRecords()

    /**
     * Deletes all data points from the [bpm_data_points] table.
     */
    @Query("DELETE FROM bpm_data_points ")
    suspend fun deleteAllDataPoints()
}

@Database(
    entities = [
        BpmRecordEntity::class, 
        BpmDataPointEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        RecordTagCrossRef::class
    ],
    version = 4,
    exportSchema = true
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun bpmRecordDao(): BpmRecordDao
    abstract fun tagDao(): TagDao

    companion object {
        @Volatile private var INSTANCE: LibraryDatabase? = null

        /**
         * Returns the singleton instance of [LibraryDatabase].
         */
        fun getInstance(context: Context): LibraryDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    "bpmetrics_db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
