package inga.bpmetrics.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.ui.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Date

/**
 * Instrumented tests for the Library and Data Synchronization logic.
 * 
 * These tests run on a real Android device/emulator and use an in-memory database
 * to verify end-to-end processing of records, tags, and auto-naming.
 */
@RunWith(AndroidJUnit4::class)
class LibraryInstrumentationTest {

    private lateinit var db: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()

        // A relaxed mock returns a Flow that never emits, and `addTagToRecord` calls `.first()` on
        // this one — which throws rather than yielding a default. Stubbed explicitly: no category
        // drives auto-naming in these tests.
        every { settingsRepository.defaultNamingCategoryId } returns flowOf(null)

        // Handed the database rather than having its fields swapped afterwards.
        //
        // The reflection this replaces could never have worked: the repository starts collecting
        // from its DAOs in `init`, so by the time a test overwrote the fields, `records` was
        // already wired to the real on-disk database. Writes went to the in-memory one and reads
        // came from the real one, which is why these tests saw each other's data.
        repository = LibraryRepository(context, settingsRepository, db)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testReceiveRecordAndEditTitle() = runBlocking {
        // 1. Simulate receiving a record from the watch
        val watchRecord = BpmWatchRecord(
            date = Date(System.currentTimeMillis()),
            dataPoints = listOf(BpmDataPoint(0, 75.0), BpmDataPoint(1000, 85.0)),
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 2000,
        )
        
        // Read back by the id the save returns, rather than through `repository.records`.
        //
        // That flow is fed by a background collector, so immediately after a save it may hold the
        // list from before the record was auto-named — or from before it existed at all. Asserting
        // against it is a race the test loses roughly whenever the collector is a moment behind.
        val recordId = repository.saveWatchRecordToLibrary(watchRecord)

        // 2. Verify it's in the library with default name
        val saved = repository.getRecordWithId(recordId)
        assertTrue(saved.metadata.title.contains("Untitled"))

        // 3. Edit the title
        repository.updateRecordTitle(recordId, "Morning Run")
        
        // 4. Verify title change
        val updatedRecord = repository.getRecordWithId(recordId)
        assertEquals("Morning Run", updatedRecord.metadata.title)
    }

    @Test
    fun testAddingAndRemovingTags() = runBlocking {
        // 1. Setup a record — id taken from the save, not from the eventually-consistent flow.
        val recordId = repository.saveWatchRecordToLibrary(
            BpmWatchRecord(Date(0), listOf(BpmDataPoint(0, 60.0)), 0, 1000)
        )

        // 2. Create a Category and Tag
        repository.createCategory("Activity")
        val category = repository.getAllCategories().first()[0]
        repository.createTag("Running", category.categoryId)
        val tag = repository.getTagsByCategory(category.categoryId).first()[0]

        // 3. Add Tag to Record
        repository.addTagToRecord(recordId, tag.tagId)
        
        // 4. Verify tag assignment
        var recordTags = repository.getTagsForRecord(recordId).first()
        assertEquals(1, recordTags.size)
        assertEquals("Running", recordTags[0].name)

        // 5. Remove Tag
        repository.removeTagFromRecord(recordId, tag.tagId)
        recordTags = repository.getTagsForRecord(recordId).first()
        assertEquals(0, recordTags.size)
    }
}
