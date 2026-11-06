package inga.bpmetrics.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a detail page keeps between visits, and what its header works out (S15.4, S15.5).
 */
class ScopeKeepingTest {

    @Test
    fun `what was left out comes back as it was`() {
        val left = ScopeExclusions(
            excludedEventIds = setOf(4L, 2L),
            excludedRecordIds = setOf(9L),
            includedDespiteFlag = setOf(7L)
        )
        assertEquals(left, ScopeExclusions.decode(left.encode()))
    }

    @Test
    fun `nothing left out is kept as nothing`() {
        assertEquals("", ScopeExclusions().encode())
        assertTrue(ScopeExclusions.decode(null).isEmpty)
        assertTrue(ScopeExclusions.decode("").isEmpty)
    }

    @Test
    fun `something unreadable is nothing left out, not a crash`() {
        assertTrue(ScopeExclusions.decode("garbage;;e:x,y").isEmpty)
    }

    @Test
    fun `the count of what is left out leaves out the flag overrides`() {
        val left = ScopeExclusions(excludedEventIds = setOf(1L, 2L), excludedRecordIds = setOf(3L), includedDespiteFlag = setOf(4L))
        assertEquals(3, left.leftOutCount)
    }

    private fun record(id: Long, start: Long, end: Long?, measured: Long, peak: Double? = 120.0) = AnalysisRecord(
        recordId = id, title = "R$id", date = start, minBpm = peak?.minus(40), avgBpm = peak?.minus(20),
        maxBpm = peak, activeDurationMs = measured, endMs = end
    )

    @Test
    fun `an event's length runs from the first start to the last end`() {
        val hour = 3_600_000L
        // Two people over a festival: one from the start, one finishing three days later.
        val state = AnalysisUiState(
            records = listOf(
                record(1, start = 0L, end = 20 * hour, measured = 18 * hour),
                record(2, start = 50 * hour, end = 75 * hour, measured = 24 * hour)
            )
        )
        assertEquals(75 * hour, state.lengthMs)
    }

    @Test
    fun `a snapshot without end times falls back to what each measured`() {
        val state = AnalysisUiState(records = listOf(record(1, start = 1_000L, end = null, measured = 5_000L)))
        assertEquals(5_000L, state.lengthMs)
    }

    @Test
    fun `a recording with no readings says it has none`() {
        assertFalse(AnalysisUiState(records = listOf(record(1, 0L, 10L, 10L, peak = null))).hasReadings)
        assertTrue(AnalysisUiState(records = listOf(record(1, 0L, 10L, 10L))).hasReadings)
    }
}
