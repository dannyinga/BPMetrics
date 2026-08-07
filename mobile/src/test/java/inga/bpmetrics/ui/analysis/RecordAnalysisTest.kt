package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what a single recording says about itself.
 *
 * The two things worth guarding: a flat recording must report no moments — inventing highlights
 * out of a walk to the shops is worse than reporting none — and a climb must not be found across
 * a dropout, where what happened is unknown.
 */
class RecordAnalysisTest {

    private val tenPm = 1_700_000_000_000L

    /** @param bpms One reading per second from [startTime]. */
    private fun record(id: Long, startTime: Long, bpms: List<Double>, personId: Long? = 1): BpmRecord {
        val points = bpms.mapIndexed { i, bpm ->
            BpmDataPointEntity(
                dataPointId = id * 10_000 + i,
                recordOwnerId = id,
                timestamp = i * 1000L,
                bpm = bpm
            )
        }
        return BpmRecord(
            metadata = BpmRecordEntity(
                recordId = id,
                title = "Record $id",
                date = startTime,
                startTime = startTime,
                endTime = startTime + bpms.size * 1000L,
                durationMs = bpms.size * 1000L,
                avg = bpms.average().takeIf { bpms.isNotEmpty() },
                personId = personId
            ),
            dataPoints = points,
            minDataPoint = points.minByOrNull { it.bpm },
            maxDataPoint = points.maxByOrNull { it.bpm }
        )
    }

    private fun insightsFor(
        record: BpmRecord,
        others: List<BpmRecord> = emptyList()
    ): RecordInsights {
        val series = EventAnalysis.from(listOf(record)).series.firstOrNull()
        return RecordAnalysis.from(series, record, others)
    }

    @Test
    fun `a flat recording reports no moments`() {
        // Nothing is above the average, so there is nothing to report. Reporting the maximum
        // anyway would dress up a steady 70 as a highlight.
        val flat = record(1, tenPm, List(300) { 70.0 })

        assertTrue(insightsFor(flat).peaks.isEmpty())
    }

    @Test
    fun `a spike is reported once, not as a cluster`() {
        val bpms = MutableList(300) { 70.0 }
        (150..155).forEach { bpms[it] = 170.0 }

        val peaks = insightsFor(record(1, tenPm, bpms)).peaks

        assertEquals(1, peaks.size)
        assertEquals(170.0, peaks.single().bpm, 0.001)
    }

    @Test
    fun `two spikes a long way apart are two moments`() {
        val bpms = MutableList(600) { 70.0 }
        bpms[100] = 160.0
        bpms[500] = 175.0

        val peaks = insightsFor(record(1, tenPm, bpms)).peaks

        assertEquals(2, peaks.size)
        assertEquals(listOf(160.0, 175.0), peaks.map { it.bpm })
    }

    @Test
    fun `the biggest climb is the biggest rise, not the longest drift`() {
        // Sixty seconds climbing 60 to 90, then a fall, then thirty seconds climbing 60 to 140.
        val bpms = (0..59).map { 60.0 + it * 0.5 } +
            List(30) { 50.0 } +
            (0..29).map { 60.0 + it * 2.67 }

        val climb = insightsFor(record(1, tenPm, bpms)).longestClimb

        assertTrue("a climb was found", climb != null)
        assertTrue("the steeper rise wins", climb!!.riseBpm > 70.0)
    }

    @Test
    fun `a climb is not found across a dropout`() {
        // Thirty readings, five minutes of nothing, then a much higher run. Joining them would
        // invent a climb through a stretch where nothing was measured.
        val first = record(1, tenPm, List(30) { 60.0 })
        val gapped = first.copy(
            dataPoints = first.dataPoints + (0..29).map { i ->
                BpmDataPointEntity(
                    dataPointId = 90_000L + i,
                    recordOwnerId = 1,
                    timestamp = 330_000L + i * 1000L,
                    bpm = 180.0
                )
            }
        )

        val insights = insightsFor(gapped)

        assertEquals(1, insights.gaps.size)
        // Whatever climb is reported must sit entirely on one side of the gap.
        insights.longestClimb?.let { climb ->
            val gap = insights.gaps.single()
            assertTrue(
                "the climb does not span the dropout",
                climb.endMs <= gap.startMs || climb.startMs >= gap.endMs
            )
        }
    }

    @Test
    fun `a twitch is not a climb`() {
        // Two seconds of rise. Real, and not what anyone means by a climb.
        val bpms = List(200) { 70.0 }.toMutableList()
        bpms[100] = 90.0
        bpms[101] = 110.0

        assertNull(insightsFor(record(1, tenPm, bpms)).longestClimb)
    }

    @Test
    fun `gaps and measured time exclude the dropout`() {
        val first = record(1, tenPm, List(60) { 70.0 })
        val gapped = first.copy(
            dataPoints = first.dataPoints + (0..59).map { i ->
                BpmDataPointEntity(
                    dataPointId = 90_000L + i,
                    recordOwnerId = 1,
                    timestamp = 360_000L + i * 1000L,
                    bpm = 90.0
                )
            }
        )

        val insights = insightsFor(gapped)

        assertEquals(1, insights.gaps.size)
        assertEquals(301_000L, insights.missingMs)
        // 59 seconds of readings either side; none of the five minutes between.
        assertEquals(118_000L, insights.activeDurationMs)
    }

    @Test
    fun `a recording is ranked against that person's others`() {
        val theirs = listOf(
            record(2, tenPm, List(60) { 190.0 }),
            record(3, tenPm, List(60) { 100.0 })
        )
        val this_ = record(1, tenPm, List(60) { 150.0 })

        val comparison = insightsFor(this_, theirs).comparison!!

        assertEquals(2, comparison.peakRank)
        assertEquals(3, comparison.totalRecordings)
        assertTrue(comparison.isMeaningful)
    }

    @Test
    fun `a person's only recording has nothing to compare against`() {
        val comparison = insightsFor(record(1, tenPm, List(60) { 150.0 })).comparison!!

        assertEquals(1, comparison.peakRank)
        assertEquals(1, comparison.totalRecordings)
        assertTrue("nothing worth saying", !comparison.isMeaningful)
    }

    @Test
    fun `the average comparison reads above and below`() {
        val theirs = listOf(record(2, tenPm, List(60) { 100.0 }))
        val higher = record(1, tenPm, List(60) { 150.0 })

        // Their averages are 150 and 100, mean 125; this one is 20% above it.
        assertEquals(20, insightsFor(higher, theirs).comparison!!.percentVsAverage)
    }

    @Test
    fun `an empty recording produces empty insights rather than a crash`() {
        val empty = record(1, tenPm, emptyList())

        val insights = insightsFor(empty)

        assertTrue(insights.peaks.isEmpty())
        assertNull(insights.longestClimb)
        assertEquals(0L, insights.activeDurationMs)
    }
}
