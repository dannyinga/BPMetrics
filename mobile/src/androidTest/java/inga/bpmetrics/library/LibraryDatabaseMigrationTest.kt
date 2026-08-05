package inga.bpmetrics.library

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the library database migrations against the exported schemas.
 *
 * These exist because the same defect has now bitten this project twice: a migration whose SQL
 * disagrees with the entities. Room compares the migrated schema against the expected one and
 * throws, so the database fails to open — but only for users upgrading. A fresh install builds
 * the schema from the entities and works perfectly, which is exactly what hides the bug during
 * development.
 *
 * [MigrationTestHelper.runMigrationsAndValidate] performs that comparison here instead, where a
 * mismatch is a failing test rather than a crash on someone's phone. The most recent example was
 * `firstSeen`/`lastSeen` created with `DEFAULT 0` while the entity declares no SQL default.
 */
@RunWith(AndroidJUnit4::class)
class LibraryDatabaseMigrationTest {

    private companion object {
        const val TEST_DB = "migration-test-db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibraryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * The check that matters: after MIGRATION_5_6 runs, does the database match what Room expects?
     */
    @Test
    fun migrate5To6_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 5).close()

        // Throws if the migrated schema differs from 6.json in any column, type, default,
        // primary key or index.
        helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            LibraryDatabase.MIGRATION_5_6
        ).close()
    }

    /**
     * Existing recordings must survive the migration with their attribution intact.
     */
    @Test
    fun migrate5To6_keepsRecordsAndTheirWearerNames() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO bpm_records
                    (recordId, title, description, date, startTime, endTime, durationMs, maxId, avg, minId, deviceId, wearerName)
                VALUES
                    (1, 'Concert', '', 1700000000000, 1700000000000, 1700000060000, 60000, NULL, 90.0, NULL, 'Pixel Watch 2', 'Kyle')
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, LibraryDatabase.MIGRATION_5_6)

        db.query("SELECT title, wearerName, deviceId, watchId FROM bpm_records WHERE recordId = 1").use { cursor ->
            assertTrue("The record should have survived the migration", cursor.moveToFirst())
            assertEquals("Concert", cursor.getString(0))
            // The wearer is a frozen historical attribution and must not be touched.
            assertEquals("Kyle", cursor.getString(1))
            assertEquals("Pixel Watch 2", cursor.getString(2))
            // Older records have no stable id, so they are keyed on the device they reported.
            assertEquals("Pixel Watch 2", cursor.getString(3))
        }
        db.close()
    }

    /**
     * The registry is seeded from whatever watches the existing records came from, so a user with
     * history does not open an empty Watches screen.
     */
    @Test
    fun migrate5To6_seedsTheRegistryFromExistingRecords() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO bpm_records
                    (recordId, title, description, date, startTime, endTime, durationMs, maxId, avg, minId, deviceId, wearerName)
                VALUES
                    (1, 'First',  '', 1000, 1000, 2000, 1000, NULL, 80.0, NULL, 'Watch A', 'Kyle'),
                    (2, 'Second', '', 5000, 5000, 6000, 1000, NULL, 85.0, NULL, 'Watch A', 'Ben'),
                    (3, 'Third',  '', 9000, 9000, 9500,  500, NULL, 70.0, NULL, 'Watch B', '')
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, LibraryDatabase.MIGRATION_5_6)

        db.query("SELECT watchId, customName, lastKnownModel, firstSeen, lastSeen FROM watches ORDER BY watchId").use { cursor ->
            assertEquals("One entry per distinct device", 2, cursor.count)

            assertTrue(cursor.moveToFirst())
            assertEquals("Watch A", cursor.getString(0))
            // Seeded entries carry no given name: a name is something the user sets, never inferred.
            assertEquals("", cursor.getString(1))
            assertEquals("Watch A", cursor.getString(2))
            // Seen range spans that watch's recordings.
            assertEquals(1000L, cursor.getLong(3))
            assertEquals(5000L, cursor.getLong(4))

            assertTrue(cursor.moveToNext())
            assertEquals("Watch B", cursor.getString(0))
        }

        // Two people wore Watch A on different days, and each recording keeps its own name.
        db.query("SELECT recordId, wearerName FROM bpm_records WHERE watchId = 'Watch A' ORDER BY recordId").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Kyle", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("Ben", cursor.getString(1))
        }
        db.close()
    }

    /**
     * A library with no recordings must migrate just as cleanly as one with history.
     */
    @Test
    fun migrate5To6_handlesAnEmptyLibrary() {
        helper.createDatabase(TEST_DB, 5).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, LibraryDatabase.MIGRATION_5_6)

        db.query("SELECT COUNT(*) FROM watches").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    /**
     * Records with no usable device id are left unlinked rather than collected under a junk entry.
     */
    @Test
    fun migrate5To6_leavesRecordsWithoutADeviceUnlinked() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO bpm_records
                    (recordId, title, description, date, startTime, endTime, durationMs, maxId, avg, minId, deviceId, wearerName)
                VALUES
                    (1, 'Imported', '', 1000, 1000, 2000, 1000, NULL, 75.0, NULL, '', '')
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, LibraryDatabase.MIGRATION_5_6)

        db.query("SELECT watchId FROM bpm_records WHERE recordId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(0))
        }
        db.query("SELECT COUNT(*) FROM watches").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("A blank device id must not create a registry entry", 0, cursor.getInt(0))
        }
        db.close()
    }

    /**
     * The saved-analysis tables must match Room's expectations exactly, down to the index name
     * and the cascade clause on the foreign key.
     */
    @Test
    fun migrate6To7_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 6).close()

        helper.runMigrationsAndValidate(TEST_DB, 7, true, LibraryDatabase.MIGRATION_6_7).close()
    }

    /**
     * Running the whole chain is what a user upgrading from an older install actually experiences.
     */
    @Test
    fun migrate5To10_runsTheWholeChain() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO bpm_records
                    (recordId, title, description, date, startTime, endTime, durationMs, maxId, avg, minId, deviceId, wearerName)
                VALUES
                    (1, 'Old Recording', '', 1000, 1000, 2000, 1000, NULL, 80.0, NULL, 'Watch A', 'Kyle')
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            10,
            true,
            LibraryDatabase.MIGRATION_5_6,
            LibraryDatabase.MIGRATION_6_7,
            LibraryDatabase.MIGRATION_7_8,
            LibraryDatabase.MIGRATION_8_9,
            LibraryDatabase.MIGRATION_9_10
        )

        db.query("SELECT wearerName, watchId FROM bpm_records WHERE recordId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Kyle", cursor.getString(0))
            assertEquals("Watch A", cursor.getString(1))
        }
        db.close()
    }

    /**
     * Deleting a saved analysis must take its captured records with it, or the database
     * accumulates orphans invisibly.
     */
    @Test
    fun savedAnalysisRecords_areRemovedWithTheirAnalysis() {
        helper.createDatabase(TEST_DB, 6).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, LibraryDatabase.MIGRATION_6_7)

        // The cascade is enforced by SQLite only when foreign keys are switched on.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO saved_analyses (analysisId, name, createdAt, filterDescription) VALUES (1, 'Coachella 2026', 1000, '')")
        db.execSQL(
            """
            INSERT INTO saved_analysis_records
                (analysisId, recordId, title, date, minBpm, avgBpm, maxBpm, activeDurationMs, tagsEncoded)
            VALUES
                (1, 10, 'Set One', 1000, 60.0, 90.0, 150.0, 60000, '1:Event:Coachella')
            """.trimIndent()
        )

        db.execSQL("DELETE FROM saved_analyses WHERE analysisId = 1")

        db.query("SELECT COUNT(*) FROM saved_analysis_records").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Captured records should cascade with their analysis", 0, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate7To8_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 7).close()

        helper.runMigrationsAndValidate(TEST_DB, 8, true, LibraryDatabase.MIGRATION_7_8).close()
    }

    /**
     * The old single name meant the wearer — the field was labelled "who is wearing this watch"
     * and was stamped onto records — so it must land on the wearer, not on the device.
     */
    @Test
    fun migrate7To8_movesTheOldNameToTheWearer() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                """
                INSERT INTO watches (watchId, customName, lastKnownModel, lastKnownNodeId, colorArgb, firstSeen, lastSeen)
                VALUES ('uuid-a', 'Kyle', 'Pixel Watch 2', 'node1', NULL, 1000, 5000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, LibraryDatabase.MIGRATION_7_8)

        db.query("SELECT deviceName, currentWearerName, lastKnownModel, lastKnownNodeId, firstSeen, lastSeen FROM watches WHERE watchId = 'uuid-a'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // The watch itself is left unnamed and falls back to its model until the user names it.
            assertEquals("", cursor.getString(0))
            assertEquals("Kyle", cursor.getString(1))
            // Everything else must survive the table being rebuilt.
            assertEquals("Pixel Watch 2", cursor.getString(2))
            assertEquals("node1", cursor.getString(3))
            assertEquals(1000L, cursor.getLong(4))
            assertEquals(5000L, cursor.getLong(5))
        }
        db.close()
    }

    /**
     * Recordings are untouched by the split: their wearer was frozen when they arrived.
     */
    @Test
    fun migrate7To8_leavesRecordedWearersAlone() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                """
                INSERT INTO bpm_records
                    (recordId, title, description, date, startTime, endTime, durationMs, maxId, avg, minId, deviceId, wearerName, watchId)
                VALUES
                    (1, 'Saturday', '', 1000, 1000, 2000, 1000, NULL, 80.0, NULL, 'Pixel Watch 2', 'Kyle', 'uuid-a')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO watches (watchId, customName, lastKnownModel, lastKnownNodeId, colorArgb, firstSeen, lastSeen)
                VALUES ('uuid-a', 'Ben', 'Pixel Watch 2', '', NULL, 1000, 5000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, LibraryDatabase.MIGRATION_7_8)

        // The watch has moved on to Ben; Saturday's recording is still Kyle's.
        db.query("SELECT wearerName FROM bpm_records WHERE recordId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Kyle", cursor.getString(0))
        }
        db.query("SELECT currentWearerName FROM watches WHERE watchId = 'uuid-a'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Ben", cursor.getString(0))
        }
        db.close()
    }

    @Test
    fun migrate8To9_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 8).close()

        helper.runMigrationsAndValidate(TEST_DB, 9, true, LibraryDatabase.MIGRATION_8_9).close()
    }

    /**
     * Analyses saved before the wearer and watch were captured keep their numbers, and simply
     * offer no comparison by them — the information was never recorded.
     */
    @Test
    fun migrate8To9_leavesOlderSavedAnalysesIntact() {
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL("INSERT INTO saved_analyses (analysisId, name, createdAt, filterDescription) VALUES (1, 'Coachella 2026', 1000, '')")
            execSQL(
                """
                INSERT INTO saved_analysis_records
                    (analysisId, recordId, title, date, minBpm, avgBpm, maxBpm, activeDurationMs, tagsEncoded)
                VALUES
                    (1, 10, 'Set One', 1000, 60.0, 90.0, 150.0, 60000, '1:Event:Coachella')
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, LibraryDatabase.MIGRATION_8_9)

        db.query("SELECT title, maxBpm, wearerName, watchName FROM saved_analysis_records WHERE analysisId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Set One", cursor.getString(0))
            assertEquals(150.0, cursor.getDouble(1), 0.001)
            assertEquals("", cursor.getString(2))
            assertEquals("", cursor.getString(3))
        }
        db.close()
    }

    @Test
    fun migrate9To10_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 9).close()

        helper.runMigrationsAndValidate(TEST_DB, 10, true, LibraryDatabase.MIGRATION_9_10).close()
    }

    /**
     * Everything saved before the two kinds existed was a compared-recordings analysis, and must
     * keep opening as one.
     */
    @Test
    fun migrate9To10_treatsOlderAnalysesAsGroupAnalyses() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL("INSERT INTO saved_analyses (analysisId, name, createdAt, filterDescription) VALUES (1, 'Coachella 2026', 1000, '')")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, LibraryDatabase.MIGRATION_9_10)

        db.query("SELECT name, kind, windowStartMs, windowEndMs FROM saved_analyses WHERE analysisId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Coachella 2026", cursor.getString(0))
            assertEquals("GROUP", cursor.getString(1))
            // A group analysis covers no particular stretch of clock.
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
        }
        db.close()
    }

    @Test
    fun migrate9To10_isIdempotent() {
        helper.createDatabase(TEST_DB, 9).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, LibraryDatabase.MIGRATION_9_10)

        LibraryDatabase.MIGRATION_9_10.migrate(db)

        assertNotNull(db)
        db.close()
    }

    @Test
    fun migrate8To9_isIdempotent() {
        helper.createDatabase(TEST_DB, 8).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, LibraryDatabase.MIGRATION_8_9)

        LibraryDatabase.MIGRATION_8_9.migrate(db)

        assertNotNull(db)
        db.close()
    }

    @Test
    fun migrate7To8_isIdempotent() {
        helper.createDatabase(TEST_DB, 7).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, LibraryDatabase.MIGRATION_7_8)

        // Rebuilding a table is the least re-runnable kind of migration, so this matters most here.
        LibraryDatabase.MIGRATION_7_8.migrate(db)

        assertNotNull(db)
        db.close()
    }

    /**
     * Migrations must be safe to re-run: a failed upgrade rolls back and is retried on next
     * launch, so a second pass over an already-migrated table cannot be allowed to throw.
     */
    @Test
    fun migrate6To7_isIdempotent() {
        helper.createDatabase(TEST_DB, 6).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, LibraryDatabase.MIGRATION_6_7)

        LibraryDatabase.MIGRATION_6_7.migrate(db)

        assertNotNull(db)
        db.close()
    }

    /**
     * Migrations must be safe to re-run: a failed upgrade rolls back and is retried on next
     * launch, so a second pass over an already-migrated table cannot be allowed to throw.
     */
    @Test
    fun migrate5To6_isIdempotent() {
        helper.createDatabase(TEST_DB, 5).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, LibraryDatabase.MIGRATION_5_6)

        // Applying the same steps again must not fail on the table or column already existing.
        LibraryDatabase.MIGRATION_5_6.migrate(db)

        assertNotNull(db)
        db.close()
    }
}
