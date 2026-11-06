package inga.bpmetrics.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the watch says when a recording stops: saved, or not saved and why. */
class SaveOutcomeTest {

    @Test
    fun `a recording with readings is saved, and says how long and how many`() {
        val outcome = SaveOutcome.of(stored = 2_512, valid = 2_512, durationMs = 42 * 60_000L + 5_000)
        assertEquals(SaveOutcome.Saved(2_512, 42 * 60_000L + 5_000), outcome)
        assertEquals("Saved · 42m 5s, 2512 readings", outcome.message)
    }

    @Test
    fun `out-of-range readings are left out, not the recording`() {
        assertEquals(SaveOutcome.Saved(9, 60_000), SaveOutcome.of(stored = 10, valid = 9, durationMs = 60_000))
    }

    @Test
    fun `nothing measured is said, not dropped in silence`() {
        assertEquals(
            "Not saved: no heart rate was measured. Check the watch is snug on your wrist.",
            SaveOutcome.of(stored = 0, valid = 0, durationMs = 30_000).message
        )
    }

    @Test
    fun `only out-of-range readings says so`() {
        assertEquals(
            "Not saved: all 3 readings were out of range.",
            SaveOutcome.of(stored = 3, valid = 0, durationMs = 30_000).message
        )
    }

    @Test
    fun `durations read the way the timer does`() {
        assertEquals("40s", SaveOutcome.formatDuration(40_000))
        assertEquals("1h 2m", SaveOutcome.formatDuration(62 * 60_000L + 30_000))
        assertEquals("1 reading", SaveOutcome.Saved(1, 1_000).message.substringAfter(", "))
    }
}
