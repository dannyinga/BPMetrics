package inga.bpmetrics.ui

import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.EventEntity
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
    private val eventsFlow = MutableStateFlow<List<EventEntity>>(emptyList())
    private val locationsFlow =
        MutableStateFlow<List<inga.bpmetrics.library.LocationEntity>>(emptyList())

    @Before
    fun setup() {
        // Every flow `filteredRecords` combines has to be stubbed, not just the one under test.
        // A relaxed mock hands back a Flow that never emits, and `combine` waits for a first value
        // from all of its sources — so one missing stub hangs the whole list and the test fails
        // with a timeout that says nothing about which flow was missing.
        every { repository.records } returns recordsFlow
        every { repository.effectiveTags } returns effectiveTagsFlow
        every { repository.getAllEvents() } returns eventsFlow
        // `combine` waits on every source, and a relaxed mock hands back a Flow that never emits —
        // so one missing stub hangs the test with a timeout that names nothing. Anything
        // `filteredRecords` reads has to be stubbed, even where this test does not care about it.
        every { repository.allEventsInTree } returns eventsFlow
        every { repository.getAllLocations() } returns locationsFlow
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
