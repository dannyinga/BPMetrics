package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clustering is the only thing standing between "a year of loose recordings" and "an event", so
 * the boundaries matter more than the happy path: exactly at the gap, one millisecond past it, and
 * a long recording that swallows a short one.
 */
class EventSuggestionsTest {

    private val minute = 60_000L

    private fun record(id: Long, startMin: Long, durationMin: Long): BpmRecord {
        val start = startMin * minute
        val end = start + durationMin * minute
        return BpmRecord(
            metadata = BpmRecordEntity(
                recordId = id,
                title = "Recording $id",
                date = start,
                startTime = start,
                endTime = end,
                durationMs = end - start
            ),
            dataPoints = emptyList(),
            minDataPoint = null,
            maxDataPoint = null
        )
    }

    @Test
    fun `recordings started together are one suggestion`() {
        val result = suggestEvents(
            listOf(record(1, 0, 30), record(2, 1, 28), record(3, 2, 31))
        )

        assertEquals(1, result.size)
        assertEquals(3, result.first().size)
        assertEquals(0L, result.first().span.startMs)
        assertEquals(33 * minute, result.first().span.endMs)
    }

    @Test
    fun `a gap longer than the threshold starts a new occasion`() {
        // Ends at 30, next starts at 91 — 61 minutes of quiet.
        val result = suggestEvents(
            listOf(record(1, 0, 30), record(2, 1, 29), record(3, 91, 20), record(4, 92, 19))
        )

        assertEquals(2, result.size)
        assertEquals(listOf(1L, 2L), result[0].records.map { it.metadata.recordId })
        assertEquals(listOf(3L, 4L), result[1].records.map { it.metadata.recordId })
    }

    @Test
    fun `a gap exactly at the threshold still joins`() {
        // First ends at minute 10, second starts at minute 40 — exactly 30 minutes.
        val result = suggestEvents(listOf(record(1, 0, 10), record(2, 40, 5)))

        assertEquals(1, result.size)
        assertEquals(2, result.first().size)
    }

    @Test
    fun `one millisecond past the threshold splits`() {
        val a = record(1, 0, 10)
        val b = a.copy(
            metadata = a.metadata.copy(
                recordId = 2,
                startTime = 40 * minute + 1,
                endTime = 45 * minute
            )
        )

        assertTrue(suggestEvents(listOf(a, b)).isEmpty())
    }

    @Test
    fun `a long recording keeps the cluster open for what falls inside it`() {
        // One watch runs three hours; two short ones sit inside it, an hour apart. Ordering by
        // start time alone would compare each against its predecessor and split them.
        val long = record(1, 0, 180)
        val mid = record(2, 60, 5)
        val late = record(3, 130, 5)

        val result = suggestEvents(listOf(long, mid, late))

        assertEquals(1, result.size)
        assertEquals(3, result.first().size)
        assertEquals(180 * minute, result.first().span.endMs)
    }

    @Test
    fun `a lone recording is not a suggestion`() {
        assertTrue(suggestEvents(listOf(record(1, 0, 10))).isEmpty())
        // Two clusters, each of one, is still nothing worth offering.
        assertTrue(suggestEvents(listOf(record(1, 0, 10), record(2, 500, 10))).isEmpty())
    }

    @Test
    fun `unordered input clusters the same way`() {
        val ordered = suggestEvents(listOf(record(1, 0, 30), record(2, 1, 28), record(3, 200, 10), record(4, 201, 9)))
        val shuffled = suggestEvents(listOf(record(4, 201, 9), record(1, 0, 30), record(3, 200, 10), record(2, 1, 28)))

        assertEquals(
            ordered.map { c -> c.records.map { it.metadata.recordId } },
            shuffled.map { c -> c.records.map { it.metadata.recordId } }
        )
    }

    @Test
    fun `empty input yields nothing`() {
        assertTrue(suggestEvents(emptyList()).isEmpty())
    }
}
