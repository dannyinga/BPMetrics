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
    @Query(
        "UPDATE bpm_records SET deviceId = :deviceId, wearerName = :wearerName, personId = :personId " +
            "WHERE recordId = :id"
    )
    suspend fun updateDeviceAndWearer(id: Long, deviceId: String, wearerName: String, personId: Long?)

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
     * The two figures that used to be recomputed from the readings on every read.
     *
     * Written at ingest and by the backfill. See [BpmRecordEntity.activeDurationMs].
     */
    @Query(
        "UPDATE bpm_records SET activeDurationMs = :activeDurationMs, zonesEncoded = :zonesEncoded " +
            "WHERE recordId = :id"
    )
    suspend fun updateDerivedFigures(id: Long, activeDurationMs: Long, zonesEncoded: String)

    /**
     * Recordings whose derived figures have never been computed, oldest first.
     *
     * The gate for the backfill: a query rather than a preference, so it is self-healing. A pass
     * that dies halfway leaves the rest still null and simply finishes next launch, and a row
     * arriving by some path that forgets to compute them is repaired rather than wrong forever.
     */
    @Query("SELECT recordId FROM bpm_records WHERE activeDurationMs IS NULL LIMIT :limit")
    suspend fun recordIdsMissingDerivedFigures(limit: Int): List<Long>

    /** The readings for one recording, in time order. */
    @Query(
        "SELECT * FROM bpm_data_points WHERE recordOwnerId = :recordId ORDER BY timestamp ASC"
    )
    suspend fun dataPointsFor(recordId: Long): List<BpmDataPointEntity>

    /**
     * The readings for a set of recordings, in one query.
     *
     * What a chart or an export asks for once it knows its scope. Chunked by the caller against
     * [LibraryRepository.SQL_VARIABLE_LIMIT], as every other multi-id query here is.
     */
    @Query(
        "SELECT * FROM bpm_data_points WHERE recordOwnerId IN (:recordIds) " +
            "ORDER BY recordOwnerId ASC, timestamp ASC"
    )
    suspend fun dataPointsForAll(recordIds: List<Long>): List<BpmDataPointEntity>
    
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

    /** The clock a recording is read in, as resolved by the one writer. */
    @Query("UPDATE bpm_records SET timeZoneId = :zoneId WHERE recordId = :id")
    suspend fun updateResolvedTimeZone(id: Long, zoneId: String?)

    /** A location chosen for this recording specifically, overriding what it inherits. */
    @Query("UPDATE bpm_records SET locationId = :locationId WHERE recordId = :id")
    suspend fun updateRecordLocation(id: Long, locationId: Long?)

    /** Forgets a location that has been deleted. See [EventDao.forgetLocation]. */
    @Query("UPDATE bpm_records SET locationId = NULL WHERE locationId = :locationId")
    suspend fun forgetLocation(locationId: Long)

    /** The same as a flow, for anything that has to recompute when the library changes. */
    @Query("SELECT * FROM bpm_records")
    fun getAllRecordEntitiesFlow(): Flow<List<BpmRecordEntity>>

    /**
     * Retrieves the metadata for a specific BPM record by its ID.
     */
    @Query("SELECT * FROM bpm_records WHERE recordId = :id")
    suspend fun getRecordEntity(id: Long) : BpmRecordEntity

    /** One recording with its readings, for a chart or an export. */
    @Transaction
    @Query("SELECT * FROM bpm_records WHERE recordId = :id")
    suspend fun getRecord(id: Long) : BpmRecordWithPoints?

    /**
     * The recordings in a scope, with their readings.
     *
     * The only bulk path that loads readings, and it is bounded by the scope that asked. Chunk
     * against [LibraryRepository.SQL_VARIABLE_LIMIT].
     */
    @Transaction
    @Query("SELECT * FROM bpm_records WHERE recordId IN (:ids) ORDER BY date DESC")
    suspend fun getRecordsWithPoints(ids: List<Long>): List<BpmRecordWithPoints>

    /**
     * Every recording, **without** its readings, as a flow.
     *
     * What the library is. This used to join `bpm_data_points`, so the always-on stream held every
     * reading in the library and Room rebuilt all of them on any write — see §9 of the product
     * doc. Everything a list, a filter or a summary needs is a column on the row.
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

/**
 * The schema version, in one place.
 *
 * There were two: the `@Database` annotation and a `CURRENT_VERSION` constant below it, commented
 * "must match the version above". It did not match — the annotation had moved on and the constant
 * had been left at 18. That constant decides whether a backup is taken before a migration runs, so
 * the one upgrade most in need of a safety net was the one that skipped it, silently.
 *
 * A file-level constant because an annotation argument cannot reference the class it annotates.
 */
internal const val LIBRARY_DB_VERSION = 30

