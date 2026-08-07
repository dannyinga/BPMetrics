package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the band split every level of the app reads from.
 *
 * The share is what people will act on — "who spent the most time in the peak band" — so the two
 * things that matter are that a dropout contributes no time at all, and that summing a person's
 * recordings gives the same answer as summing the whole scope.
 */
class BpmZonesTest {

    private val start = 1_700_000_000_000L

    /** One reading per second. */
    private fun readings(bpms: List<Double>, from: Long = start) =
        bpms.mapIndexed { i, bpm -> (from + i * 1000L) to bpm }

    @Test
    fun `an interval belongs to the band its earlier reading was in`() {
        // Ten readings at 80 (Resting) then ten at 140 (Elevated) is nineteen intervals. The one
        // spanning the jump counts as Resting, because that is where the stretch of time it
        // measures began — attributing it to the band it landed in would credit Elevated with a
        // second nobody spent there, and no interval may belong to a band neither end was in.
        val split = BpmZones.split(readings(List(10) { 80.0 } + List(10) { 140.0 }))

        assertEquals(10_000L, split.first { it.zone.name == "Resting" }.durationMs)
        assertEquals(9_000L, split.first { it.zone.name == "Elevated" }.durationMs)
        assertEquals(0L, split.first { it.zone.name == "Light" }.durationMs)
        assertEquals(19_000L, split.sumOf { it.durationMs })
    }

    @Test
    fun `a dropout contributes no time to any band`() {
        val before = readings(List(10) { 80.0 })
        val after = readings(List(10) { 80.0 }, from = start + 300_000L)

        val split = BpmZones.split(before + after)

        // Eighteen one-second intervals; the five-minute gap is not attributed anywhere.
        assertEquals(18_000L, split.sumOf { it.durationMs })
    }

    @Test
    fun `shares add up to one`() {
        val split = BpmZones.split(readings(List(30) { 80.0 } + List(30) { 170.0 }))

        assertEquals(1f, split.sumOf { it.share.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `the top band has no ceiling`() {
        val split = BpmZones.split(readings(List(10) { 250.0 }))

        assertEquals(9_000L, split.first { it.zone.name == "Peak" }.durationMs)
    }

    @Test
    fun `merging recordings gives the same answer as one long one`() {
        // The property the whole screen rests on: a person's row, a tag's row and the scope total
        // are all merges of the same per-record splits, so they can never disagree.
        val first = BpmZones.split(readings(List(20) { 80.0 }))
        val second = BpmZones.split(readings(List(20) { 170.0 }, from = start + 10_000_000L))

        val merged = BpmZones.merge(listOf(first, second))

        assertEquals(19_000L, merged.first { it.zone.name == "Resting" }.durationMs)
        assertEquals(19_000L, merged.first { it.zone.name == "Peak" }.durationMs)
        assertEquals(0.5f, merged.first { it.zone.name == "Peak" }.share, 0.001f)
    }

    @Test
    fun `merging nothing is not a division by zero`() {
        val merged = BpmZones.merge(emptyList())

        assertTrue(merged.all { it.durationMs == 0L && it.share == 0f })
    }

    @Test
    fun `a single reading has no measured time`() {
        // Duration comes from the interval between readings, and one reading has no interval.
        assertEquals(0L, BpmZones.split(readings(listOf(80.0))).sumOf { it.durationMs })
    }
}
