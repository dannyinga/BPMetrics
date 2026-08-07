package inga.bpmetrics.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.ui.settings.SettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.first
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
        
        // We override the DB access in the repository for the test
        repository = LibraryRepository(context, settingsRepository)
            // Note: In a production test, you'd use a Testing-specific LibraryRepository 
            // that accepts a Database instance via constructor.
        
        // Point every one of the repository's DAOs at the in-memory database.
        //
        // Listed together on purpose. Three of these used to be swapped and the rest left pointing
        // at the real on-disk database, so a save that touched an un-swapped DAO silently wrote
        // somewhere else — invisible until saving a record started consulting the people table and
        // these tests began failing for no apparent reason.
        //
        // Any new DAO on LibraryRepository has to be added here too.
        replaceField("database", db)
        replaceField("recordDao", db.bpmRecordDao())
        replaceField("tagDao", db.tagDao())
        replaceField("watchDao", db.watchDao())
        replaceField("personDao", db.personDao())
        replaceField("savedAnalysisDao", db.savedAnalysisDao())
    }

    /** Overwrites one of the repository's private fields, which is how the test swaps in its own DB. */
    private fun replaceField(name: String, value: Any) {
        LibraryRepository::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(repository, value)
        }
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
        
        repository.saveWatchRecordToLibrary(watchRecord)
        
        // 2. Verify it's in the library with default name
        val records = repository.records.first()
        assertEquals(1, records.size)
        assertTrue(records[0].metadata.title.contains("Untitled"))
        
        val recordId = records[0].metadata.recordId

        // 3. Edit the title
        repository.updateRecordTitle(recordId, "Morning Run")
        
        // 4. Verify title change
        val updatedRecord = repository.getRecordWithId(recordId)
        assertEquals("Morning Run", updatedRecord.metadata.title)
    }

    @Test
    fun testAddingAndRemovingTags() = runBlocking {
        // 1. Setup a record
        repository.saveWatchRecordToLibrary(BpmWatchRecord(Date(0), listOf(BpmDataPoint(0, 60.0)), 0, 1000))
        val recordId = repository.records.first()[0].metadata.recordId

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
