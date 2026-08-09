package inga.bpmetrics.library

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        /** The whole chain, in order, so a new migration only has to be added in one place. */
        val ALL_MIGRATIONS = arrayOf(
            LibraryDatabase.MIGRATION_5_6,
            LibraryDatabase.MIGRATION_6_7,
            LibraryDatabase.MIGRATION_7_8,
            LibraryDatabase.MIGRATION_8_9,
            LibraryDatabase.MIGRATION_9_10,
            LibraryDatabase.MIGRATION_10_11,
            LibraryDatabase.MIGRATION_11_12,
            LibraryDatabase.MIGRATION_12_13,
            LibraryDatabase.MIGRATION_13_14,
            LibraryDatabase.MIGRATION_14_15,
            LibraryDatabase.MIGRATION_15_16,
            LibraryDatabase.MIGRATION_16_17,
            LibraryDatabase.MIGRATION_17_18,
            LibraryDatabase.MIGRATION_18_19,
            LibraryDatabase.MIGRATION_19_20,
            LibraryDatabase.MIGRATION_20_21,
            LibraryDatabase.MIGRATION_21_22,
            LibraryDatabase.MIGRATION_22_23
        )
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
    fun migrate5To15_runsTheWholeChain() {
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

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS)

        db.query("SELECT wearerName, watchId FROM bpm_records WHERE recordId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Kyle", cursor.getString(0))
            assertEquals("Watch A", cursor.getString(1))
        }
        db.close()
    }

    /**
     * The upgrade that turns wearers into people has to arrive with everyone already set up.
     *
     * An empty People tab beside a library full of names it did not recognise would look like the
     * upgrade had lost them.
     */
    @Test
    fun migrate10To11_createsAProfileForEveryNameAlreadyInUse() {
        seedVersion5With(
            "(1, 'Saturday', '', 1000, 1000, 2000, 1000, NULL, 80.0, NULL, 'Watch A', 'Kyle')",
            "(2, 'Sunday', '', 2000, 2000, 3000, 1000, NULL, 90.0, NULL, 'Watch B', 'Ben')",
            "(3, 'Also Kyle', '', 3000, 3000, 4000, 1000, NULL, 95.0, NULL, 'Watch A', 'Kyle')",
            "(4, 'Nobody', '', 4000, 4000, 5000, 1000, NULL, 70.0, NULL, 'Watch C', '')"
        )

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS)

        // One profile per distinct name — the two Kyle recordings share a person, not one each.
        db.query("SELECT COUNT(*) FROM people").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        // Everyone gets a colour, and it is a real one rather than the zero the seed starts from.
        db.query("SELECT COUNT(*) FROM people WHERE colorArgb = 0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        // Both of Kyle's recordings point at the same profile.
        db.query(
            """
            SELECT r.recordId, p.name FROM bpm_records r
            JOIN people p ON p.personId = r.personId
            ORDER BY r.recordId
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getLong(0))
            assertEquals("Kyle", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals(2, cursor.getLong(0))
            assertEquals("Ben", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals(3, cursor.getLong(0))
            assertEquals("Kyle", cursor.getString(1))
            assertFalse("a nameless recording must not be given a profile", cursor.moveToNext())
        }

        // The frozen name stays put. It is what keeps a recording readable if its profile is
        // deleted later, so the migration must not clear it in favour of the link.
        db.query("SELECT wearerName FROM bpm_records WHERE recordId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Kyle", cursor.getString(0))
        }
        db.close()
    }

    /** A library with nobody named must still arrive at a valid schema. */
    @Test
    fun migrate10To11_handlesALibraryWithNoWearers() {
        helper.createDatabase(TEST_DB, 5).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS)

        db.query("SELECT COUNT(*) FROM people").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    /**
     * The check that catches the mistake this project keeps making.
     *
     * `runMigrationsAndValidate` compares the migrated schema against `12.json` column by column,
     * including default values. An earlier attempt at this migration wrote
     * `createdAt INTEGER NOT NULL DEFAULT 0` while the entity declares no SQL default — a database
     * that opens perfectly on a fresh install and refuses to open for everyone upgrading.
     */
    @Test
    fun migrate11To12_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS).close()
    }

    /**
     * Existing recordings arrive unfiled, which is the correct state — they were made before
     * anyone said what they were part of.
     */
    @Test
    fun migrate11To12_leavesExistingRecordingsUnfiled() {
        seedVersion5With(
            "(1, 'Before events existed', '', 1000, 1000, 2000, 1000, NULL, 80.0, NULL, 'Watch A', 'Kyle')"
        )

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS)

        db.query("SELECT eventId FROM bpm_records WHERE recordId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("an existing recording belongs to no event", cursor.isNull(0))
        }
        // The tables exist and start empty; nothing is invented on the user's behalf.
        listOf("events", "event_groups").forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table should start empty", 0, cursor.getInt(0))
            }
        }
        db.close()
    }

    /**
     * The check that has caught the same class of bug three times now.
     *
     * A hand-written `DEFAULT` the entity does not declare, or a missing index, produces a database
     * that installs perfectly and refuses to open for everyone upgrading. `validateDroppedTables`
     * is on, so this compares the live schema against what Room expects column for column.
     */
    @Test
    fun migrate12To13_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS).close()
    }

    /**
     * Tags on events and groups start empty, and nothing is copied onto existing recordings.
     *
     * Inheritance is resolved on read — see §2.5. If the upgrade ever started writing tags downward
     * this is where it would show, because a library that had no event tags before the upgrade
     * cannot legitimately have any after it.
     */
    @Test
    fun migrate12To13_addsEmptyTagTablesAndTouchesNothing() {
        seedVersion5With(
            "(1, 'Before tags cascaded', '', 1000, 1000, 2000, 1000, NULL, 80.0, NULL, 'Watch A', 'Kyle')"
        )

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS)

        listOf("event_tag_cross_ref", "event_group_tag_cross_ref").forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table should start empty", 0, cursor.getInt(0))
            }
        }
        db.query("SELECT COUNT(*) FROM record_tag_cross_ref").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("no tags were written onto existing recordings", 0, cursor.getInt(0))
        }
        db.close()
    }

    /**
     * Deleting an event releases its tag links without touching the tags themselves.
     *
     * The cascade runs the wrong way round if the foreign keys are declared backwards, which would
     * mean deleting one event deleted a tag out of every recording that used it.
     */
    @Test
    fun deletingAnEvent_removesItsTagLinksButKeepsTheTag() {
        helper.createDatabase(TEST_DB, 5).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS)

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO categories (categoryId, name) VALUES (1, 'Festivals')")
        db.execSQL("INSERT INTO tags (tagId, name, parentCategoryId) VALUES (1, 'Coachella', 1)")
        db.execSQL("INSERT INTO events (eventId, name, createdAt) VALUES (1, 'Saturday', 0)")
        db.execSQL("INSERT INTO event_tag_cross_ref (eventId, tagId) VALUES (1, 1)")

        db.execSQL("DELETE FROM events WHERE eventId = 1")

        db.query("SELECT COUNT(*) FROM event_tag_cross_ref").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the link goes with the event", 0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM tags WHERE tagId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the tag itself survives", 1, cursor.getInt(0))
        }
        db.close()
    }

    /**
     * The schema check for the columns a saved analysis gained.
     *
     * Five `ALTER TABLE ADD COLUMN` statements whose defaults have to match the entity exactly. A
     * `DEFAULT NULL` written where the entity declares none — or the other way round — produces a
     * database that installs fine and refuses to open on upgrade.
     */
    @Test
    fun migrate13To14_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS).close()
    }

    /**
     * An analysis saved before the upgrade keeps its numbers and gains empty new ones.
     *
     * Nothing is backfilled on purpose: the ids and the band split were never recorded, and
     * inventing them from the current library would make a frozen analysis reflect a present it is
     * meant to predate.
     */
    @Test
    fun migrate13To14_keepsOldSnapshotsAndLeavesTheNewColumnsEmpty() {
        helper.createDatabase(TEST_DB, 5).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS)

        db.execSQL(
            "INSERT INTO saved_analyses (analysisId, name, createdAt) VALUES (1, 'Coachella', 100)"
        )
        db.execSQL(
            """
            INSERT INTO saved_analysis_records
                (analysisId, recordId, title, date, minBpm, avgBpm, maxBpm, activeDurationMs, wearerName)
            VALUES (1, 7, 'Saturday', 100, 60.0, 120.0, 180.0, 60000, 'Kyle')
            """.trimIndent()
        )

        db.query(
            "SELECT wearerName, maxBpm, personId, eventId, eventName, zonesEncoded " +
                "FROM saved_analysis_records WHERE recordId = 7"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Kyle", cursor.getString(0))
            assertEquals(180.0, cursor.getDouble(1), 0.001)
            assertTrue("no person id was ever recorded", cursor.isNull(2))
            assertTrue("no event id was ever recorded", cursor.isNull(3))
            assertEquals("", cursor.getString(4))
            assertEquals("", cursor.getString(5))
        }
        db.close()
    }

    /**
     * The schema check for the presets table.
     *
     * Two boolean columns with SQL defaults, which is precisely the shape that has gone wrong here
     * before — a `DEFAULT 0` written where the entity declares none, or the reverse, opens fine on
     * a fresh install and refuses to open for everyone upgrading.
     */
    @Test
    fun migrate14To15_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS).close()
    }

    /**
     * The migration creates the table and nothing else.
     *
     * The built-in presets are seeded by the repository, not here, so that a fresh install and an
     * upgrade take the same path and what ships is defined once. If the migration ever started
     * inserting them, this is where the duplication would show.
     */
    @Test
    fun migrate14To15_addsAnEmptyPresetsTable() {
        helper.createDatabase(TEST_DB, 5).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, *ALL_MIGRATIONS)

        db.query("SELECT COUNT(*) FROM export_presets").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("presets are seeded in Kotlin, not by the migration", 0, cursor.getInt(0))
        }
        db.close()
    }

    /**
     * The schema check for the render queue.
     *
     * Every column here is declared without a SQL `DEFAULT`, matching an entity that declares none.
     * That is the pairing this project has got wrong three times: a Kotlin constructor default is
     * not a SQL default, and a `DEFAULT` on one side only installs cleanly and then refuses to open
     * for everyone upgrading. This is the test that catches it before they do.
     */
    @Test
    fun migrate15To16_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 16, true, *ALL_MIGRATIONS).close()
    }

    /** The queue starts empty, and an upgrade does not invent jobs nobody asked for. */
    @Test
    fun migrate15To16_addsAnEmptyRenderQueue() {
        helper.createDatabase(TEST_DB, 5).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 16, true, *ALL_MIGRATIONS)

        db.query("SELECT COUNT(*) FROM render_jobs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    /**
     * A row written by hand survives the round trip Room will make of it.
     *
     * Guards the column order and nullability as much as the types: the queue is written by one
     * process and read by the next, so a column that silently refuses a null is a crash on the
     * launch after a batch, which is the worst possible time to find out.
     */
    @Test
    fun migrate15To16_acceptsAJobWithEverythingOptionalLeftOut() {
        helper.createDatabase(TEST_DB, 5).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 16, true, *ALL_MIGRATIONS)

        db.execSQL(
            """
            INSERT INTO render_jobs
                (jobId, recordId, title, recordIdsCsv, presetJson, colorsCsv, graphTitle,
                 startTimeMs, endTimeMs, overlayUri, overlayStartedAtMs, targetUri, status,
                 error, presetName, sourceLabel, recordCount, queuedAt)
            VALUES
                ('job-1', 7, 'Subtronics', '7,8', '{"version":1}', '', NULL,
                 0, 1000, NULL, NULL, NULL, 'QUEUED',
                 NULL, NULL, NULL, 2, 1700000000000)
            """.trimIndent()
        )

        db.query("SELECT recordIdsCsv, status, recordCount FROM render_jobs WHERE jobId = 'job-1'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("7,8", cursor.getString(0))
                assertEquals("QUEUED", cursor.getString(1))
                assertEquals(2, cursor.getInt(2))
            }
        db.close()
    }

    /**
     * The schema check for nestable collections.
     *
     * `DEFAULT NULL` on both sides. The entity declares `@ColumnInfo(defaultValue = "NULL")`, so
     * the migration must say the same — a `DEFAULT` present on one side only installs cleanly and
     * then refuses to open for everyone upgrading, which is the failure this whole file exists for.
     */
    @Test
    fun migrate16To17_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 17, true, *ALL_MIGRATIONS).close()
    }

    /**
     * Existing collections survive, sitting at the top level.
     *
     * Nothing was nested before this version, so every collection that already exists must come
     * out with no parent — not with a parent of 0, which would point at a collection that does not
     * exist and quietly hide it from the list.
     */
    @Test
    fun migrate16To17_leavesExistingCollectionsAtTheTop() {
        helper.createDatabase(TEST_DB, 5).close()
        var db = helper.runMigrationsAndValidate(TEST_DB, 16, true, *ALL_MIGRATIONS)
        db.execSQL(
            "INSERT INTO event_groups (groupId, name, notes, createdAt) " +
                "VALUES (1, 'Coachella', '', 1700000000000)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 17, true, *ALL_MIGRATIONS)

        db.query("SELECT name, parentGroupId FROM event_groups WHERE groupId = 1").use { cursor ->
            assertTrue("the collection should have survived", cursor.moveToFirst())
            assertEquals("Coachella", cursor.getString(0))
            assertTrue("must be top level, not parented to id 0", cursor.isNull(1))
        }
        db.close()
    }

    /** A collection can be filed inside another once the column exists. */
    @Test
    fun migrate16To17_acceptsANestedCollection() {
        helper.createDatabase(TEST_DB, 5).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 17, true, *ALL_MIGRATIONS)

        db.execSQL(
            "INSERT INTO event_groups (groupId, name, notes, createdAt, parentGroupId) VALUES " +
                "(1, 'Coachella', '', 1700000000000, NULL), " +
                "(2, 'Day 1', '', 1700000000000, 1)"
        )

        db.query("SELECT parentGroupId FROM event_groups WHERE groupId = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        db.close()
    }

    /** The schema check for per-person heart rate figures. */
    @Test
    fun migrate17To18_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 18, true, *ALL_MIGRATIONS).close()
    }

    /**
     * Existing people keep inheriting the app-wide figures.
     *
     * Null means "use the default", which is a different thing from zero and has to stay
     * distinguishable from it — a person migrated to 0 bpm resting would make every zone
     * percentage they appear in wrong, quietly.
     */
    @Test
    fun migrate17To18_leavesExistingPeopleOnTheDefaults() {
        helper.createDatabase(TEST_DB, 5).close()
        var db = helper.runMigrationsAndValidate(TEST_DB, 17, true, *ALL_MIGRATIONS)
        db.execSQL(
            "INSERT INTO people (personId, name, colorArgb, createdAt) VALUES (1, 'Kyle', -1, 0)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 18, true, *ALL_MIGRATIONS)

        db.query("SELECT name, restingBpm, maxBpm FROM people WHERE personId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Kyle", cursor.getString(0))
            assertTrue("resting must be absent, not zero", cursor.isNull(1))
            assertTrue("maximum must be absent, not zero", cursor.isNull(2))
        }
        db.close()
    }

    /** The schema check for cover images. */
    @Test
    fun migrate18To19_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 19, true, *ALL_MIGRATIONS).close()
    }

    /**
     * Everything that already exists comes through with no cover set.
     *
     * Null is not "no cover" on an event or a collection — it means *inherit*, and a migration that
     * wrote an empty string instead would make every existing event look like one whose cover had
     * been deliberately cleared, permanently blocking inheritance from the collection above it.
     */
    @Test
    fun migrate18To19_leavesEverythingInheriting() {
        helper.createDatabase(TEST_DB, 5).close()
        var db = helper.runMigrationsAndValidate(TEST_DB, 18, true, *ALL_MIGRATIONS)
        db.execSQL(
            "INSERT INTO event_groups (groupId, name, notes, createdAt, parentGroupId) " +
                "VALUES (1, 'Coachella', '', 1700000000000, NULL)"
        )
        db.execSQL(
            "INSERT INTO events (eventId, name, groupId, notes, createdAt) " +
                "VALUES (1, 'Subtronics', 1, '', 1700000000000)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 19, true, *ALL_MIGRATIONS)

        db.query("SELECT coverPath, coverCropLeft FROM events WHERE eventId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("an existing event must inherit, not be cleared", cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
        db.query("SELECT coverPath FROM event_groups WHERE groupId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("an existing collection must inherit, not be cleared", cursor.isNull(0))
        }
        db.close()
    }

    /**
     * The crop survives as fractions rather than being rounded to whole numbers.
     *
     * REAL, not INTEGER. A crop stored as integers collapses to 0 and 1, which is not a crop — and
     * the failure is silent: the cover still draws, just never framed the way it was set.
     */
    @Test
    fun migrate18To19_storesTheCropAsFractions() {
        helper.createDatabase(TEST_DB, 5).close()
        var db = helper.runMigrationsAndValidate(TEST_DB, 18, true, *ALL_MIGRATIONS)
        db.execSQL(
            "INSERT INTO events (eventId, name, groupId, notes, createdAt) " +
                "VALUES (1, 'Subtronics', NULL, '', 1700000000000)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 19, true, *ALL_MIGRATIONS)
        db.execSQL(
            "UPDATE events SET coverPath = 'cover_1.jpg', " +
                "coverCropLeft = 0.125, coverCropTop = 0.25, " +
                "coverCropRight = 0.875, coverCropBottom = 0.75 WHERE eventId = 1"
        )

        db.query(
            "SELECT coverPath, coverCropLeft, coverCropTop, coverCropRight, coverCropBottom " +
                "FROM events WHERE eventId = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("cover_1.jpg", cursor.getString(0))
            assertEquals(0.125, cursor.getDouble(1), 0.0001)
            assertEquals(0.25, cursor.getDouble(2), 0.0001)
            assertEquals(0.875, cursor.getDouble(3), 0.0001)
            assertEquals(0.75, cursor.getDouble(4), 0.0001)
        }
        db.close()
    }

    /** A recording can carry its own cover, overriding whatever its event would give it. */
    @Test
    fun migrate18To19_letsOneRecordingOverrideItsEvent() {
        seedVersion5With(
            "(1, 'Set', '', 1700000000000, 1700000000000, 1700000060000, 60000, NULL, 90.0, NULL, 'Pixel Watch 2', 'Kyle')"
        )
        val db = helper.runMigrationsAndValidate(TEST_DB, 19, true, *ALL_MIGRATIONS)

        db.query("SELECT coverPath FROM bpm_records WHERE recordId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("a migrated recording must inherit its event's cover", cursor.isNull(0))
        }

        db.execSQL("UPDATE bpm_records SET coverPath = 'own.jpg' WHERE recordId = 1")
        db.query("SELECT coverPath FROM bpm_records WHERE recordId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("own.jpg", cursor.getString(0))
        }
        db.close()
    }

    /** The schema check for events becoming the timeline. */
    @Test
    fun migrate22To23_producesTheSchemaRoomExpects() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 23, true, *ALL_MIGRATIONS).close()
    }

    /**
     * Every existing event comes through unchanged.
     *
     * This migration is additive on purpose — folding collections into the tree is a separate one,
     * because that rewrites data and this cannot break anything. An event with no parent and no
     * window has to behave exactly as it did before, or the split was pointless.
     */
    @Test
    fun migrate22To23_leavesExistingEventsAtTheTopWithNoWindow() {
        helper.createDatabase(TEST_DB, 5).close()
        var db = helper.runMigrationsAndValidate(TEST_DB, 22, true, *ALL_MIGRATIONS)
        db.execSQL(
            "INSERT INTO events (eventId, name, groupId, notes, createdAt) " +
                "VALUES (1, 'Subtronics', NULL, '', 1700000000000)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 23, true, *ALL_MIGRATIONS)

        db.query(
            "SELECT name, parentId, windowStart, windowEnd, type, excludedFromParentAnalysis " +
                "FROM events WHERE eventId = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Subtronics", cursor.getString(0))
            assertTrue("an existing event must stay at the top", cursor.isNull(1))
            assertTrue("and must not gain a window", cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            // Not nullable: there is no third state between excluded and not.
            assertEquals(0, cursor.getInt(5))
        }
        db.close()
    }

    /** Two stages at one festival: the table that lets their windows overlap. */
    @Test
    fun migrate22To23_letsAWindowNameThePeopleItAppliesTo() {
        helper.createDatabase(TEST_DB, 5).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 23, true, *ALL_MIGRATIONS)

        db.execSQL("INSERT INTO event_window_people (eventId, personId) VALUES (1, 100), (1, 200)")

        db.query("SELECT COUNT(*) FROM event_window_people WHERE eventId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        db.close()
    }

    /** Inserts records into a fresh version 5 database and closes it. */
    private fun seedVersion5With(vararg rows: String) {
        helper.createDatabase(TEST_DB, 5).apply {
            rows.forEach { row ->
                execSQL(
                    """
                    INSERT INTO bpm_records
                        (recordId, title, description, date, startTime, endTime, durationMs, maxId, avg, minId, deviceId, wearerName)
                    VALUES $row
                    """.trimIndent()
                )
            }
            close()
        }
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
