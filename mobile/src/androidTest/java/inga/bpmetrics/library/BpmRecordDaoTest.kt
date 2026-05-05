package inga.bpmetrics.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumented test for [BpmRecordDao].
 *
 * This test verifies the database operations for BPM records and data points
 * using an in-memory database instance.
 */
@RunWith(AndroidJUnit4::class)
class BpmRecordDaoTest {

    private lateinit var db: LibraryDatabase
    private lateinit var dao: BpmRecordDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Using an in-memory database for testing as it's faster and isolated.
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        dao = db.bpmRecordDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    fun insertAndGetRecord() = runBlocking {
        val record = BpmRecordEntity(
            title = "Test Record",
            date = 123456789L,
            startTime = 1000L,
            endTime = 5000L,
            durationMs = 4000L
        )
        val id = dao.insertBpmRecordGetId(record)
        
        val fetchedRecord = dao.getRecord(id)
        assertNotNull(fetchedRecord)
        assertEquals("Test Record", fetchedRecord.metadata.title)
    }

    @Test
    fun batchInsertDataPoints() = runBlocking {
        val recordId = dao.insertBpmRecordGetId(
            BpmRecordEntity(title = "Batch Test", date = 0L, startTime = 0L, endTime = 0L, durationMs = 0L)
        )
        
        val points = listOf(
            BpmDataPointEntity(recordOwnerId = recordId, timestamp = 100L, bpm = 70.0),
            BpmDataPointEntity(recordOwnerId = recordId, timestamp = 200L, bpm = 80.0),
            BpmDataPointEntity(recordOwnerId = recordId, timestamp = 300L, bpm = 90.0)
        )
        
        val ids = dao.insertAllDataPoints(points)
        assertEquals(3, ids.size)
        
        val fetchedPoints = dao.getAllDataPointsForRecord(recordId)
        assertEquals(3, fetchedPoints.size)
        assertEquals(80.0, fetchedPoints[1].bpm, 0.0)
    }

    @Test
    fun updateAnalysisResults() = runBlocking {
        val recordId = dao.insertBpmRecordGetId(
            BpmRecordEntity(title = "Analysis Test", date = 0L, startTime = 0L, endTime = 0L, durationMs = 0L)
        )
        
        dao.updateAnalysis(recordId, minId = 1L, maxId = 10L, avg = 75.5)
        
        val updatedRecord = dao.getRecordEntity(recordId)
        assertEquals(75.5, updatedRecord.avg ?: 0.0, 0.0)
        assertEquals(1L, updatedRecord.minId)
        assertEquals(10L, updatedRecord.maxId)
    }

    @Test
    fun deleteRecordCascade() = runBlocking {
        val recordId = dao.insertBpmRecordGetId(
            BpmRecordEntity(title = "Delete Test", date = 0L, startTime = 0L, endTime = 0L, durationMs = 0L)
        )
        dao.insertBpmDataPoint(BpmDataPointEntity(recordOwnerId = recordId, timestamp = 100L, bpm = 70.0))
        
        dao.deleteRecordById(recordId)
        dao.deleteDataPointsByRecordId(recordId)
        
        val records = dao.getAllRecordEntities()
        val points = dao.getAllDataPointsForRecord(recordId)
        
        assertEquals(0, records.size)
        assertEquals(0, points.size)
    }
}
