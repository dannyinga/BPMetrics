package inga.bpmetrics.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * DAO for temporary BPM data point storage and pending record sync on the watch.
 */
@Dao
interface RecordingDAO {
    // --- Data Point Operations ---
    @Insert
    suspend fun insert(point: LocalBpmDataPoint)

    /**
     * Inserts a whole batch of samples in one transaction.
     *
     * Health Services delivers samples in batches, so writing them individually costs one
     * transaction — and one disk sync — per second for the length of a recording.
     */
    @Insert
    suspend fun insertAll(points: List<LocalBpmDataPoint>)

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

    @Query("SELECT * FROM pending_records ORDER BY id ASC")
    suspend fun getAllPendingRecords(): List<PendingRecordEntity>

    /** Notes where a record was handed to Play Services, so its delivery can be followed up. */
    @Query("UPDATE pending_records SET dataItemPath = :path WHERE id = :id")
    suspend fun markHandedOver(id: Long, path: String)

    @Query("DELETE FROM pending_records WHERE id = :id")
    suspend fun deletePendingRecordById(id: Long)

    @Delete
    suspend fun deletePendingRecord(record: PendingRecordEntity)
}

/**
 * Room database for persistent workout data on the watch.
 */
@Database(
    entities = [LocalBpmDataPoint::class, PendingRecordEntity::class],
    version = 3,
    exportSchema = false
)
abstract class RecordingDB : RoomDatabase() {
    abstract fun bpmWatchDao(): RecordingDAO

    companion object {
        @Volatile private var INSTANCE: RecordingDB? = null

        /**
         * Migration from version 1 to 2: adds pending_records table.
         * Idempotent: checks if table already exists.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        recordJson TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * Migration from version 2 to 3: records where a pending record was handed over.
         *
         * Nullable with no default, which is exactly what `dataItemPath: String? = null` declares
         * — the Kotlin default is a constructor default, not a SQL one. Adding `DEFAULT` here
         * would make the live schema disagree with the entity and fail Room's validation on every
         * upgraded watch.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_records ADD COLUMN dataItemPath TEXT")
            }
        }

        fun getInstance(context: Context): RecordingDB {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RecordingDB::class.java,
                    "bpm_watch_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // NEVER add fallbackToDestructiveMigration() here.
                // Data loss is unacceptable.
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
