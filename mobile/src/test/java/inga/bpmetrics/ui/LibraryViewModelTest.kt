package inga.bpmetrics.ui

import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.EffectiveTag
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
    private val effectiveTagsFlow = MutableStateFlow<Map<Long, List<EffectiveTag>>>(emptyMap())

    @Before
    fun setup() {
        every { repository.records } returns recordsFlow
        // Filtering combines records with resolved tags, so a relaxed mock that never emits here
        // leaves the whole list flow waiting for a first value and the test times out rather
        // than failing with anything that says why.
        every { repository.effectiveTags } returns effectiveTagsFlow
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
