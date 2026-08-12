package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two numbers that moved out of the readings.
 *
 * They are stored at ingest so a summary needs no readings at all — see §9 of the product doc. That
 * only holds if what gets stored is what the old walk produced, so these pin the arithmetic down
 * rather than the plumbing.
 */
class DerivedFiguresTest {

    private val start = 1_700_000_000_000L

    private fun points(vararg at: Pair<Long, Double>) = at.map { (ts, bpm) ->
        BpmDataPointEntity(recordOwnerId = 1, timestamp = ts, bpm = bpm)
    }

    // --- Active duration ---

    @Test
    fun `a steady recording measures its whole length`() {
        val readings = points(0L to 80.0, 1_000L to 82.0, 2_000L to 81.0)

        assertEquals(3_000L, DerivedFigures.activeDurationOf(readings, durationMs = 3_000L))
    }

    @Test
    fun `a dropout contributes nothing`() {
        // The watch stopped reporting for a minute. That minute was not measured, and counting it
        // would claim a heart rate nobody recorded.
        val readings = points(0L to 80.0, 1_000L to 82.0, 61_000L to 84.0)

        assertEquals(2_000L, DerivedFigures.activeDurationOf(readings, durationMs = 62_000L))
    }

    @Test
    fun `the last reading is closed by the recording's length`() {
        // Otherwise the final interval is silently dropped and every recording is one sample short.
        val readings = points(0L to 80.0)

        assertEquals(5_000L, DerivedFigures.activeDurationOf(readings, durationMs = 5_000L))
    }

    @Test
    fun `no readings is zero, not an error`() {
        assertEquals(0L, DerivedFigures.activeDurationOf(emptyList(), durationMs = 5_000L))
    }

    @Test
    fun `the stored figure is what the old walk produced`() {
        // The guarantee that makes storing it safe. BpmRecordWithPoints computes from the readings
        // in hand; the column holds what DerivedFigures wrote. Both call the same function, and
        // this is the assertion that says so.
        val readings = points(0L to 80.0, 1_000L to 82.0, 61_000L to 84.0)
        val figures = DerivedFigures.of(readings, start, durationMs = 62_000L)

        val record = BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = 1,
                title = "Set",
                date = start,
                startTime = start,
                endTime = start + 62_000L,
                durationMs = 62_000L
            ),
            dataPoints = readings,
            minDataPoint = null,
            maxDataPoint = null
        )

        assertEquals(record.calculateActiveDurationMs(), figures.activeDurationMs)
    }

    // --- Bands ---

    @Test
    fun `time lands in the band the earlier reading falls in`() {
        val readings = points(0L to 90.0, 10_000L to 170.0, 20_000L to 175.0)
        val figures = DerivedFigures.of(readings, start, durationMs = 30_000L)
        val bands = DerivedFigures.zoneTimes(figures.zonesEncoded).associate { it.zone.name to it.durationMs }

        assertEquals("ten seconds at 90 is resting", 10_000L, bands["Resting"])
        assertEquals("then ten at 170", 10_000L, bands["Peak"])
        assertEquals(0L, bands["Light"])
    }

    @Test
    fun `the bands stop at the last reading, the duration does not`() {
        // A real and long-standing difference, pinned here rather than papered over. The bands are
        // built from consecutive *pairs*, so the final reading has no successor and contributes no
        // time; the active duration closes that last interval with the recording's own length.
        // They therefore differ by exactly that interval.
        //
        // Neither is wrong — a band cannot say how long a reading held without guessing, and a
        // duration can, because the recording knows when it stopped. Storing both preserves the
        // difference exactly as it was; changing it would move every zone breakdown in the app.
        val readings = points(0L to 90.0, 1_000L to 140.0, 61_000L to 175.0, 62_000L to 176.0)
        val figures = DerivedFigures.of(readings, start, durationMs = 63_000L)
        val banded = DerivedFigures.zoneTimes(figures.zonesEncoded).sumOf { it.durationMs }

        // Measured: 0→1s, 61→62s, and 62s→the end at 63s. The 60s hole is a dropout and counts
        // for neither. The bands see only the first two, because the last reading has no successor
        // to pair with — so they come up exactly one interval short.
        assertEquals(3_000L, figures.activeDurationMs)
        assertEquals("the final second has no pair to close it", 2_000L, banded)
    }

    @Test
    fun `shares are recomputed rather than stored`() {
        val readings = points(0L to 90.0, 10_000L to 170.0)
        val figures = DerivedFigures.of(readings, start, durationMs = 20_000L)
        val bands = DerivedFigures.zoneTimes(figures.zonesEncoded)

        assertEquals(1.0f, bands.sumOf { it.share.toDouble() }.toFloat(), 0.0001f)
    }

    // --- The encoding, which two tables share ---

    @Test
    fun `bands survive the round trip`() {
        val original = listOf(SnapshotZone("Resting", 1_000L), SnapshotZone("Peak", 2_000L))

        assertEquals(original, DerivedFigures.decodeZones(DerivedFigures.encodeZones(original)))
    }

    @Test
    fun `nothing stored reads as every band at zero`() {
        // What a row looks like between migrating and the backfill reaching it. Zero across the
        // board is visibly unfinished; a missing band list would be a crash on first draw.
        val bands = DerivedFigures.zoneTimes("")

        assertEquals(BpmZones.DEFAULT.size, bands.size)
        assertTrue(bands.all { it.durationMs == 0L && it.share == 0f })
    }

    @Test
    fun `a band the app no longer defines is dropped`() {
        // Renaming or removing a band must not leave time filed under a heading nothing else uses.
        val bands = DerivedFigures.zoneTimes("Resting:1000\nInvented:9999")

        assertEquals(BpmZones.DEFAULT.map { it.name }, bands.map { it.zone.name })
        assertEquals(1_000L, bands.sumOf { it.durationMs })
    }

    @Test
    fun `an unreadable line is skipped rather than throwing`() {
        val bands = DerivedFigures.zoneTimes("Resting:1000\nnonsense\nPeak:notanumber")

        assertEquals(1_000L, bands.sumOf { it.durationMs })
    }

    @Test
    fun `a recording with no readings encodes as nothing`() {
        val figures = DerivedFigures.of(emptyList(), start, durationMs = 5_000L)

        assertEquals(0L, figures.activeDurationMs)
        assertEquals("", figures.zonesEncoded)
    }
}
