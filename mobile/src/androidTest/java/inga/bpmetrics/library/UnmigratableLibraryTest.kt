package inga.bpmetrics.library

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A library with no migration path fails when it is *opened*, not when it is built.
 *
 * Which is why the startup guard has to force the open (`LibraryRepository.ensureOpen`). It used
 * to only construct the repository, so a schema-2 library found on an old emulator passed the
 * guard and crashed the app on the first screen, and the Recovery screen never appeared.
 */
@RunWith(AndroidJUnit4::class)
class UnmigratableLibraryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "unmigratable-test-db"

    @Before
    fun writeSchemaTwoFile() {
        context.deleteDatabase(name)
        // A database that says it is version 2. Nothing migrates from 2; the chain starts at 4.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE bpm_records (id INTEGER PRIMARY KEY)")
            db.version = 2
        }
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun buildingSucceedsButOpeningFails() {
        val database = LibraryDatabase.build(context, name)
        try {
            val opened = runCatching { database.openHelper.writableDatabase }
            assertTrue(
                "Opening a schema-2 library should fail, so the guard can catch it",
                opened.isFailure
            )
        } finally {
            database.close()
        }
    }
}
