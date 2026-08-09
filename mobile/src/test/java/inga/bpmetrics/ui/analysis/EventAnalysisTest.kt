package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.WatchEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the one thing an event analysis does that a concurrent analysis does not: collapse a
 * person's several recordings into one lane.
 *
 * The failure this guards against is a watch dropping out mid-evening and its owner appearing twice
 * in two colours, as though two people were there.
 */
class EventAnalysisTest {

    private val tenPm = 1_700_000_000_000L

    private val kyle = PersonEntity(personId = 1, name = "Kyle", colorArgb = 0xFFFF0000.toInt())
    private val ben = PersonEntity(personId = 2, name = "Ben", colorArgb = 0xFF00FF00.toInt())

    /** @param bpms One reading per second from [startTime]. */
    private fun record(
        id: Long,
        startTime: Long,
        bpms: List<Double>,
        personId: Long? = null,
        watchId: String? = null,
        wearer: String = ""
    ): BpmRecordWithPoints {
        val points = bpms.mapIndexed { i, bpm ->
            BpmDataPointEntity(
                dataPointId = id * 10_000 + i,
                recordOwnerId = id,
                timestamp = i * 1000L,
                bpm = bpm
            )
        }
        return BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = id,
                title = "Record $id",
                date = startTime,
                startTime = startTime,
                endTime = startTime + bpms.size * 1000L,
                durationMs = bpms.size * 1000L,
                avg = bpms.average(),
                personId = personId,
                watchId = watchId,
                wearerName = wearer
            ),
            dataPoints = points,
            minDataPoint = points.minByOrNull { it.bpm },
            maxDataPoint = points.maxByOrNull { it.bpm }
        )
    }

    @Test
    fun `one person's two recordings become one lane with one gap`() {
        // Sixty seconds of readings, five minutes of nothing, sixty more. A concurrent analysis
        // would call this two people.
        val first = record(1, tenPm, List(60) { 70.0 }, personId = kyle.personId)
        val second = record(2, tenPm + 360_000L, List(60) { 90.0 }, personId = kyle.personId)

        val analysis = EventAnalysis.from(listOf(first, second), people = listOf(kyle))

        assertEquals(1, analysis.series.size)
        val lane = analysis.series.single()
        assertEquals("Kyle", lane.label)
        assertEquals(listOf(1L, 2L), lane.recordIds)
        assertEquals(120, lane.points.size)
        assertEquals(1, lane.gaps.size)
        assertEquals(70.0, lane.minBpm, 0.001)
        assertEquals(90.0, lane.maxBpm, 0.001)
    }

    @Test
    fun `two people are two lanes in their own colours`() {
        val a = record(1, tenPm, List(60) { 70.0 }, personId = kyle.personId)
        val b = record(2, tenPm, List(60) { 80.0 }, personId = ben.personId)

        val analysis = EventAnalysis.from(listOf(a, b), people = listOf(kyle, ben))

        assertEquals(2, analysis.series.size)
        assertEquals(setOf("Kyle", "Ben"), analysis.series.map { it.label }.toSet())
        assertEquals(kyle.colorArgb, analysis.series.first { it.label == "Kyle" }.colorArgb)
        assertEquals(ben.colorArgb, analysis.series.first { it.label == "Ben" }.colorArgb)
        assertNotEquals(analysis.series[0].colorArgb, analysis.series[1].colorArgb)
    }

    @Test
    fun `the merged lane crosses the join`() {
        // The point of merging: the gap sits between the two recordings, and nowhere else. A
        // per-record analysis would report zero gaps across two separate lanes.
        val first = record(1, tenPm, List(60) { 70.0 }, personId = kyle.personId)
        val second = record(2, tenPm + 360_000L, List(60) { 90.0 }, personId = kyle.personId)

        val lane = EventAnalysis.from(listOf(first, second), people = listOf(kyle)).series.single()

        val gap = lane.gaps.single()
        assertEquals(tenPm + 59_000L, gap.startMs)
        assertEquals(tenPm + 360_000L, gap.endMs)
        // 59 seconds of readings in each half, and none of the five-minute gap.
        assertEquals(118_000L, lane.activeDurationMs)
    }

    @Test
    fun `the gap is not interpolated across`() {
        val first = record(1, tenPm, List(60) { 70.0 }, personId = kyle.personId)
        val second = record(2, tenPm + 360_000L, List(60) { 90.0 }, personId = kyle.personId)

        val lane = EventAnalysis.from(listOf(first, second), people = listOf(kyle)).series.single()

        // Inside the dropout there is no reading to give, and inventing one is what the gap
        // threshold exists to prevent.
        assertNull(lane.bpmAt(tenPm + 180_000L))
        assertEquals(70.0, lane.bpmAt(tenPm + 30_000L)!!, 0.001)
        assertEquals(90.0, lane.bpmAt(tenPm + 390_000L)!!, 0.001)
    }

    @Test
    fun `recordings with nobody attached keep their own lanes`() {
        val a = record(1, tenPm, List(60) { 70.0 }, watchId = "watch-a")
        val b = record(2, tenPm, List(60) { 80.0 }, watchId = "watch-b")

        val analysis = EventAnalysis.from(
            listOf(a, b),
            watches = listOf(
                WatchEntity(watchId = "watch-a", deviceName = "Left"),
                WatchEntity(watchId = "watch-b", deviceName = "Right")
            )
        )

        assertEquals(2, analysis.series.size)
        assertEquals(setOf("Left", "Right"), analysis.series.map { it.label }.toSet())
    }

    @Test
    fun `an unattributed recording does not join a person's lane`() {
        val kyles = record(1, tenPm, List(60) { 70.0 }, personId = kyle.personId)
        val orphan = record(2, tenPm, List(60) { 80.0 }, watchId = "watch-b")

        val analysis = EventAnalysis.from(
            listOf(kyles, orphan),
            watches = listOf(WatchEntity(watchId = "watch-b", deviceName = "Spare")),
            people = listOf(kyle)
        )

        assertEquals(2, analysis.series.size)
        assertEquals(listOf(1L), analysis.series.first { it.label == "Kyle" }.recordIds)
    }

    @Test
    fun `simultaneous readings for one person are averaged rather than stacked`() {
        // Two watches on one person. Keeping both would leave the lane non-monotonic, and the
        // chart would draw a vertical spike at every instant they disagreed.
        val left = record(1, tenPm, List(30) { 60.0 }, personId = kyle.personId, watchId = "a")
        val right = record(2, tenPm, List(30) { 80.0 }, personId = kyle.personId, watchId = "b")

        val lane = EventAnalysis.from(listOf(left, right), people = listOf(kyle)).series.single()

        assertEquals(30, lane.points.size)
        assertTrue(lane.points.all { it.bpm == 70.0 })
        assertEquals(
            lane.points.map { it.wallClockMs },
            lane.points.map { it.wallClockMs }.sorted()
        )
        assertEquals("2 watches", lane.watchLabel)
    }

    @Test
    fun `lanes are ordered by when they start`() {
        val late = record(1, tenPm + 120_000L, List(30) { 70.0 }, personId = kyle.personId)
        val early = record(2, tenPm, List(30) { 80.0 }, personId = ben.personId)

        val analysis = EventAnalysis.from(listOf(late, early), people = listOf(kyle, ben))

        assertEquals(listOf("Ben", "Kyle"), analysis.series.map { it.label })
    }

    @Test
    fun `a window clips the lane without splitting it`() {
        val first = record(1, tenPm, List(60) { 70.0 }, personId = kyle.personId)
        val second = record(2, tenPm + 360_000L, List(60) { 90.0 }, personId = kyle.personId)

        val analysis = EventAnalysis.from(
            listOf(first, second),
            people = listOf(kyle),
            window = tenPm..(tenPm + 30_000L)
        )

        val lane = analysis.series.single()
        assertEquals(31, lane.points.size)
        assertTrue(lane.gaps.isEmpty())
    }

    @Test
    fun `no recordings is an empty analysis rather than a crash`() {
        assertTrue(EventAnalysis.from(emptyList()).isEmpty)
        // A recording with no readings contributes no lane either.
        assertTrue(EventAnalysis.from(listOf(record(1, tenPm, emptyList()))).isEmpty)
    }
}
