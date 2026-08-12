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

        const val DB_NAME = "bpm_watch_db"

        /** The version this build knows how to open. */
        const val EXPECTED_VERSION = 3

        fun getInstance(context: Context): RecordingDB {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecordingDB::class.java,
                    DB_NAME
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // NEVER add fallbackToDestructiveMigration() here.
                // Data loss is unacceptable — and on the watch it is worse than on the phone. This
                // file is the *only* copy of a recording between the moment it is finalised and the
                // moment the phone acknowledges it. Discarding it to get the app to start would
                // throw away the evening it exists to keep.
                .build()
                .also { INSTANCE = it }
            }
        }

        /**
         * Opens the database and touches it, so a failure surfaces here rather than mid-recording.
         *
         * Room's builder is lazy: `getInstance` never throws, because nothing is opened until the
         * first query. That laziness is what made a broken database invisible on the watch. The
         * first query happens inside the repository's coroutine scope, which has an exception
         * handler — so the failure was caught, logged, and *silently swallowed*: the app started
         * normally, showed no pending recordings, and would have gone through the whole motion of
         * recording a set while storing none of it. A wearer would find out at the end of the night.
         *
         * @return null on success, or the reason it could not be opened.
         */
        fun probe(context: Context): DbFailure? {
            return try {
                // A real query, not just `openHelper.readableDatabase`. Room validates the schema
                // and runs migrations on first access, and those are the failures worth catching.
                getInstance(context).openHelper.readableDatabase
                    .query("SELECT COUNT(*) FROM pending_records").use { it.moveToFirst() }
                null
            } catch (t: Throwable) {
                val onDisk = fileVersion(context)
                android.util.Log.e(
                    "RecordingDB",
                    "Could not open the watch database (file version $onDisk, expected $EXPECTED_VERSION)",
                    t
                )
                // Distinguished for the same reason the phone distinguishes them: a file newer than
                // the code is an app that went *backwards*, and the fix is to install the newer
                // build again — the data is fine. Treating that as damage would be how a working
                // set of recordings gets thrown away.
                if (onDisk != null && onDisk > EXPECTED_VERSION) {
                    DbFailure.NewerThanApp(onDisk = onDisk, expected = EXPECTED_VERSION)
                } else {
                    DbFailure.Unreadable(t.message ?: "The recording store could not be opened")
                }
            }
        }

        /**
         * The version recorded in the file, or null if it cannot be read.
         *
         * Read straight off the file with plain SQLite rather than through Room, because the case
         * this exists for is Room refusing to open it.
         */
        fun fileVersion(context: Context): Int? {
            val dbFile = context.getDatabasePath(DB_NAME)?.takeIf { it.exists() } ?: return null
            return try {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { it.version }
            } catch (t: Throwable) {
                android.util.Log.w("RecordingDB", "Could not read the database version", t)
                null
            }
        }
    }
}

/**
 * Why the watch's recording store could not be opened.
 *
 * Two cases, because they want opposite things from the wearer. One is a downgrade and needs the
 * newer build put back; the other is damage and needs the store cleared. Collapsing them into a
 * single "something went wrong" is how somebody clears a perfectly good set of recordings that
 * only needed the right APK.
 */
sealed interface DbFailure {

    /**
     * The file was written by a newer build than this one.
     *
     * **Do not clear it.** The recordings are intact and will open again as soon as the newer app
     * is installed. This is the exact shape that hit the phone — *"a migration from 30 to 29 was
     * required but not found"* — and the message Room gives is accurate and useless.
     */
    data class NewerThanApp(val onDisk: Int, val expected: Int) : DbFailure

    /** Genuinely unreadable: corruption, a truncated file, a disk that gave out mid-write. */
    data class Unreadable(val reason: String) : DbFailure
}
