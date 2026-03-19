package inga.bpmetrics.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for temporary BPM data point storage and pending record sync on the watch.
 */
@Dao
interface RecordingDAO {
    // --- Data Point Operations ---
    @Insert
    suspend fun insert(point: LocalBpmDataPoint)

    @Query("DELETE FROM local_bpm_data_points")
    suspend fun deleteAll()

    @Query("SELECT * FROM local_bpm_data_points ORDER BY timestamp ASC")
    suspend fun getAllPoints(): List<LocalBpmDataPoint>

    @Query("SELECT * FROM local_bpm_data_points ORDER BY timestamp ASC LIMIT 1")
    suspend fun getFirstPoint(): LocalBpmDataPoint?

    // --- Pending Record Operations ---
    @Insert
    suspend fun insertPendingRecord(record: PendingRecordEntity)

    @Query("SELECT * FROM pending_records ORDER BY id ASC")
    fun getAllPendingRecordsFlow(): Flow<List<PendingRecordEntity>>

    @Delete
    suspend fun deletePendingRecord(record: PendingRecordEntity)
}

/**
 * Room database for persistent workout data on the watch.
 */
@Database(
    entities = [LocalBpmDataPoint::class, PendingRecordEntity::class], 
    version = 2, 
    exportSchema = false
)
abstract class RecordingDB : RoomDatabase() {
    abstract fun bpmWatchDao(): RecordingDAO

    companion object {
        @Volatile private var INSTANCE: RecordingDB? = null

        fun getInstance(context: Context): RecordingDB {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RecordingDB::class.java,
                    "bpm_watch_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
