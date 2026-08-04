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
            // Seeded entries are unnamed: a name is something the user gives, never inferred.
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
