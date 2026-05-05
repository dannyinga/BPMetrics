package inga.bpmetrics.library

import android.content.Context
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.ui.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Date

/**
 * Unit tests for [LibraryRepository], focusing on the heart rate analysis logic.
 */
class LibraryRepositoryTest {

    private val context = mockk<Context>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val database = mockk<LibraryDatabase>(relaxed = true)
    private val recordDao = mockk<BpmRecordDao>(relaxed = true)
    private val tagDao = mockk<TagDao>(relaxed = true)
    
    private lateinit var repository: LibraryRepository

    @Before
    fun setup() {
        // Mock database singleton and DAO access
        io.mockk.mockkObject(LibraryDatabase.Companion)
        every { LibraryDatabase.getInstance(any()) } returns database
        every { database.bpmRecordDao() } returns recordDao
        every { database.tagDao() } returns tagDao
        
        repository = LibraryRepository(context, settingsRepository)
    }

    @Test
    fun `saveWatchRecordToLibrary correctly calculates weighted average`() = runTest {
        // GIVEN: A record with data points at irregular intervals
        // Point 1: 60 BPM for 1000ms
        // Point 2: 80 BPM for 2000ms
        // Total duration: 3000ms
        val dataPoints = listOf(
            BpmDataPoint(timestamp = 0L, bpm = 60.0),
            BpmDataPoint(timestamp = 1000L, bpm = 80.0)
        )
        val watchRecord = BpmWatchRecord(
            date = Date(0L),
            dataPoints = dataPoints,
            startTime = 0L,
            endTime = 3000L,
            durationMs = 3000L
        )

        coEvery { recordDao.insertBpmRecordGetId(any()) } returns 123L
        coEvery { recordDao.insertAllDataPoints(any()) } returns listOf(1L, 2L)
        every { settingsRepository.defaultNamingCategoryId } returns flowOf(null)

        // WHEN: The record is saved
        repository.saveWatchRecordToLibrary(watchRecord)

        // THEN: Verify the weighted average calculation
        // Sum = (60 * 1000) + (80 * 2000) = 60,000 + 160,000 = 220,000
        // Avg = 220,000 / 3000 = 73.333...
        val expectedAvg = (60.0 * 1000 + 80.0 * 2000) / 3000.0
        
        coVerify { 
            recordDao.updateAnalysis(
                id = 123L, 
                minId = 1L, 
                maxId = 2L, 
                avg = withArg { assertEquals(expectedAvg, it, 0.01) }
            ) 
        }
    }

    @Test
    fun `saveWatchRecordToLibrary handles single data point`() = runTest {
        val dataPoints = listOf(BpmDataPoint(timestamp = 0L, bpm = 100.0))
        val watchRecord = BpmWatchRecord(
            date = Date(0L),
            dataPoints = dataPoints,
            startTime = 0L,
            endTime = 1000L,
            durationMs = 1000L
        )

        coEvery { recordDao.insertBpmRecordGetId(any()) } returns 1L
        coEvery { recordDao.insertAllDataPoints(any()) } returns listOf(10L)
        every { settingsRepository.defaultNamingCategoryId } returns flowOf(null)

        repository.saveWatchRecordToLibrary(watchRecord)

        coVerify { 
            recordDao.updateAnalysis(1L, 10L, 10L, 100.0)
        }
    }
}
