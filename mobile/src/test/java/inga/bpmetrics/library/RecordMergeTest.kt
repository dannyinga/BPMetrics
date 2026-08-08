package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Joining recordings back into one.
 *
 * Worth testing hard because a merge done wrong produces a recording that looks entirely plausible
 * and describes something that never happened — and once the originals are gone there is nothing
 * left to compare it against.
 */
class RecordMergeTest {

    private val noon = 1_700_000_000_000L

    private fun record(
        id: Long,
        personId: Long?,
        startTime: Long,
        durationMs: Long,
        bpm: List<Pair<Long, Double>> = listOf(0L to 90.0)
    ) = BpmRecord(
        metadata = BpmRecordEntity(
            recordId = id,
            title = "Recording $id",
            date = startTime,
            startTime = startTime,
            endTime = startTime + durationMs,
            durationMs = durationMs,
            personId = personId
        ),
        dataPoints = bpm.map { (at, value) ->
            BpmDataPointEntity(recordOwnerId = id, timestamp = at, bpm = value)
        },
        minDataPoint = null,
        maxDataPoint = null
    )

    @Test
    fun `two recordings of one person can be joined`() {
        val records = listOf(
            record(1, personId = 5, startTime = noon, durationMs = 60_000),
            record(2, personId = 5, startTime = noon + 120_000, durationMs = 60_000)
        )

        assertTrue(RecordMerge.canMerge(records))
        assertNull(RecordMerge.refusal(records))
    }

    @Test
    fun `different people are refused, with a reason`() {
        // Two hearts on one curve, presented as one. Nothing downstream could detect it.
        val records = listOf(
            record(1, personId = 5, startTime = noon, durationMs = 60_000),
            record(2, personId = 6, startTime = noon, durationMs = 60_000)
        )

        assertFalse(RecordMerge.canMerge(records))
        assertNotNull(RecordMerge.refusal(records))
    }

    @Test
    fun `recordings attributed to nobody are refused`() {
        // Without a person there is no evidence they are the same heart, and joining them would be
        // a guess presented as data.
        val records = listOf(
            record(1, personId = null, startTime = noon, durationMs = 60_000),
            record(2, personId = null, startTime = noon + 60_000, durationMs = 60_000)
        )

        assertFalse(RecordMerge.canMerge(records))
        assertNotNull(RecordMerge.refusal(records))
    }

    @Test
    fun `one recording is not a merge`() {
        assertFalse(RecordMerge.canMerge(listOf(record(1, 5, noon, 60_000))))
    }

    @Test
    fun `readings keep the instant they were taken at`() {
        // The gap between two sets is real. Concatenating by elapsed time would slide the second
        // up against the first and invent a continuity that never happened.
        val records = listOf(
            record(1, 5, noon, 60_000, bpm = listOf(0L to 80.0, 30_000L to 100.0)),
            record(2, 5, noon + 600_000, 60_000, bpm = listOf(0L to 150.0))
        )

        val merged = RecordMerge.combine(records)!!

        assertEquals(noon, merged.startTime)
        assertEquals(listOf(0L, 30_000L, 600_000L), merged.points.map { it.timestampMs })
        assertEquals(listOf(80.0, 100.0, 150.0), merged.points.map { it.bpm })
    }

    @Test
    fun `order of selection does not matter`() {
        // The library hands over whatever order the user tapped in. Clock order is the only one
        // that describes the evening.
        val later = record(2, 5, noon + 600_000, 60_000, bpm = listOf(0L to 150.0))
        val earlier = record(1, 5, noon, 60_000, bpm = listOf(0L to 80.0))

        val merged = RecordMerge.combine(listOf(later, earlier))!!

        assertEquals(noon, merged.startTime)
        assertEquals(listOf(80.0, 150.0), merged.points.map { it.bpm })
    }

    @Test
    fun `the merged span reaches the end of the last recording`() {
        val records = listOf(
            record(1, 5, noon, 60_000),
            record(2, 5, noon + 600_000, 90_000)
        )

        val merged = RecordMerge.combine(records)!!

        assertEquals(noon + 690_000, merged.endTime)
        assertEquals(690_000L, merged.durationMs)
    }

    @Test
    fun `the silence between recordings is reported before the merge`() {
        // Joining two sets ten minutes apart is legitimate, and also a mistake someone might be
        // about to make. Saying so first is the difference between the two.
        val records = listOf(
            record(1, 5, noon, 60_000),
            record(2, 5, noon + 600_000, 60_000)
        )

        // First ends 60s in, second starts 600s in: nine minutes of silence between them.
        assertEquals(540_000L, RecordMerge.gapMs(records))
    }

    @Test
    fun `back-to-back recordings report no gap`() {
        val records = listOf(
            record(1, 5, noon, 60_000),
            record(2, 5, noon + 60_000, 60_000)
        )

        assertEquals(0L, RecordMerge.gapMs(records))
    }

    @Test
    fun `recordings with no readings produce nothing rather than an empty recording`() {
        val records = listOf(
            record(1, 5, noon, 60_000, bpm = emptyList()),
            record(2, 5, noon + 60_000, 60_000, bpm = emptyList())
        )

        assertNull(RecordMerge.combine(records))
    }

    @Test
    fun `a recording with no readings is skipped rather than dragging the span with it`() {
        val records = listOf(
            record(1, 5, noon, 60_000, bpm = listOf(0L to 90.0)),
            record(2, 5, noon - 600_000, 60_000, bpm = emptyList())
        )

        val merged = RecordMerge.combine(records)!!

        assertEquals("an empty recording must not set the origin", noon, merged.startTime)
    }
}