@Database(
    entities = [
        BpmRecordEntity::class,
        BpmDataPointEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        RecordTagCrossRef::class,
        WatchEntity::class,
        PersonEntity::class,
        EventEntity::class,
        EventTagCrossRef::class,
        CollectionEntity::class,
        CollectionEventCrossRef::class,
        CollectionRecordCrossRef::class,
        LocationEntity::class,
        EventWindowPersonCrossRef::class,
        SavedAnalysisRecordEntity::class,
        ExportPresetEntity::class,
        RenderJobEntity::class
    ],
    version = LIBRARY_DB_VERSION,
    exportSchema = true
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun bpmRecordDao(): BpmRecordDao
    abstract fun tagDao(): TagDao
    abstract fun watchDao(): WatchDao
    abstract fun personDao(): PersonDao
    abstract fun eventDao(): EventDao
    abstract fun collectionDao(): CollectionDao
    abstract fun locationDao(): LocationDao
    abstract fun exportPresetDao(): ExportPresetDao
    abstract fun renderJobDao(): RenderJobDao

    companion object {
        private const val TAG = "LibraryDatabase"
        private const val DB_NAME = "bpmetrics_db"

        /** The version the app expects, used to spot a pending migration. See [LIBRARY_DB_VERSION]. */
        private const val CURRENT_VERSION = LIBRARY_DB_VERSION

        private const val MAX_BACKUPS = 5

        /** Headroom over the database's own size before a backup is attempted. */
        private const val SPACE_SAFETY_FACTOR = 2.0

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

        /**
         * Migration from schema version 7 to 8.
         *
         * Splits the watch's single name into two: what the watch is called, and who is wearing
         * it. One field could not serve both — naming a watch after its wearer meant renaming the
         * hardware every time it changed hands, and made "which watch recorded this" unanswerable.
         *
         * The old value moves to the wearer, because that is what it meant: the field was
         * presented as "who is wearing this watch" and was stamped onto records as the wearer.
         * Watches are left unnamed, falling back to their model until someone names them.
         *
         * The table is recreated rather than altered in place. SQLite can only drop a column on
         * newer versions, and Room compares the full column set — a leftover column would fail
         * validation just as surely as a missing one.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "watches", "customName")) {
                    android.util.Log.i(TAG, "MIGRATION_7_8: watches already split, skipping")
                    return
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watches_new (
                        watchId TEXT NOT NULL,
                        deviceName TEXT NOT NULL DEFAULT '',
                        currentWearerName TEXT NOT NULL DEFAULT '',
                        lastKnownModel TEXT NOT NULL DEFAULT '',
                        lastKnownNodeId TEXT NOT NULL DEFAULT '',
                        colorArgb INTEGER,
                        firstSeen INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        PRIMARY KEY(watchId)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO watches_new (watchId, deviceName, currentWearerName, lastKnownModel, lastKnownNodeId, colorArgb, firstSeen, lastSeen)
                    SELECT watchId, '', customName, lastKnownModel, lastKnownNodeId, colorArgb, firstSeen, lastSeen
                    FROM watches
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE watches")
                db.execSQL("ALTER TABLE watches_new RENAME TO watches")

                android.util.Log.i(TAG, "MIGRATION_7_8: Split watch name into device and wearer")
            }
        }

        /**
         * Migration from schema version 8 to 9.
         *
         * Captures the wearer and watch on each analysed recording, so a saved analysis can rank
         * by them. Both are copied at save time like every other snapshot value — a stored
         * analysis must not start reporting different people because a watch changed hands.
         *
         * Analyses saved before this have no such values and simply offer no wearer or watch
         * comparison, which is correct: that information was not captured.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "saved_analysis_records", "wearerName")) {
                    db.execSQL("ALTER TABLE saved_analysis_records ADD COLUMN wearerName TEXT NOT NULL DEFAULT ''")
                }
                if (!columnExists(db, "saved_analysis_records", "watchName")) {
                    db.execSQL("ALTER TABLE saved_analysis_records ADD COLUMN watchName TEXT NOT NULL DEFAULT ''")
                }
                android.util.Log.i(TAG, "MIGRATION_8_9: Snapshot wearer and watch captured")
            }
        }

        /**
         * Migration from schema version 9 to 10.
         *
         * Lets a saved analysis record which of the two questions it asked, and the stretch of
         * clock a same-time analysis covered. Everything saved before this was a group analysis,
         * which is what the default says.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "saved_analyses", "kind")) {
                    db.execSQL("ALTER TABLE saved_analyses ADD COLUMN kind TEXT NOT NULL DEFAULT 'GROUP'")
                }
                if (!columnExists(db, "saved_analyses", "windowStartMs")) {
                    db.execSQL("ALTER TABLE saved_analyses ADD COLUMN windowStartMs INTEGER")
                }
                if (!columnExists(db, "saved_analyses", "windowEndMs")) {
                    db.execSQL("ALTER TABLE saved_analyses ADD COLUMN windowEndMs INTEGER")
                }
                android.util.Log.i(TAG, "MIGRATION_9_10: Saved analyses can record their kind")
            }
        }

        /**
         * Migration from schema version 10 to 11: wearers become people.
         *
         * A wearer was a bare string copied onto every record. This creates a profile for each
         * distinct name already in the library or the watch registry, then points the records and
         * watches at those profiles — so an upgrade arrives with everyone already set up rather
         * than an empty People tab and a library full of names it does not recognise.
         *
         * `wearerName` is deliberately left in place on every record. It is what a recording falls
         * back to if its person is later deleted, which is the difference between a deleted profile
         * leaving readable history and leaving anonymous history.
         *
         * Names are matched exactly, so "Kyle" and "kyle" become two profiles. Merging them
         * automatically would be a guess, and the wrong guess silently merges two real people;
         * leaving both is something the wearer can fix in a few taps.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `people` (
                        `personId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `colorArgb` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                if (!columnExists(db, "bpm_records", "personId")) {
                    db.execSQL("ALTER TABLE bpm_records ADD COLUMN personId INTEGER DEFAULT NULL")
                }
                if (!columnExists(db, "watches", "currentPersonId")) {
                    db.execSQL("ALTER TABLE watches ADD COLUMN currentPersonId INTEGER DEFAULT NULL")
                }

                // Only seed once. Re-running must not create a second profile for everybody.
                val alreadySeeded = db.query("SELECT COUNT(*) FROM people").use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) > 0
                }

                if (!alreadySeeded) {
                    val now = System.currentTimeMillis()
                    db.execSQL(
                        """
                        INSERT INTO people (name, colorArgb, createdAt)
                        SELECT name, 0, $now FROM (
                            SELECT DISTINCT wearerName AS name FROM bpm_records WHERE wearerName != ''
                            UNION
                            SELECT DISTINCT currentWearerName AS name FROM watches WHERE currentWearerName != ''
                        ) ORDER BY name
                        """.trimIndent()
                    )

                    // Built from the palette rather than written out here: these are signed ARGB
                    // ints, and hand-copying them into SQL is how they end up subtly wrong.
                    val branches = PersonColors.PALETTE.mapIndexed { index, color ->
                        "WHEN $index THEN $color"
                    }.joinToString(" ")
                    db.execSQL(
                        "UPDATE people SET colorArgb = CASE (personId % ${PersonColors.PALETTE.size}) $branches ELSE ${PersonColors.PALETTE.first()} END"
                    )
                }

                db.execSQL(
                    """
                    UPDATE bpm_records
                    SET personId = (SELECT personId FROM people WHERE people.name = bpm_records.wearerName)
                    WHERE wearerName != '' AND personId IS NULL
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE watches
                    SET currentPersonId = (SELECT personId FROM people WHERE people.name = watches.currentWearerName)
                    WHERE currentWearerName != '' AND currentPersonId IS NULL
                    """.trimIndent()
                )

                android.util.Log.i(TAG, "MIGRATION_10_11: Wearers are now people with their own colours")
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
         * Copies the database aside before a migration rewrites it.
         *
         * Three things here are load-bearing, and the absence of each of them turned a full phone
         * into an app that could not be opened at all:
         *
         * 1. It only runs when a migration is actually pending. It used to run on every single
         *    launch — despite the name — so opening the app copied the whole database every time.
         * 2. Old backups are pruned *first*, and the copy is skipped unless there is comfortably
         *    room for it. Pruning used to happen after the copy, so a copy that failed for want of
         *    space skipped the cleanup that would have made space.
         * 3. A failed copy deletes what it managed to write. Each attempt used a fresh timestamped
         *    name, so failed attempts accumulated as partial files, each launch consuming a little
         *    more of the space that was already gone.
         *
         * A missing backup is survivable. A device too full to open the app is not, so when space
         * is short this gives up rather than making the problem worse.
         */
        private fun performPreMigrationBackup(context: Context) {
            try {
                val dbFile = context.getDatabasePath(DB_NAME) ?: return
                if (!dbFile.exists()) return
                if (!migrationPending(dbFile)) return

                val backupDir = java.io.File(context.filesDir, "db_backups")
                backupDir.mkdirs()

                pruneBackups(backupDir, keep = MAX_BACKUPS - 1)

                val needed = (dbFile.length() * SPACE_SAFETY_FACTOR).toLong()
                if (backupDir.usableSpace < needed) {
                    android.util.Log.w(
                        TAG,
                        "Skipping pre-migration backup: needs ${needed / 1024}KB, " +
                            "${backupDir.usableSpace / 1024}KB free"
                    )
                    return
                }

                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val backupFile = java.io.File(backupDir, "${DB_NAME}_backup_$timestamp.db")

                try {
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
                } catch (e: Exception) {
                    // Leave nothing half-written behind, or the next attempt starts with less room
                    // than this one had.
                    backupFile.delete()
                    java.io.File(backupDir, "${DB_NAME}_backup_${timestamp}.db-wal").delete()
                    java.io.File(backupDir, "${DB_NAME}_backup_${timestamp}.db-shm").delete()
                    throw e
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to create pre-migration backup", e)
            }
        }

        /**
         * Migration from schema version 11 to 12: events and event groups.
         *
         * The `CREATE TABLE` statements are copied verbatim from the generated `12.json`, which is
         * the only reliable way to write one of these. Note `createdAt INTEGER NOT NULL` with **no
         * `DEFAULT`**: the entity's `createdAt: Long = 0L` is a Kotlin constructor default, not a
         * SQL one, and adding `DEFAULT 0` here produces a column Room rejects on every upgraded
         * device while a fresh install works perfectly. That exact mistake has now been made three
         * times in this project.
         *
         * Nothing is backfilled. Existing recordings start unfiled, which is the correct state —
         * they were made before anyone said what they were part of.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_groups` (" +
                        "`groupId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`notes` TEXT NOT NULL DEFAULT '', " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `events` (" +
                        "`eventId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`groupId` INTEGER DEFAULT NULL, " +
                        "`notes` TEXT NOT NULL DEFAULT '', " +
                        "`createdAt` INTEGER NOT NULL)"
                )

                if (!columnExists(db, "bpm_records", "eventId")) {
                    db.execSQL("ALTER TABLE bpm_records ADD COLUMN eventId INTEGER DEFAULT NULL")
                }

                android.util.Log.i(TAG, "MIGRATION_11_12: Recordings can belong to events")
            }
        }

        /**
         * Migration from 12 to 13: tags can be applied to events and to groups.
         *
         * Two join tables mirroring `record_tag_cross_ref`, with the same cascade behaviour —
         * deleting an event or a tag removes the link, never the other side.
         *
         * Nothing is backfilled and nothing is copied downward. A recording's inherited tags are
         * worked out on read by [EffectiveTagsResolver]; see §2.5 of the product doc for why
         * writing them onto the recordings would be simpler and wrong.
         *
         * SQL copied verbatim from the generated `13.json`. Room compares the live schema against
         * what it expects column by column, and a hand-written `DEFAULT` that the entity does not
         * declare produces a database that installs fine and refuses to open on upgrade — a
         * mistake this project has made three times.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_tag_cross_ref` (" +
                        "`eventId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`eventId`, `tagId`), " +
                        "FOREIGN KEY(`eventId`) REFERENCES `events`(`eventId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`tagId`) REFERENCES `tags`(`tagId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_tag_cross_ref_tagId` " +
                        "ON `event_tag_cross_ref` (`tagId`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_group_tag_cross_ref` (" +
                        "`groupId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `tagId`), " +
                        "FOREIGN KEY(`groupId`) REFERENCES `event_groups`(`groupId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`tagId`) REFERENCES `tags`(`tagId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_group_tag_cross_ref_tagId` " +
                        "ON `event_group_tag_cross_ref` (`tagId`)"
                )

                android.util.Log.i(TAG, "MIGRATION_12_13: Events and groups can carry tags")
            }
        }

        /**
         * Migration from 13 to 14: a saved analysis remembers who and where, not just what.
         *
         * Snapshots captured names only, so a frozen analysis could group by wearer but could not
         * colour anyone, could not offer the Event tab, and lost the time-in-band breakdown — the
         * ids and the bands were simply never written down, and the data points they would have
         * been recomputed from are gone by then.
         *
         * Existing rows get NULL and empty string, which is exactly the state they were already
         * in. Nothing is backfilled: the values were not recorded, and inventing them from the
         * current library would make a frozen analysis reflect a present it is meant to predate.
         *
         * `ALTER TABLE ADD COLUMN` only, so no table rebuild and no foreign keys to re-declare.
         * The defaults here match the entity's `@ColumnInfo(defaultValue = ...)` exactly — a
         * mismatch produces a database that installs fine and refuses to open on upgrade.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val additions = listOf(
                    "personId" to "INTEGER DEFAULT NULL",
                    "personColorArgb" to "INTEGER DEFAULT NULL",
                    "eventId" to "INTEGER DEFAULT NULL",
                    "eventName" to "TEXT NOT NULL DEFAULT ''",
                    "zonesEncoded" to "TEXT NOT NULL DEFAULT ''"
                )
                additions.forEach { (column, type) ->
                    if (!columnExists(db, "saved_analysis_records", column)) {
                        db.execSQL("ALTER TABLE saved_analysis_records ADD COLUMN $column $type")
                    }
                }

                android.util.Log.i(TAG, "MIGRATION_13_14: Saved analyses remember who and where")
            }
        }

        /**
         * Migration from 14 to 15: export presets.
         *
         * A new table only — nothing existing is touched, and no rows are created here. The
         * built-in presets are seeded by the repository on first read rather than by this
         * migration, so a fresh install and an upgrade take the identical path and there is one
         * definition of what ships rather than two.
         *
         * SQL copied verbatim from the generated `15.json`.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `export_presets` (" +
                        "`presetId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`configJson` TEXT NOT NULL, " +
                        "`isDefault` INTEGER NOT NULL DEFAULT 0, " +
                        "`isBuiltIn` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL)"
                )

                android.util.Log.i(TAG, "MIGRATION_14_15: Export presets")
            }
        }

        /**
         * The render queue, so a batch survives the process that queued it.
         *
         * SQL copied verbatim from the generated `16.json`. No column carries a `DEFAULT`, which is
         * deliberate: a Kotlin constructor default is not a SQL default, and a mismatched one
         * installs cleanly and then refuses to open on the next upgrade. This project has been
         * bitten by that three times.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `render_jobs` (" +
                        "`jobId` TEXT NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`recordIdsCsv` TEXT NOT NULL, " +
                        "`presetJson` TEXT NOT NULL, " +
                        "`colorsCsv` TEXT NOT NULL, " +
                        "`graphTitle` TEXT, " +
                        "`startTimeMs` INTEGER NOT NULL, " +
                        "`endTimeMs` INTEGER NOT NULL, " +
                        "`overlayUri` TEXT, " +
                        "`overlayStartedAtMs` INTEGER, " +
                        "`targetUri` TEXT, " +
                        "`status` TEXT NOT NULL, " +
                        "`error` TEXT, " +
                        "`presetName` TEXT, " +
                        "`sourceLabel` TEXT, " +
                        "`recordCount` INTEGER NOT NULL, " +
                        "`queuedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`jobId`))"
                )

                android.util.Log.i(TAG, "MIGRATION_15_16: Render queue")
            }
        }

        /**
         * Collections nest, so a festival can hold its days and a day can hold its sets.
         *
         * `DEFAULT NULL` rather than no default, matching the entity's `@ColumnInfo` exactly. The
         * pairing has to agree in both directions: a `DEFAULT` on one side only installs cleanly
         * and then refuses to open for everyone upgrading, which this project has been bitten by
         * three times. The generated `17.json` is what this was copied from.
         *
         * Deliberately not a foreign key. Deleting a parent orphans its children to the top level
         * rather than cascading — removing "Coachella" should not silently take both of its days
         * and every event inside them.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `event_groups` ADD COLUMN `parentGroupId` INTEGER DEFAULT NULL"
                )

                android.util.Log.i(TAG, "MIGRATION_16_17: Collections nest")
            }
        }

        /**
         * A person's own resting and maximum rate.
         *
         * Both nullable with `DEFAULT NULL`, matching the entity exactly — null means "use the
         * app-wide figure", which is a different thing from zero and has to stay distinguishable
         * from it. Everyone who already exists keeps inheriting the default, which is what they
         * were doing before this column existed.
         *
         * SQL copied from the generated `18.json`.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `people` ADD COLUMN `restingBpm` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `people` ADD COLUMN `maxBpm` INTEGER DEFAULT NULL")

                android.util.Log.i(TAG, "MIGRATION_17_18: Per-person heart rate zones")
            }
        }

        /**
         * A picture for an event, a collection, or one recording.
         *
         * All nullable with `DEFAULT NULL`, matching the entities exactly. Null is not "no cover"
         * on an event or a collection — it means *inherit*, which is a different thing and has to
         * stay distinguishable from a cover deliberately cleared. Everything that already exists
         * comes through with nothing set, which is what it had before these columns existed.
         *
         * The crop is REAL because it is stored as fractions of the source image rather than
         * pixels, so it survives being drawn into a wide tile, a square thumbnail and a page
         * header. Getting that wrong is not a crash, it is a cover that reframes itself depending
         * on where it is shown.
         *
         * SQL copied from the generated `19.json`.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("events", "event_groups", "bpm_records").forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `coverPath` TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `coverCropLeft` REAL DEFAULT NULL")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `coverCropTop` REAL DEFAULT NULL")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `coverCropRight` REAL DEFAULT NULL")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `coverCropBottom` REAL DEFAULT NULL")
                }

                android.util.Log.i(TAG, "MIGRATION_18_19: Events, collections and recordings can carry a cover")
            }
        }

        /**
         * A photograph for a person.
         *
         * `TEXT DEFAULT NULL`, matching the entity. Null falls back to their colour and initial,
         * which is what everyone has now and what anyone who never adds a photo keeps.
         *
         * A separate migration from 18→19 rather than folded into it, even though neither has
         * shipped. Folding is only safe if nothing has ever opened a version 19 database, and being
         * *fairly sure* of that is not the standard this chain is held to — an extra ALTER costs a
         * line, and getting it wrong costs someone their library.
         *
         * SQL copied from the generated `20.json`.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `people` ADD COLUMN `photoPath` TEXT DEFAULT NULL")

                android.util.Log.i(TAG, "MIGRATION_19_20: People can have a photograph")
            }
        }

        /**
         * How a person's photograph is framed.
         *
         * `REAL DEFAULT NULL`, matching the entity, and fractions rather than pixels for the same
         * reason a cover's crop is: the same face is drawn into a 34dp circle on a tile and a 56dp
         * one in the editor, and fractions are what survives both.
         *
         * Anyone who already added a photo comes through with no crop, which means the whole
         * picture — exactly what they had.
         *
         * SQL copied from the generated `21.json`.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `people` ADD COLUMN `photoCropLeft` REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE `people` ADD COLUMN `photoCropTop` REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE `people` ADD COLUMN `photoCropRight` REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE `people` ADD COLUMN `photoCropBottom` REAL DEFAULT NULL")

                android.util.Log.i(TAG, "MIGRATION_20_21: A person's photograph can be framed")
            }
        }

        /**
         * How much to soften a cover behind the writing.
         *
         * For covers that are themselves made of type. An event flyer carries a headliner in
         * enormous letters and a support list under it, and a tile drawn over one is two sets of
         * words fighting for the same space — a different problem from contrast, and not one that
         * protecting *our* text can fix. Blurring dissolves the flyer's type while keeping its
         * colour and composition, which is what identifies the night at a glance anyway.
         *
         * `REAL DEFAULT NULL`, matching the entities. Null is none, which is what every existing
         * cover has and what a photograph of a crowd should keep.
         *
         * SQL copied from the generated `22.json`.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("events", "event_groups", "bpm_records").forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `coverBlur` REAL DEFAULT NULL")
                }

                android.util.Log.i(TAG, "MIGRATION_21_22: A cover can be softened behind the text")
            }
        }

        /**
         * Events become the timeline: nesting, optionally time-bounded, typed.
         *
         * Additive only. Every column is nullable or defaulted, `event_groups` is untouched, and
         * nothing existing changes meaning — an event with no parent and no window behaves exactly
         * as it did. Folding collections into this tree is a separate migration, deliberately,
         * because that one rewrites data and this one cannot break anything.
         *
         * `excludedFromParentAnalysis` is `INTEGER NOT NULL DEFAULT 0` rather than nullable: there
         * is no third state between excluded and not, and a nullable boolean invites one.
         *
         * SQL copied from the generated `23.json`.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `events` ADD COLUMN `parentId` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `events` ADD COLUMN `windowStart` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `events` ADD COLUMN `windowEnd` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `events` ADD COLUMN `type` TEXT DEFAULT NULL")
                db.execSQL(
                    "ALTER TABLE `events` ADD COLUMN `excludedFromParentAnalysis` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )

                // No rows means the window applies to everyone, which is the common case and the
                // one that needs no storage at all.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_window_people` (" +
                        "`eventId` INTEGER NOT NULL, `personId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`eventId`, `personId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_window_people_personId` " +
                        "ON `event_window_people` (`personId`)"
                )

                android.util.Log.i(TAG, "MIGRATION_22_23: Events nest, and may carry a window")
            }
        }

        /**
         * Collections become events. One tree, where there were two kinds of container.
         *
         * A collection was already an event in everything but name — a thing with a title, notes, a
         * cover, tags, and other things inside it. The only difference was that it could not hold a
         * time window and events could not nest. Both of those went away in 23, so keeping two
         * tables meant maintaining two of every count, span and roll-up. That duplication is the
         * direct cause of four separate "0 recordings" defects in this app, each one a second
         * implementation of a walk disagreeing with the first.
         *
         * **`event_groups` is not dropped.** Everything is copied out, nothing is deleted, and
         * `events.groupId` keeps pointing where it always did. If this fold turns out to be wrong,
         * the original arrangement is still sitting there to be read back. The table goes a version
         * or two later, once a real library has lived on the new shape — a destructive migration
         * that runs the same day as the code that needs it has no way back.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // A migration must survive being run twice — a failure rolls back and is retried on
                // the next launch — and this one cannot rely on `INSERT OR IGNORE` to make that
                // true. The ids it inserts are derived from the current maximum, so a second pass
                // computes a *different* offset, collides with nothing, and cheerfully duplicates
                // every collection in the library. Hence an explicit guard rather than a clever
                // conflict clause.
                //
                // `type` is safe to test on: it arrived in 23 and nothing writes it yet, so no
                // event can carry a type unless this migration put it there.
                val alreadyFolded = db.query(
                    "SELECT COUNT(*) FROM events WHERE type = 'Collection'"
                ).use { if (it.moveToFirst()) it.getLong(0) else 0L } > 0L

                if (alreadyFolded) {
                    android.util.Log.i(TAG, "MIGRATION_23_24: collections already folded, skipping")
                    return
                }

                // Read once, before anything is inserted, and used as a literal below.
                //
                // The obvious `groupId + (SELECT MAX(eventId) FROM events)` inside the INSERT is a
                // trap: the subquery is re-evaluated per row as the insert proceeds, so the offset
                // grows underneath its own statement and the mapping stops being a mapping. Frozen
                // here, `newEventId = groupId + offset` holds for every row, which is what lets the
                // reparenting below be plain arithmetic instead of a temporary table.
                val offset = db.query("SELECT IFNULL(MAX(eventId), 0) FROM events").use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO events (
                        eventId, name, notes, createdAt, groupId, parentId, type,
                        excludedFromParentAnalysis,
                        coverPath, coverCropLeft, coverCropTop, coverCropRight, coverCropBottom,
                        coverBlur
                    )
                    SELECT
                        groupId + $offset, name, notes, createdAt, NULL,
                        CASE WHEN parentGroupId IS NULL THEN NULL ELSE parentGroupId + $offset END,
                        'Collection', 0,
                        coverPath, coverCropLeft, coverCropTop, coverCropRight, coverCropBottom,
                        coverBlur
                    FROM event_groups
                    """.trimIndent()
                )

                // Every event that was in a collection now sits under the event that collection
                // became. Restricted to rows that had no parent: an event nested in 23 was put
                // there deliberately and by something further down the tree, and its collection
                // membership is the older, coarser statement of where it lives.
                db.execSQL(
                    """
                    UPDATE events
                    SET parentId = groupId + $offset
                    WHERE groupId IS NOT NULL
                      AND parentId IS NULL
                      AND groupId IN (SELECT groupId FROM event_groups)
                    """.trimIndent()
                )

                // Tags follow their collection. Without this the fold would quietly strip the
                // labelling off every container in the library, which is the failure the backup
                // format just had to be rescued from.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO event_tag_cross_ref (eventId, tagId)
                    SELECT groupId + $offset, tagId FROM event_group_tag_cross_ref
                    """.trimIndent()
                )

                val folded = db.query("SELECT COUNT(*) FROM event_groups").use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }
                android.util.Log.i(
                    TAG,
                    "MIGRATION_23_24: folded $folded collections into the event tree " +
                        "(id offset $offset); event_groups kept as a safety net"
                )
            }
        }

        /**
         * Collections, as arbitrary sets this time.
         *
         * Different from what 23→24 folded away, and the difference is the point. Those were
         * *tiers* — a second container type being used for hierarchy — so they became events,
         * which is what the tree is for. This is a set: it holds events and recordings by
         * reference, many-to-many, with no window, no parent and no claim on where anything lives.
         * "Festivals" holds two festivals months apart while both stay put on the timeline.
         *
         * The `type = 'Collection'` marker is cleared from the folded events. It was scaffolding to
         * keep them listed separately while the old screens caught up, and leaving it would put two
         * meanings of one word in front of the same user — which is the confusion this whole rework
         * exists to remove. Their names and their nesting carry everything that mattered; the label
         * carried nothing.
         *
         * No set is created for them. They were containers and they still are, one rung of the
         * tree each; turning them into sets would throw away the hierarchy 23→24 just rebuilt.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (" +
                        "`collectionId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`notes` TEXT NOT NULL DEFAULT '', " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`coverPath` TEXT DEFAULT NULL, " +
                        "`coverCropLeft` REAL DEFAULT NULL, " +
                        "`coverCropTop` REAL DEFAULT NULL, " +
                        "`coverCropRight` REAL DEFAULT NULL, " +
                        "`coverCropBottom` REAL DEFAULT NULL, " +
                        "`coverBlur` REAL DEFAULT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_events` (" +
                        "`collectionId` INTEGER NOT NULL, `eventId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`collectionId`, `eventId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_collection_events_eventId` " +
                        "ON `collection_events` (`eventId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_records` (" +
                        "`collectionId` INTEGER NOT NULL, `recordId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`collectionId`, `recordId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_collection_records_recordId` " +
                        "ON `collection_records` (`recordId`)"
                )

                db.execSQL("UPDATE events SET type = NULL WHERE type = 'Collection'")

                android.util.Log.i(TAG, "MIGRATION_24_25: collections are sets; tier marker cleared")
            }
        }

        /**
         * Venues, as a registry, and the clock each one keeps.
         *
         * A location is the same kind of thing as a person or a watch: made once, pointed at by
         * many events, renamed in one place. That is what makes comparing across venues a
         * comparison of identities rather than of two strings matching, and it is why the Gorge
         * spelled three ways is not three venues.
         *
         * The zone is stored on the location and **chosen rather than derived**. The only reason to
         * work one out from coordinates is not knowing it, and someone naming a venue knows what
         * time it is there — so this needs no boundary dataset, no third-party dependency, and no
         * network. Coordinates are optional and informational; nothing depends on them.
         *
         * References are nullable and nothing is backfilled. A zone could be guessed for existing
         * recordings from the device default, and it would be wrong for every one made away from
         * home — which is exactly the set this feature exists for. Null means "nobody has said",
         * and the app falls back to the reader's zone as it always did.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `locations` (" +
                        "`locationId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`timeZoneId` TEXT DEFAULT NULL, " +
                        "`latitude` REAL DEFAULT NULL, " +
                        "`longitude` REAL DEFAULT NULL, " +
                        "`photoPath` TEXT DEFAULT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                // Not foreign keys, matching every other reference in this model: deleting a venue
                // must leave its events alone rather than take the night with it.
                db.execSQL("ALTER TABLE `events` ADD COLUMN `locationId` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `bpm_records` ADD COLUMN `locationId` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `bpm_records` ADD COLUMN `timeZoneId` TEXT DEFAULT NULL")
                android.util.Log.i(TAG, "MIGRATION_25_26: venues, and the clock each one keeps")
            }
        }

        /**
         * Saved views: a filter someone kept.
         *
         * The filter is stored as text rather than as a column per dimension. The dimensions change
         * — location arrived one sprint after the rest — and a table that grows a column each time
         * is a migration each time. Nothing queries a view in SQL; they are read as a list and
         * applied in memory.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_views` (" +
                        "`viewId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`filterJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`isPinned` INTEGER NOT NULL DEFAULT 1)"
                )
                android.util.Log.i(TAG, "MIGRATION_26_27: saved views")
            }
        }

        /**
         * Collections, saved views and saved analyses become one thing.
         *
         * They were three tables holding the same idea — a named set of recordings — differing only
         * in how membership was decided: enumerated, computed, or frozen. That is one property of a
         * selection, not three kinds of object, and keeping them apart had already produced two
         * live defects. See §8 of the product doc.
         *
         * A saved view folds in as a collection with a rule and no members. A saved analysis folds
         * in as a collection with `frozenAt` set, its snapshot rows re-keyed from `analysisId` to
         * `collectionId`.
         *
         * **The re-key is done in Kotlin, row by row.** Both tables autoincrement from 1, so their
         * ids collide and no SQL join can tell which new collection a snapshot row belongs to. The
         * arithmetic alternative — assume the nth insert landed at `max(id) + n` — is true right up
         * until it is not, and it fails silently by attaching someone's numbers to the wrong set.
         *
         * Also drops `event_groups` and `event_group_tag_cross_ref`, which the 23→24 fold left with
         * no readers, and the now-empty `saved_views` and `saved_analyses`.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collections ADD COLUMN filterJson TEXT DEFAULT NULL")
                db.execSQL(
                    "ALTER TABLE collections ADD COLUMN excludedRecordJson TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL("ALTER TABLE collections ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE collections ADD COLUMN frozenAt INTEGER DEFAULT NULL")

                // Saved views: a rule, no members, pinned as they were.
                db.execSQL(
                    "INSERT INTO collections (name, notes, createdAt, filterJson, isPinned) " +
                        "SELECT name, '', createdAt, filterJson, isPinned FROM saved_views"
                )

                // saved_analysis_records is rebuilt rather than altered: SQLite cannot change a
                // foreign key in place, and the column is being renamed as well as re-pointed.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_analysis_records_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`collectionId` INTEGER NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`date` INTEGER NOT NULL, " +
                        "`minBpm` REAL, `avgBpm` REAL, `maxBpm` REAL, " +
                        "`activeDurationMs` INTEGER NOT NULL, " +
                        "`tagsEncoded` TEXT NOT NULL DEFAULT '', " +
                        "`wearerName` TEXT NOT NULL DEFAULT '', " +
                        "`watchName` TEXT NOT NULL DEFAULT '', " +
                        "`personId` INTEGER DEFAULT NULL, " +
                        "`personColorArgb` INTEGER DEFAULT NULL, " +
                        "`eventId` INTEGER DEFAULT NULL, " +
                        "`eventName` TEXT NOT NULL DEFAULT '', " +
                        "`zonesEncoded` TEXT NOT NULL DEFAULT '', " +
                        "FOREIGN KEY(`collectionId`) REFERENCES `collections`(`collectionId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )

                // One collection per saved analysis, carrying its own id forward so the snapshot
                // rows can follow it.
                //
                // The two kinds fold differently, because they were never the same thing:
                //
                // - A **group** analysis was a snapshot. It becomes a frozen collection with no
                //   members, which is exactly what it was.
                // - A **same-time** analysis was not frozen at all. It stored *which* recordings
                //   and re-read them from the library on open — so it becomes a live collection
                //   whose members are those recordings. That is a hand-made set, which is what it
                //   always was underneath.
                //
                // This replaces `convertConcurrentAnalysesToEvents`, which turned them into events
                // and then deleted them. Its own reasoning said that rewriting `bpm_records.eventId`
                // on a correlation the schema does not express was too dangerous for a migration,
                // and it was right — so this does not do that. Nothing is deleted and nothing is
                // re-filed; a set that would rather be an event can be promoted by hand.
                db.query(
                    "SELECT analysisId, name, createdAt, filterDescription, kind FROM saved_analyses"
                )
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            val analysisId = cursor.getLong(0)
                            val name = cursor.getString(1) ?: ""
                            val createdAt = cursor.getLong(2)
                            val note = cursor.getString(3) ?: ""
                            val wasConcurrent = cursor.getString(4) == "CONCURRENT"

                            db.execSQL(
                                "INSERT INTO collections (name, notes, createdAt, frozenAt) " +
                                    "VALUES (?, ?, ?, ?)",
                                arrayOf(name, note, createdAt, createdAt.takeUnless { wasConcurrent })
                            )

                            // Read back rather than assumed. last_insert_rowid() is the only thing
                            // that actually knows where the row landed.
                            val collectionId = db.query("SELECT last_insert_rowid()").use {
                                if (it.moveToFirst()) it.getLong(0) else -1L
                            }
                            if (collectionId <= 0) continue

                            if (wasConcurrent) {
                                // Members, not a snapshot. Only recordings still in the library: a
                                // membership row pointing at a deleted one refers to nothing. Its
                                // captured numbers are deliberately dropped — they were never
                                // authoritative, since the screen recomputed them from the library
                                // every time it opened.
                                db.execSQL(
                                    "INSERT OR IGNORE INTO collection_records " +
                                        "(collectionId, recordId) " +
                                        "SELECT ?, recordId FROM saved_analysis_records " +
                                        "WHERE analysisId = ? " +
                                        "  AND recordId IN (SELECT recordId FROM bpm_records)",
                                    arrayOf<Any>(collectionId, analysisId)
                                )
                            } else {
                                db.execSQL(
                                    "INSERT INTO saved_analysis_records_new (" +
                                        "collectionId, recordId, title, date, minBpm, avgBpm, " +
                                        "maxBpm, activeDurationMs, tagsEncoded, wearerName, " +
                                        "watchName, personId, personColorArgb, eventId, " +
                                        "eventName, zonesEncoded) " +
                                        "SELECT ?, recordId, title, date, minBpm, avgBpm, maxBpm, " +
                                        "activeDurationMs, tagsEncoded, wearerName, watchName, " +
                                        "personId, personColorArgb, eventId, eventName, " +
                                        "zonesEncoded " +
                                        "FROM saved_analysis_records WHERE analysisId = ?",
                                    arrayOf<Any>(collectionId, analysisId)
                                )
                            }
                        }
                    }

                db.execSQL("DROP TABLE saved_analysis_records")
                db.execSQL("ALTER TABLE saved_analysis_records_new RENAME TO saved_analysis_records")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_saved_analysis_records_collectionId` " +
                        "ON `saved_analysis_records` (`collectionId`)"
                )

                db.execSQL("DROP TABLE IF EXISTS saved_views")
                db.execSQL("DROP TABLE IF EXISTS saved_analyses")

                // Left with no readers by the 23→24 fold. Dropped now rather than left to look like
                // something still in use.
                db.execSQL("DROP TABLE IF EXISTS event_group_tag_cross_ref")
                db.execSQL("DROP TABLE IF EXISTS event_groups")

                android.util.Log.i(TAG, "MIGRATION_27_28: selections folded into collections")
            }
        }

        /**
         * The two derived figures a summary needs, moved out of the readings.
         *
         * `bpm_records` already stored min, avg and max. Active duration and the zone split were
         * recomputed from the readings every time, which is why the library stream loaded every
         * reading in the library and rebuilt them on every write — see §9 of the product doc.
         *
         * **Adds the columns and nothing else.** Existing rows are left null and empty, and filled
         * by [LibraryRepository.backfillDerivedFigures] on the next launch, in Kotlin, against the
         * same functions the app has always used. Computing them here would mean a second
         * implementation of the gap rule in SQL, and two definitions of one number is the failure
         * this initiative exists to unwind. It also keeps a pass over every reading in the library
         * out of a migration, where failing means an app that will not open.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bpm_records ADD COLUMN activeDurationMs INTEGER DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE bpm_records ADD COLUMN zonesEncoded TEXT NOT NULL DEFAULT ''"
                )
                android.util.Log.i(TAG, "MIGRATION_28_29: derived figures move onto the record")
            }
        }

        /**
         * How far to darken a cover.
         *
         * The sibling of `coverBlur`, added for the same reason and solving the other half of it.
         * Blur is for a cover made of type; this is for one that is simply too bright — a
         * white-sky festival shot, a flash photo — where the writing over it cannot hold whatever
         * is done to the writing. Outlining the text was tried and looked like a sticker; the
         * picture is what is too bright, so the picture is what gives.
         *
         * `REAL DEFAULT NULL`, matching the entities and [MIGRATION_21_22]. Null is none, which is
         * what every existing cover has and what most should keep.
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("events", "collections", "bpm_records").forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `coverDim` REAL DEFAULT NULL")
                }
                android.util.Log.i(TAG, "MIGRATION_29_30: a cover can be darkened")
            }
        }

        /**
         * Whether opening the database will run a migration.
         *
         * Read straight off the database file rather than through Room, so this can be answered
         * before anything is opened. If it cannot be read the answer is "yes": an unreadable file
         * is the case where a backup is worth the most.
         */
        /**
         * The version stored in the database file, or null when it cannot be read.
         *
         * Read off the file rather than through Room, because the case this exists for is Room
         * refusing to open it. A file newer than the code — the app downgraded, an older build
         * installed over a newer one — throws "a migration from 30 to 29 was required but not
         * found", which is accurate and says nothing anyone can act on.
         */
        fun fileVersion(context: Context): Int? {
            val dbFile = context.getDatabasePath(DB_NAME)?.takeIf { it.exists() } ?: return null
            return try {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { it.version }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not read the database version", e)
                null
            }
        }

        /** The version this build of the app knows how to open. See [LIBRARY_DB_VERSION]. */
        const val EXPECTED_VERSION = LIBRARY_DB_VERSION

        private fun migrationPending(dbFile: java.io.File): Boolean = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { it.version < CURRENT_VERSION }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not read the database version; assuming a backup is wanted", e)
            true
        }

        /** Drops all but the [keep] most recent backups, with their WAL and SHM companions. */
        private fun pruneBackups(backupDir: java.io.File, keep: Int) {
            val backups = backupDir
                .listFiles { f -> f.name.startsWith(DB_NAME) && f.name.endsWith(".db") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            backups.drop(keep.coerceAtLeast(0)).forEach { old ->
                old.delete()
                java.io.File(old.path + "-wal").delete()
                java.io.File(old.path + "-shm").delete()
                android.util.Log.d(TAG, "Cleaned old backup: ${old.name}")
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
                    .addMigrations(
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        MIGRATION_27_28,
                        MIGRATION_28_29,
                        MIGRATION_29_30
                    )
                    // NEVER add fallbackToDestructiveMigration() here.
                    // Data loss is unacceptable. If migrations fail, crash loudly.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
