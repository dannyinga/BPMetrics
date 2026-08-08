package inga.bpmetrics.export

import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * The pulse's arithmetic.
 *
 * Written against a bug that could only be seen by exporting a video and watching it: the ring
 * around each wearer's face jittered instead of beating. The cause was `sin(t × bpm)` — frequency
 * multiplied by absolute time — which jumps by `t × Δf` whenever the reading changes. Ten minutes
 * in, one bpm of change is about ten whole cycles, so every interpolated tick threw the beat to a
 * new random point.
 *
 * The tests that matter here are [phase advances smoothly across a changing rate] and
 * [a rate change does not jump the beat]: both pass against an integral and fail against a product,
 * which is the entire distinction.
 */
class BeatPhaseTest {

    @Before
    fun clearCache() = BeatPhase.clear()

    /** A record whose readings are [bpms], one per second from zero. */
    private fun record(id: Long, bpms: List<Double>): BpmRecord {
        val points = bpms.mapIndexed { i, bpm ->
            BpmDataPointEntity(
                dataPointId = id * 100_000 + i,
                recordOwnerId = id,
                timestamp = i * 1000L,
                bpm = bpm
            )
        }
        return BpmRecord(
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

    private fun steady(id: Long, bpm: Double, seconds: Int) = record(id, List(seconds) { bpm })

    @Test
    fun `sixty bpm is one beat a second`() {
        val r = steady(1, 60.0, 120)

        assertEquals(10.0, BeatPhase.beatsAt(r, 10_000L), 0.001)
        assertEquals(60.0, BeatPhase.beatsAt(r, 60_000L), 0.001)
    }

    @Test
    fun `a hundred and twenty bpm is two beats a second`() {
        val r = steady(2, 120.0, 120)

        assertEquals(20.0, BeatPhase.beatsAt(r, 10_000L), 0.001)
    }

    @Test
    fun `beats only ever accumulate`() {
        val r = record(3, List(60) { 60.0 + it })

        var previous = -1.0
        for (ms in 0..59_000 step 250) {
            val beats = BeatPhase.beatsAt(r, ms.toLong())
            assertTrue("beats went backwards at ${ms}ms", beats >= previous)
            previous = beats
        }
    }

    @Test
    fun `phase advances smoothly across a changing rate`() {
        // The regression. The rate climbs a beat per second, which is exactly the case the old
        // product-of-frequency-and-time handled worst.
        val r = record(4, List(120) { 80.0 + it * 0.5 })

        var previous = BeatPhase.phaseAt(r, 0L)
        var wraps = 0
        // 60fps, finer than any render, so a discontinuity has nowhere to hide.
        for (ms in 16..119_000 step 16) {
            val phase = BeatPhase.phaseAt(r, ms.toLong())
            val delta = phase - previous
            if (delta < 0f) {
                // A wrap from near 1 back to near 0 is the beat itself, and must be a wrap rather
                // than a leap: both ends have to be close to their respective edges.
                wraps++
                assertTrue("phase left mid-beat at ${ms}ms: $previous", previous > 0.55f)
                assertTrue("phase arrived mid-beat at ${ms}ms: $phase", phase < 0.45f)
            } else {
                assertTrue("phase jumped $delta at ${ms}ms", delta < 0.5f)
            }
            previous = phase
        }
        assertTrue("no beats at all", wraps > 100)
    }

    @Test
    fun `a rate change does not jump the beat`() {
        // Half an hour in, one bpm of change. Under the old scheme this moved the phase by tens of
        // whole cycles; under an integral it moves it by nothing at all.
        val steadyThen = record(5, List(1800) { if (it < 1200) 140.0 else 141.0 })

        val justBefore = BeatPhase.beatsAt(steadyThen, 1_199_900L)
        val justAfter = BeatPhase.beatsAt(steadyThen, 1_200_100L)

        // Two tenths of a second at ~140bpm is under half a beat, whatever the rate does.
        assertTrue(
            "the beat count leapt by ${justAfter - justBefore}",
            abs(justAfter - justBefore) < 0.5
        )
    }

    @Test
    fun `the beat keeps time with the rate on display`() {
        // What "properly pulses at the bpm shown" means: over a minute at 150bpm there are 150
        // beats, not approximately 150.
        val r = steady(6, 150.0, 120)

        val beatsInAMinute = BeatPhase.beatsAt(r, 90_000L) - BeatPhase.beatsAt(r, 30_000L)

        assertEquals(150.0, beatsInAMinute, 0.01)
    }

    @Test
    fun `phase stays within a beat`() {
        val r = record(7, List(60) { 70.0 + (it % 20) * 3.0 })

        for (ms in 0..59_000 step 100) {
            val phase = BeatPhase.phaseAt(r, ms.toLong())
            assertTrue("phase $phase out of range at ${ms}ms", phase >= 0f && phase < 1f)
        }
    }

    @Test
    fun `before and after a recording are still answerable`() {
        val r = steady(8, 60.0, 30)

        assertEquals(0.0, BeatPhase.beatsAt(r, -5_000L), 0.001)
        // Past the end it holds at the total rather than running on into nothing.
        val total = BeatPhase.beatsAt(r, 29_000L)
        assertEquals(total, BeatPhase.beatsAt(r, 500_000L), 0.001)
    }

    @Test
    fun `a recording too short to have a rate does not throw`() {
        val one = record(9, listOf(70.0))

        assertEquals(0.0, BeatPhase.beatsAt(one, 5_000L), 0.001)
        assertEquals(0f, BeatPhase.phaseAt(one, 5_000L), 0.001f)
    }

    @Test
    fun `the envelope snaps and falls away`() {
        // At rest, at the peak, and settled again — a heartbeat rather than a sine, which is what
        // makes consecutive beats distinguishable.
        assertEquals(0f, BeatPhase.envelope(0f), 0.001f)
        assertEquals(1f, BeatPhase.envelope(BeatPhase.ATTACK), 0.001f)
        assertEquals(0f, BeatPhase.envelope(1f), 0.001f)

        // The fall is longer than the snap, which is the asymmetry that reads as a pulse.
        assertTrue(BeatPhase.envelope(0.09f) < BeatPhase.envelope(0.18f))
        assertTrue(BeatPhase.envelope(0.5f) < BeatPhase.envelope(0.3f))
    }

    @Test
    fun `the envelope never leaves its range`() {
        for (i in -20..120) {
            val v = BeatPhase.envelope(i / 100f)
            assertTrue("envelope $v out of range at ${i / 100f}", v in 0f..1f)
        }
    }

    @Test
    fun `two wearers keep their own time`() {
        // Six rings beating in lockstep would be obviously wrong; they are separate recordings with
        // separate rates and must be separately phased.
        val slow = steady(10, 60.0, 120)
        val fast = steady(11, 180.0, 120)

        assertEquals(30.0, BeatPhase.beatsAt(slow, 30_000L), 0.001)
        assertEquals(90.0, BeatPhase.beatsAt(fast, 30_000L), 0.001)
    }

    @Test
    fun `a rewritten recording is not answered from a stale table`() {
        // Same id, different data — a split or a merge does exactly this.
        val before = steady(12, 60.0, 60)
        assertEquals(30.0, BeatPhase.beatsAt(before, 30_000L), 0.001)

        val after = steady(12, 120.0, 60)
        assertEquals(60.0, BeatPhase.beatsAt(after, 30_000L), 0.001)
    }
}
