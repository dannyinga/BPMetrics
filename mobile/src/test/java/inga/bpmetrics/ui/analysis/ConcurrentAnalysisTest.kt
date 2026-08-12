package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.BpmRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers laying several wearers over one wall clock and finding what they did together.
 */
class ConcurrentAnalysisTest {

    private val tenPm = 1_700_000_000_000L

    /**
     * @param bpms One reading per second from [startTime].
     */
    private fun record(
        id: Long,
        startTime: Long,
        bpms: List<Double>,
        wearer: String = "Wearer $id"
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
                wearerName = wearer
            ),
            dataPoints = points,
            minDataPoint = points.minByOrNull { it.bpm },
            maxDataPoint = points.maxByOrNull { it.bpm }
        )
    }

    @Test
    fun `places every wearer on the same wall clock`() {
        // Started a minute apart, so their readings must not line up as though simultaneous.
        val first = record(1, tenPm, List(120) { 70.0 })
        val second = record(2, tenPm + 60_000L, List(120) { 80.0 })

        val analysis = ConcurrentAnalysis.from(listOf(first, second))

        assertEquals(tenPm, analysis.windowStartMs)
        // The window ends at the last reading, which is one interval before the record's end.
        assertEquals(tenPm + 60_000L + 119_000L, analysis.windowEndMs)
        assertEquals(70.0, analysis.series[0].bpmAt(tenPm + 10_000L)!!, 0.001)
        // The second wearer was not recording yet at that instant.
        assertNull(analysis.series[1].bpmAt(tenPm + 10_000L))
        assertEquals(80.0, analysis.series[1].bpmAt(tenPm + 70_000L)!!, 0.001)
    }

    @Test
    fun `intensity is normalised per wearer rather than compared as raw bpm`() {
        // Both peak at their own maximum at the same instant, from very different ranges.
        // Raw BPM would say the fitter wearer barely reacted; normalised says they both maxed out.
        val athlete = record(1, tenPm, List(60) { if (it == 30) 140.0 else 50.0 })
        val other = record(2, tenPm, List(60) { if (it == 30) 190.0 else 90.0 })

        val analysis = ConcurrentAnalysis.from(listOf(athlete, other))
        val peak = analysis.intensity.maxByOrNull { it.intensity }!!

        assertEquals(2, peak.participants)
        // Both at the top of their own range means full intensity, whatever the numbers were.
        assertTrue("Expected a near-total shared peak, got ${peak.intensity}", peak.intensity > 0.9f)
    }

    @Test
    fun `a stretch where nobody reacted reports no moments`() {
        // Flat data has no highlights. Ranking it anyway would fabricate them, and because calm
        // stretches are long they would survive the separation rule and look convincing.
        val a = record(1, tenPm, List(300) { 70.0 })
        val b = record(2, tenPm, List(300) { 75.0 })

        val analysis = ConcurrentAnalysis.from(listOf(a, b))

        assertTrue("Nothing happened, so nothing should be reported", analysis.peaks.isEmpty())
    }

    @Test
    fun `a spike from one person alone is not a group moment`() {
        val spiker = record(1, tenPm, List(120) { if (it in 55..65) 180.0 else 60.0 })
        val steady = record(2, tenPm, List(120) { 70.0 })

        val analysis = ConcurrentAnalysis.from(listOf(spiker, steady))

        // The peak list only reports instants more than one person shared, and a flat wearer
        // drags the average down rather than confirming the moment.
        val peakIntensities = analysis.peaks.map { it.intensity }
        assertTrue(
            "One person spiking alone should not read as a full group reaction",
            peakIntensities.all { it < 0.9f }
        )
    }

    @Test
    fun `peaks near each other collapse to the strongest`() {
        // A sustained thirty second surge is one moment, not fifteen.
        val surge = { i: Int -> if (i in 100..130) 170.0 else 60.0 }
        val a = record(1, tenPm, List(300) { surge(it) })
        val b = record(2, tenPm, List(300) { surge(it) })

        val analysis = ConcurrentAnalysis.from(listOf(a, b))

        assertEquals("A single surge should report once", 1, analysis.peaks.size)
    }

    @Test
    fun `readings are not invented across a sensor dropout`() {
        // A minute-long hole in the middle: the sensor was not delivering, so nothing is known.
        val before = (0 until 30).map { 70.0 }
        val points = before.mapIndexed { i, bpm ->
            BpmDataPointEntity(dataPointId = i.toLong(), recordOwnerId = 1, timestamp = i * 1000L, bpm = bpm)
        } + BpmDataPointEntity(dataPointId = 99, recordOwnerId = 1, timestamp = 90_000L, bpm = 75.0)

        val record = BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = 1,
                title = "Gappy",
                date = tenPm,
                startTime = tenPm,
                endTime = tenPm + 90_000L,
                durationMs = 90_000L,
                avg = 70.0
            ),
            dataPoints = points,
            minDataPoint = points.first(),
            maxDataPoint = points.last()
        )

        val series = ConcurrentAnalysis.from(listOf(record)).series.single()

        assertNotNull(series.bpmAt(tenPm + 10_000L))
        assertNull("Interpolating across a dropout would invent a heart rate", series.bpmAt(tenPm + 60_000L))
    }

    @Test
    fun `a window narrows the analysis to one set`() {
        val full = record(1, tenPm, List(600) { 70.0 })
        val window = (tenPm + 100_000L)..(tenPm + 200_000L)

        val analysis = ConcurrentAnalysis.from(listOf(full), window = window)

        assertEquals(tenPm + 100_000L, analysis.windowStartMs)
        assertEquals(tenPm + 200_000L, analysis.windowEndMs)
        assertTrue(analysis.series.single().points.all { it.wallClockMs in window })
    }

    @Test
    fun `recordings that never ran together are reported as having no overlap`() {
        val morning = record(1, tenPm, List(60) { 70.0 })
        val evening = record(2, tenPm + 10_000_000L, List(60) { 70.0 })

        val analysis = ConcurrentAnalysis.from(listOf(morning, evening))

        assertTrue(analysis.series.size == 2)
        assertTrue("Nothing was shared, so there is nothing to compare", !analysis.hasOverlap)
        assertTrue(analysis.peaks.isEmpty())
    }

    @Test
    fun `anyOverlap gates the action on recordings actually having run together`() {
        val a = record(1, tenPm, List(120) { 70.0 })
        val duringA = record(2, tenPm + 30_000L, List(120) { 70.0 })
        val muchLater = record(3, tenPm + 10_000_000L, List(60) { 70.0 })

        // Nothing to compare a recording against on its own.
        assertTrue(!ConcurrentAnalysis.anyOverlap(listOf(a).map { it.metadata }))
        // Different days share no moment, so there is no chart worth drawing.
        assertTrue(!ConcurrentAnalysis.anyOverlap(listOf(a, muchLater).map { it.metadata }))

        assertTrue(ConcurrentAnalysis.anyOverlap(listOf(a, duringA).map { it.metadata }))
        // Only one pair needs to overlap for the comparison to mean something.
        assertTrue(ConcurrentAnalysis.anyOverlap(listOf(a, muchLater, duringA).map { it.metadata }))
    }

    @Test
    fun `anyOverlap is not fooled by the order recordings arrive in`() {
        val early = record(1, tenPm, List(120) { 70.0 })
        val late = record(2, tenPm + 60_000L, List(120) { 70.0 })

        assertTrue(ConcurrentAnalysis.anyOverlap(listOf(late, early).map { it.metadata }))
    }

    @Test
    fun `overlapping finds the recordings made at the same time`() {
        val subject = record(1, tenPm, List(120) { 70.0 })
        val during = record(2, tenPm + 30_000L, List(120) { 70.0 })
        val after = record(3, tenPm + 500_000L, List(60) { 70.0 })

        val found = ConcurrentAnalysis.overlapping(subject, listOf(subject, during, after))

        assertEquals(listOf(1L, 2L), found.map { it.metadata.recordId })
    }

    @Test
    fun `an empty selection produces an empty analysis rather than failing`() {
        val analysis = ConcurrentAnalysis.from(emptyList())

        assertTrue(analysis.isEmpty)
        assertTrue(analysis.peaks.isEmpty())
        assertEquals(0L, analysis.durationMs)
    }

    @Test
    fun `wearer names label the curves`() {
        val analysis = ConcurrentAnalysis.from(
            listOf(
                record(1, tenPm, List(60) { 70.0 }, wearer = "Kyle"),
                record(2, tenPm, List(60) { 80.0 }, wearer = "Ben")
            )
        )

        assertEquals(listOf("Kyle", "Ben"), analysis.series.map { it.label })
    }
}
