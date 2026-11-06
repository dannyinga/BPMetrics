package inga.bpmetrics.export

import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.BpmRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers how several records are placed on one shared time axis for multi-watch export.
 *
 * The clock-time cases are the ones that keep a video in sync: a record must land at the same
 * offset it would occupy if it had been exported on its own.
 */
class VideoFrameAlignmentTest {

    private val tenAm = 1_700_000_000_000L

    /**
     * Builds a record starting at [startTime] with a sample every second for [durationMs].
     */
    private fun record(
        id: Long,
        startTime: Long,
        durationMs: Long,
        firstSampleOffsetMs: Long = 0L
    ): BpmRecordWithPoints {
        val points = (0..(durationMs / 1000)).map { i ->
            BpmDataPointEntity(
                dataPointId = id * 1000 + i,
                recordOwnerId = id,
                timestamp = firstSampleOffsetMs + i * 1000L,
                bpm = 70.0 + i
            )
        }
        return BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = id,
                title = "Record $id",
                date = startTime,
                startTime = startTime,
                endTime = startTime + durationMs,
                durationMs = durationMs
            ),
            dataPoints = points,
            minDataPoint = points.first(),
            maxDataPoint = points.last()
        )
    }

    @Test
    fun `clock time places a later record at its real distance from the earliest start`() {
        val first = record(id = 1, startTime = tenAm, durationMs = 60_000L)
        // Started three minutes after the first one.
        val second = record(id = 2, startTime = tenAm + 180_000L, durationMs = 60_000L)

        val aligned = VideoFrameRenderer.alignRecords(listOf(first, second))

        assertEquals(tenAm, aligned.timeline.originWallClockMs)
        assertEquals(0L, aligned.records[0].dataPoints.first().timestamp)
        assertEquals(180_000L, aligned.records[1].dataPoints.first().timestamp)
        // Timeline runs from the earliest start to the last sample of the record ending last.
        assertEquals(240_000L, aligned.timeline.durationMs)
    }

    @Test
    fun `clock time is unaffected by the order records are passed in`() {
        // The library hands records over newest-first, so ordering must not pick the origin.
        val early = record(id = 1, startTime = tenAm, durationMs = 60_000L)
        val late = record(id = 2, startTime = tenAm + 180_000L, durationMs = 60_000L)

        val forward = VideoFrameRenderer.alignRecords(listOf(early, late))
        val reversed = VideoFrameRenderer.alignRecords(listOf(late, early))

        assertEquals(forward.timeline, reversed.timeline)
        assertEquals(0L, reversed.records[1].dataPoints.first().timestamp)
        assertEquals(180_000L, reversed.records[0].dataPoints.first().timestamp)
    }

    @Test
    fun `a clock aligned record keeps the offset it would have alone against the same video`() {
        // Exported by itself, a record's timeline origin is its own start, so its first sample
        // sits at 0. Exported alongside an earlier record, it must move by exactly the gap
        // between the two starts — that shift is what keeps one video in sync with both.
        val gapMs = 420_000L
        val early = record(id = 1, startTime = tenAm, durationMs = 60_000L)
        val late = record(id = 2, startTime = tenAm + gapMs, durationMs = 60_000L)

        val alone = VideoFrameRenderer.alignRecords(listOf(late))
        val together = VideoFrameRenderer.alignRecords(listOf(early, late))

        val aloneFirst = alone.records.first().dataPoints.first().timestamp
        val togetherFirst = together.records[1].dataPoints.first().timestamp

        assertEquals(0L, aloneFirst)
        assertEquals(aloneFirst + gapMs, togetherFirst)
        // The origin moved back by the same gap, so the video shifts with it and stays aligned.
        assertEquals(alone.timeline.originWallClockMs - gapMs, together.timeline.originWallClockMs)
    }

    @Test
    fun `clock time preserves an acquisition delay instead of hiding it`() {
        // The same delayed record under clock alignment keeps its gap, because that gap is real.
        val delayed = record(id = 1, startTime = tenAm, durationMs = 30_000L, firstSampleOffsetMs = 5_000L)
        val prompt = record(id = 2, startTime = tenAm, durationMs = 30_000L)

        val aligned = VideoFrameRenderer.alignRecords(listOf(delayed, prompt))

        assertEquals(5_000L, aligned.records[0].dataPoints.first().timestamp)
        assertEquals(0L, aligned.records[1].dataPoints.first().timestamp)
    }

    @Test
    fun `timelineFor agrees with alignRecords without copying data points`() {
        val records = listOf(
            record(id = 1, startTime = tenAm, durationMs = 60_000L),
            record(id = 2, startTime = tenAm + 180_000L, durationMs = 90_000L)
        )

        assertEquals(VideoFrameRenderer.alignRecords(records).timeline, VideoFrameRenderer.timelineFor(records))
    }

    @Test
    fun `aligning keeps record identity so colors and the legend still match`() {
        val records = listOf(
            record(id = 7, startTime = tenAm, durationMs = 10_000L),
            record(id = 9, startTime = tenAm + 5_000L, durationMs = 10_000L)
        )

        val aligned = VideoFrameRenderer.alignRecords(records)

        assertEquals(listOf(7L, 9L), aligned.records.map { it.metadata.recordId })
    }

    @Test
    fun `an empty selection yields an empty timeline rather than failing`() {
        val aligned = VideoFrameRenderer.alignRecords(emptyList())

        assertEquals(emptyList<BpmRecordWithPoints>(), aligned.records)
        assertEquals(0L, aligned.timeline.durationMs)
    }
}
