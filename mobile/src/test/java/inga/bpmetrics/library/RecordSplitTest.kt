package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cutting a shorter recording out of a longer one.
 *
 * The arithmetic, tested away from the database and the two screens that used to each carry their
 * own copy of it. A split done wrong produces a recording that looks entirely plausible — a curve,
 * a duration, a peak — and describes a stretch of time that was never measured.
 */
class RecordSplitTest {

    private val evening = 1_700_000_000_000L

    /** A ten-minute recording with a reading every minute. */
    private fun record(
        durationMs: Long = 600_000L,
        bpm: List<Pair<Long, Double>> = (0..10).map { it * 60_000L to 80.0 + it }
    ) = BpmRecordWithPoints(
        metadata = BpmRecordEntity(
            recordId = 7,
            title = "Set",
            date = evening,
            startTime = evening,
            endTime = evening + durationMs,
            durationMs = durationMs
        ),
        dataPoints = bpm.map { (at, value) ->
            BpmDataPointEntity(recordOwnerId = 7, timestamp = at, bpm = value)
        },
        minDataPoint = null,
        maxDataPoint = null
    )

    @Test
    fun `readings are rebased onto the new recording's own start`() {
        // Otherwise the result is a fragment that begins four minutes in, and every chart, export
        // and duration in the app reads a timestamp as time since the recording started.
        val slice = RecordSplit.slice(record(), fromMs = 240_000L, toMs = 420_000L)

        assertNotNull(slice)
        assertEquals(listOf(0L, 60_000L, 120_000L, 180_000L), slice!!.points.map { it.timestampMs })
        assertEquals(listOf(84.0, 85.0, 86.0, 87.0), slice.points.map { it.bpm })
    }

    @Test
    fun `the new recording sits where it actually happened`() {
        val slice = RecordSplit.slice(record(), fromMs = 240_000L, toMs = 420_000L)!!

        assertEquals(evening + 240_000L, slice.startTime)
        assertEquals(evening + 420_000L, slice.endTime)
        assertEquals(180_000L, slice.durationMs)
    }

    @Test
    fun `a range holding no readings is refused rather than made empty`() {
        // A recording with no readings draws a flat line and reports zero, which looks like an
        // answer.
        val gapped = record(bpm = listOf(0L to 80.0, 540_000L to 95.0))

        assertNull(RecordSplit.slice(gapped, fromMs = 120_000L, toMs = 300_000L))
        assertEquals(
            "Nothing was recorded in that range.",
            RecordSplit.refusal(gapped, fromMs = 120_000L, toMs = 300_000L)
        )
    }

    @Test
    fun `an ordinary range is not refused`() {
        assertNull(RecordSplit.refusal(record(), fromMs = 0L, toMs = 600_000L))
        assertEquals(11, RecordSplit.readingsIn(record(), fromMs = 0L, toMs = 600_000L))
    }

    @Test
    fun `a range running backwards is refused, and says so`() {
        assertEquals(
            "The end has to come after the start.",
            RecordSplit.refusal(record(), fromMs = 300_000L, toMs = 120_000L)
        )
        assertEquals(
            "The end has to come after the start.",
            RecordSplit.refusal(record(), fromMs = 300_000L, toMs = 300_000L)
        )
        assertNull(RecordSplit.slice(record(), fromMs = 300_000L, toMs = 120_000L))
    }

    @Test
    fun `a range outside the recording is refused`() {
        assertEquals(
            "That range falls outside this recording.",
            RecordSplit.refusal(record(), fromMs = -1_000L, toMs = 300_000L)
        )
        assertEquals(
            "That range falls outside this recording.",
            RecordSplit.refusal(record(), fromMs = 0L, toMs = 900_000L)
        )
    }

    @Test
    fun `the tail of a recording that stopped measuring early is still splittable`() {
        // It ran for ten minutes and the watch dropped out at seven. Those last three minutes
        // happened, and a range ending inside them is a legitimate thing to ask for — bounds come
        // from the duration, not from the last reading.
        val droppedOut = record(bpm = (0..7).map { it * 60_000L to 80.0 })

        assertNull(RecordSplit.refusal(droppedOut, fromMs = 300_000L, toMs = 600_000L))
        assertEquals(3, RecordSplit.slice(droppedOut, fromMs = 300_000L, toMs = 600_000L)!!.points.size)
    }
}
