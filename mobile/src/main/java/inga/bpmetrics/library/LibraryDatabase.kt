package inga.bpmetrics.library

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
     * Updates the device ID and wearer name of a specific record.
     */
    @Query("UPDATE bpm_records SET deviceId = :deviceId, wearerName = :wearerName WHERE recordId = :id")
    suspend fun updateDeviceAndWearer(id: Long, deviceId: String, wearerName: String)

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
        RecordTagCrossRef::class,
        WatchEntity::class,
        SavedAnalysisEntity::class,
        SavedAnalysisRecordEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun bpmRecordDao(): BpmRecordDao
    abstract fun tagDao(): TagDao
    abstract fun watchDao(): WatchDao
    abstract fun savedAnalysisDao(): SavedAnalysisDao

    companion object {
        private const val TAG = "LibraryDatabase"
        private const val DB_NAME = "bpmetrics_db"

        @Volatile private var INSTANCE: LibraryDatabase? = null

        /**
         * Migration from schema version 4 to 5.
         * Adds deviceId and wearerName columns to bpm_records.
         * Idempotent: checks if columns already exist before adding them.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existingColumns = mutableSetOf<String>()
                db.query("PRAGMA table_info(bpm_records)").use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        existingColumns.add(cursor.getString(nameIdx))
                    }
                }

                if ("deviceId" !in existingColumns) {
                    db.execSQL("ALTER TABLE bpm_records ADD COLUMN deviceId TEXT NOT NULL DEFAULT 'Watch'")
                    android.util.Log.i(TAG, "MIGRATION_4_5: Added deviceId column")
                } else {
                    android.util.Log.i(TAG, "MIGRATION_4_5: deviceId column already exists, skipping")
                }

                if ("wearerName" !in existingColumns) {
                    db.execSQL("ALTER TABLE bpm_records ADD COLUMN wearerName TEXT NOT NULL DEFAULT ''")
                    android.util.Log.i(TAG, "MIGRATION_4_5: Added wearerName column")
                } else {
                    android.util.Log.i(TAG, "MIGRATION_4_5: wearerName column already exists, skipping")
                }
            }
        }

        /**
         * Migration from schema version 5 to 6.
         *
         * Adds the watch registry and links records to it. Existing records are grouped by their
         * reported device id, which is the only identity older records carry — so two watches of
         * the same model land in one entry and have to be separated by hand afterwards.
         *
         * Existing wearer names are left untouched. They are historical attributions and belong
         * to the record, not to the watch.
         *
         * Idempotent: every step checks before it acts.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watches (
                        watchId TEXT NOT NULL PRIMARY KEY,
                        customName TEXT NOT NULL DEFAULT '',
                        lastKnownModel TEXT NOT NULL DEFAULT '',
                        lastKnownNodeId TEXT NOT NULL DEFAULT '',
                        colorArgb INTEGER,
                        firstSeen INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                if (!columnExists(db, "bpm_records", "watchId")) {
                    db.execSQL("ALTER TABLE bpm_records ADD COLUMN watchId TEXT DEFAULT NULL")
                    android.util.Log.i(TAG, "MIGRATION_5_6: Added watchId column")
                } else {
                    android.util.Log.i(TAG, "MIGRATION_5_6: watchId column already exists, skipping")
                }

                // Seed the registry from whatever device ids the existing records carry, using the
                // record dates as the first and last time each was seen.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO watches (watchId, customName, lastKnownModel, lastKnownNodeId, colorArgb, firstSeen, lastSeen)
                    SELECT deviceId, '', deviceId, '', NULL, MIN(date), MAX(date)
                    FROM bpm_records
                    WHERE deviceId IS NOT NULL AND deviceId != ''
                    GROUP BY deviceId
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    UPDATE bpm_records
                    SET watchId = deviceId
                    WHERE watchId IS NULL AND deviceId IS NOT NULL AND deviceId != ''
                    """.trimIndent()
                )

                android.util.Log.i(TAG, "MIGRATION_5_6: Watch registry seeded from existing records")
            }
        }

        /**
         * Migration from schema version 6 to 7.
         *
         * Adds storage for saved analyses. Purely additive — nothing existing is touched.
         *
         * The SQL must match what Room generates for these entities exactly, down to column
         * defaults and index names; [LibraryDatabaseMigrationTest] is what checks that.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_analyses (
                        analysisId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        filterDescription TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_analysis_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        analysisId INTEGER NOT NULL,
                        recordId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        date INTEGER NOT NULL,
                        minBpm REAL,
                        avgBpm REAL,
                        maxBpm REAL,
                        activeDurationMs INTEGER NOT NULL,
                        tagsEncoded TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY(analysisId) REFERENCES saved_analyses(analysisId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_saved_analysis_records_analysisId ON saved_analysis_records (analysisId)"
                )

                android.util.Log.i(TAG, "MIGRATION_6_7: Saved analysis tables created")
            }
        }

        /** Whether [column] is already present on [table], so a migration can re-run safely. */
        private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query("PRAGMA table_info($table)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == column) return true
                }
            }
            return false
        }

        /**
         * Creates a timestamped backup of the database in a persistent directory.
         * Backups go to the app's files directory (NOT cache, which can be cleared).
         */
        private fun performPreMigrationBackup(context: Context) {
            try {
                val dbFile = context.getDatabasePath(DB_NAME) ?: return
                if (!dbFile.exists()) return

                val backupDir = java.io.File(context.filesDir, "db_backups")
                backupDir.mkdirs()

                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val backupFile = java.io.File(backupDir, "${DB_NAME}_backup_$timestamp.db")
                dbFile.copyTo(backupFile, overwrite = true)

                // Also copy WAL and SHM files if they exist
                val walFile = java.io.File(dbFile.path + "-wal")
                if (walFile.exists()) {
                    walFile.copyTo(java.io.File(backupDir, "${DB_NAME}_backup_${timestamp}.db-wal"), overwrite = true)
                }
                val shmFile = java.io.File(dbFile.path + "-shm")
                if (shmFile.exists()) {
                    shmFile.copyTo(java.io.File(backupDir, "${DB_NAME}_backup_${timestamp}.db-shm"), overwrite = true)
                }

                android.util.Log.i(TAG, "Pre-migration backup saved: ${backupFile.absolutePath}")

                // Keep only the 5 most recent backups to avoid filling storage
                val allBackups = backupDir.listFiles { f -> f.name.startsWith(DB_NAME) && f.name.endsWith(".db") }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
                allBackups.drop(5).forEach { old ->
                    old.delete()
                    java.io.File(old.path + "-wal").delete()
                    java.io.File(old.path + "-shm").delete()
                    android.util.Log.d(TAG, "Cleaned old backup: ${old.name}")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to create pre-migration backup", e)
            }
        }

        /**
         * Returns the singleton instance of [LibraryDatabase].
         *
         * CRITICAL: This builder NEVER uses fallbackToDestructiveMigration.
         * If a migration fails, the app will crash rather than silently wipe data.
         * All migrations are idempotent and wrapped with safety checks.
         */
        fun getInstance(context: Context): LibraryDatabase {
            return INSTANCE ?: synchronized(this) {
                performPreMigrationBackup(context)
                Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    // NEVER add fallbackToDestructiveMigration() here.
                    // Data loss is unacceptable. If migrations fail, crash loudly.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
