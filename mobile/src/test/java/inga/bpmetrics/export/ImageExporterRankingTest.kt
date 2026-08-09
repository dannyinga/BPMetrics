package inga.bpmetrics.export

import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.BpmRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the order the live BPM pills are stacked in during a multi-watch video, and how they
 * move between places.
 *
 * The property that matters most here is that a pill's position is a function of the playhead and
 * nothing else. Video frames are rendered independently, and a position carried between them would
 * come out different on a re-render — so these tests pin the placement to the timestamp alone.
 */
class ImageExporterRankingTest {

    /** A record whose readings are [bpms], one per second from the start of the timeline. */
    private fun record(id: Long, bpms: List<Double>, startOffsetMs: Long = 0L): BpmRecordWithPoints {
        val points = bpms.mapIndexed { i, bpm ->
            BpmDataPointEntity(
                dataPointId = id * 10_000 + i,
                recordOwnerId = id,
                timestamp = startOffsetMs + i * 1000L,
                bpm = bpm
            )
        }
        return BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = id,
                title = "Record $id",
                date = 0L,
                startTime = 0L,
                endTime = points.last().timestamp,
                durationMs = points.last().timestamp
            ),
            dataPoints = points,
            minDataPoint = points.minByOrNull { it.bpm }!!,
            maxDataPoint = points.maxByOrNull { it.bpm }!!
        )
    }

    private fun steady(id: Long, bpm: Double, seconds: Int = 120) =
        record(id, List(seconds) { bpm })

    @Test
    fun `the fastest heart rate takes the top place`() {
        val slots = ImageExporter.animatedRankSlots(
            listOf(steady(1, 120.0), steady(2, 150.0), steady(3, 90.0)),
            playhead = 30_000.0
        )

        assertEquals(1f, slots[0], TOLERANCE)
        assertEquals(0f, slots[1], TOLERANCE)
        assertEquals(2f, slots[2], TOLERANCE)
    }

    @Test
    fun `a settled stack sits on whole places rather than between them`() {
        val slots = ImageExporter.animatedRankSlots(
            listOf(steady(1, 120.0), steady(2, 150.0)),
            playhead = 30_000.0
        )

        // Nothing has changed places recently, so nothing should be drawn mid-slide.
        slots.forEach { assertEquals(it.toDouble(), Math.round(it).toDouble(), TOLERANCE.toDouble()) }
    }

    /**
     * One wearer climbing past another: 100 bpm until the sixtieth second, then 180, against a
     * steady 140. The curves cross at 59.5s.
     */
    private fun overtaking() = listOf(
        record(1, List(60) { 100.0 } + List(60) { 180.0 }),
        steady(2, 140.0)
    )

    @Test
    fun `a wearer who speeds past another ends up above them`() {
        val before = ImageExporter.animatedRankSlots(overtaking(), playhead = 50_000.0)
        assertEquals("slower wearer starts below", 1f, before[0], TOLERANCE)
        assertEquals(0f, before[1], TOLERANCE)

        val after = ImageExporter.animatedRankSlots(overtaking(), playhead = 70_000.0)
        assertEquals("and finishes above", 0f, after[0], TOLERANCE)
        assertEquals(1f, after[1], TOLERANCE)
    }

    @Test
    fun `the change of place is a slide rather than a jump`() {
        // A quarter of the way through, both pills are caught between places.
        val slots = ImageExporter.animatedRankSlots(overtaking(), playhead = 59_650.0)

        assertTrue("expected a part-way position, got ${slots[0]}", slots[0] > 0f && slots[0] < 1f)
        assertTrue("expected a part-way position, got ${slots[1]}", slots[1] > 0f && slots[1] < 1f)
        // The slide has started but not yet carried them past one another.
        assertTrue("expected the climbing wearer still below", slots[0] > slots[1])
    }

    @Test
    fun `pills trading places pass through one another`() {
        // Half way through the slide the two are level, because they are moving through the same
        // space in opposite directions. There is no arrangement in which they avoid each other —
        // which is why the draw order puts whichever is moving furthest on top, and why each pill
        // carries a rim in its wearer's colour so the boundary survives the overlap.
        val slots = ImageExporter.animatedRankSlots(overtaking(), playhead = 59_800.0)

        assertEquals(0.5f, slots[0], 0.05f)
        assertEquals(0.5f, slots[1], 0.05f)
    }

    @Test
    fun `the slide is over within its allotted time`() {
        // The crossing is at 59.5s and the slide lasts 600ms, so by 60.2s it is done.
        val slots = ImageExporter.animatedRankSlots(overtaking(), playhead = 60_200.0)

        assertEquals(0f, slots[0], TOLERANCE)
        assertEquals(1f, slots[1], TOLERANCE)
    }

    @Test
    fun `a wearer whose watch is not reading sinks to the bottom`() {
        val records = listOf(
            // Stops after a minute, so it has nothing to report at the playhead.
            record(1, List(60) { 100.0 }),
            steady(2, 80.0)
        )

        val slots = ImageExporter.animatedRankSlots(records, playhead = 90_000.0)

        assertEquals("the reading wearer holds the top place", 0f, slots[1], TOLERANCE)
        assertEquals(1f, slots[0], TOLERANCE)
    }

    @Test
    fun `wearers on the same heart rate keep the order they were given`() {
        val slots = ImageExporter.animatedRankSlots(
            listOf(steady(1, 100.0), steady(2, 100.0), steady(3, 100.0)),
            playhead = 30_000.0
        )

        // A tie must not let the stack shuffle from one frame to the next.
        assertEquals(0f, slots[0], TOLERANCE)
        assertEquals(1f, slots[1], TOLERANCE)
        assertEquals(2f, slots[2], TOLERANCE)
    }

    @Test
    fun `the stack keeps its place on screen throughout a change`() {
        val records = overtaking() + steady(3, 60.0)

        // Whatever the pills are doing, their positions must still add up to the same total, or
        // the block would drift up or down the graph mid-swap and take the clock with it.
        listOf(50_000.0, 59_500.0, 59_800.0, 60_100.0, 70_000.0).forEach { playhead ->
            val total = ImageExporter.animatedRankSlots(records, playhead).sum()
            assertEquals("at $playhead", 3f, total, TOLERANCE)
        }
    }

    @Test
    fun `the same instant always places the pills identically`() {
        // Frames are rendered independently and not necessarily in order, so a frame drawn again
        // after a seek has to come out exactly as it did the first time.
        val first = ImageExporter.animatedRankSlots(overtaking(), playhead = 59_800.0)
        val second = ImageExporter.animatedRankSlots(overtaking(), playhead = 59_800.0)

        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun `a lone wearer holds the only place`() {
        val slots = ImageExporter.animatedRankSlots(listOf(steady(1, 100.0)), playhead = 30_000.0)

        assertEquals(1, slots.size)
        assertEquals(0f, slots[0], TOLERANCE)
    }

    @Test
    fun `readings are found correctly deep into a long recording`() {
        // Guards the binary search that replaced a scan through every point.
        val long = record(1, List(3_600) { i -> 60.0 + (i % 100) })
        val other = steady(2, 61.0, seconds = 3_600)

        // At 1000s the first wearer is on 60 + (1000 % 100) = 60, just below the other.
        assertEquals(listOf(1, 0), ImageExporter.rankOrderAt(listOf(long, other), 1_000_000.0))
        // At 1050s it is on 110, well above.
        assertEquals(listOf(0, 1), ImageExporter.rankOrderAt(listOf(long, other), 1_050_000.0))
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
