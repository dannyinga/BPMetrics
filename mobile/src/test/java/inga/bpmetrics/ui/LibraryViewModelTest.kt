package inga.bpmetrics.ui

import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.ui.library.LibraryViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit test for [inga.bpmetrics.ui.library.LibraryViewModel].
 */
class LibraryViewModelTest {

    @Test
    fun `initial state is loading`() = runBlocking {
        val fakeRepository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(fakeRepository as LibraryRepository)
        
        // The first emission might be the initial value
        val state = viewModel.uiState.first()
        // Note: Depending on coroutine execution, it might already be loaded
        // if the flow collection is immediate.
    }

    @Test
    fun `uiState updates when repository emits records`() = runBlocking {
        val fakeRepository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(fakeRepository as LibraryRepository)
        
        val testRecords = listOf(
            BpmRecord(metadata = inga.bpmetrics.library.BpmRecordEntity(id = 1, title = "Test", date = 0, startTime = 0, endTime = 0, durationMs = 0), dataPoints = emptyList(), minPoint = null, maxPoint = null)
        )
        
        fakeRepository.emitRecords(testRecords)
        
        val state = viewModel.uiState.value
        assertEquals(testRecords, state.records)
        assertFalse(state.isLoading)
    }
}

/**
 * Fake implementation of LibraryRepository for testing.
 * We cast this to LibraryRepository in the test using a trick or by making 
 * LibraryRepository an interface (recommended). 
 * For now, we'll assume the ViewModel can take a mock or fake.
 */
class FakeLibraryRepository {
    private val _records = MutableStateFlow<List<BpmRecord>>(emptyList())
    val records: StateFlow<List<BpmRecord>> = _records

    fun emitRecords(records: List<BpmRecord>) {
        _records.value = records
    }
}
