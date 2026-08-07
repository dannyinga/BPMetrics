package inga.bpmetrics.ui

import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.ui.library.LibraryViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

import kotlinx.coroutines.flow.first

/**
 * Unit test for [inga.bpmetrics.ui.library.LibraryViewModel].
 */
class LibraryViewModelTest {

    private val repository = mockk<LibraryRepository>(relaxed = true)
    private val recordsFlow = MutableStateFlow<List<BpmRecord>>(emptyList())

    @Before
    fun setup() {
        every { repository.records } returns recordsFlow
    }

    @Test
    fun `uiState updates when repository emits records`() = runTest {
        val viewModel = LibraryViewModel(repository)
        
        val testRecords = listOf(
            BpmRecord(
                metadata = BpmRecordEntity(recordId = 1, title = "Test", date = 0, startTime = 0, endTime = 0, durationMs = 0),
                dataPoints = emptyList(),
                minDataPoint = null,
                maxDataPoint = null
            )
        )
        
        recordsFlow.value = testRecords
        
        val state = viewModel.uiState.first { !it.isLoading }
        assertEquals(testRecords, state.records)
        assertFalse(state.isLoading)
    }
}
