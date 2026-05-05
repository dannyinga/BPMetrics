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
        
        // Manual setup of the repository internal state to use our in-memory DB
        val repoField = LibraryRepository::class.java.getDeclaredField("database")
        repoField.isAccessible = true
        repoField.set(repository, db)
        
        val recordDaoField = LibraryRepository::class.java.getDeclaredField("recordDao")
        recordDaoField.isAccessible = true
        recordDaoField.set(repository, db.bpmRecordDao())
        
        val tagDaoField = LibraryRepository::class.java.getDeclaredField("tagDao")
        tagDaoField.isAccessible = true
        tagDaoField.set(repository, db.tagDao())
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
