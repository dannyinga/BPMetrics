package inga.bpmetrics.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * DAO for temporary BPM data point storage on the watch.
 */
@Dao
interface BpmWatchDao {
    @Insert
    suspend fun insert(point: LocalBpmDataPoint)

    @Query("SELECT * FROM local_bpm_data_points ORDER BY timestamp ASC")
    fun getAllPointsFlow(): Flow<List<LocalBpmDataPoint>>

    @Query("DELETE FROM local_bpm_data_points")
    suspend fun deleteAll()

    @Query("SELECT * FROM local_bpm_data_points ORDER BY timestamp ASC")
    suspend fun getAllPoints(): List<LocalBpmDataPoint>

    @Query("SELECT * FROM local_bpm_data_points ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPoint(): LocalBpmDataPoint?

    @Query("SELECT * FROM local_bpm_data_points ORDER BY timestamp ASC LIMIT 1")
    suspend fun getFirstPoint(): LocalBpmDataPoint?
}

/**
 * Room database for persistent workout data on the watch.
 */
@Database(entities = [LocalBpmDataPoint::class], version = 1, exportSchema = false)
abstract class BpmWatchDatabase : RoomDatabase() {
    abstract fun bpmWatchDao(): BpmWatchDao

    companion object {
        @Volatile private var INSTANCE: BpmWatchDatabase? = null

        fun getInstance(context: Context): BpmWatchDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    BpmWatchDatabase::class.java,
                    "bpm_watch_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
