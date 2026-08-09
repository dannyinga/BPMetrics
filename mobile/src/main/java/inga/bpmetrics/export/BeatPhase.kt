package inga.bpmetrics.export

import inga.bpmetrics.library.BpmRecordWithPoints
import kotlin.math.floor

/**
 * Where a recording is within a heartbeat at a given instant.
 *
 * Exists because the obvious way to animate a pulse is wrong. Multiplying the current rate by the
 * elapsed time — `sin(t × bpm)` — gives a phase that jumps every time the rate changes, by `t × Δf`
 * radians. Ten minutes into a recording a tick from 140 to 141 bpm moves that argument by some ten
 * whole cycles, so the beat lands somewhere unrelated to where it was on the previous frame. At
 * thirty frames a second against a continuously interpolated rate, the result is not a pulse; it is
 * noise that happens to be bounded.
 *
 * The phase is therefore the **integral** of the rate over time. `∫f` is continuous when `f` moves,
 * and its instantaneous frequency is exactly `f` — so the ring beats at the number printed beside
 * it and never skips.
 *
 * Split out of the renderer so it can be checked without a device. The glitch it fixes was only
 * ever visible by exporting a video and watching it, which is the worst possible feedback loop for
 * a piece of arithmetic.
 */
internal object BeatPhase {

    /** How much of a beat is the snap. The rest is the fall away. */
    const val ATTACK = 0.18f

    /**
     * Beats elapsed from the start of [record] up to [atMs], as a whole number plus a fraction.
     *
     * Trapezoidal, matching the linear interpolation the rest of the renderer reads the rate with —
     * so the pulse and the curve are describing the same recording.
     */
    fun beatsAt(record: BpmRecordWithPoints, atMs: Long): Double {
        val table = tableFor(record) ?: return 0.0
        val times = table.timestamps
        if (atMs <= times.first()) return 0.0
        if (atMs >= times.last()) return table.beats.last()

        // The last sample at or before atMs.
        var low = 0
        var high = times.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (times[mid] <= atMs) low = mid else high = mid - 1
        }
        if (low >= times.size - 1) return table.beats.last()

        val points = record.dataPoints
        val spanMs = (times[low + 1] - times[low]).toDouble()
        if (spanMs <= 0.0) return table.beats[low]

        val into = (atMs - times[low]).toDouble()
        val rateAtLow = points[low].bpm
        val rateHere = rateAtLow + (points[low + 1].bpm - rateAtLow) * (into / spanMs)
        return table.beats[low] + (rateAtLow + rateHere) / 2.0 * (into / 60_000.0)
    }

    /** How far through the current beat, 0..1. */
    fun phaseAt(record: BpmRecordWithPoints, atMs: Long): Float {
        val beats = beatsAt(record, atMs)
        return (beats - floor(beats)).toFloat()
    }

    /**
     * A heartbeat rather than a sine wave.
     *
     * A sine spends half its cycle growing and half shrinking, which reads as breathing. A heart
     * snaps and relaxes — and because this does too, consecutive beats are distinguishable, which
     * is what makes a pulse look like it is keeping time rather than merely wobbling.
     */
    fun envelope(phase: Float): Float {
        val p = phase.coerceIn(0f, 1f)
        return if (p < ATTACK) {
            p / ATTACK
        } else {
            val decayed = (p - ATTACK) / (1f - ATTACK)
            (1f - decayed) * (1f - decayed)
        }
    }

    /** Drops every cached table. For tests, and for anything that rewrites a recording wholesale. */
    fun clear() = synchronized(tables) { tables.clear() }

    private class Table(
        val fingerprint: Int,
        val timestamps: LongArray,
        /** Beats elapsed from the first sample up to each timestamp. */
        val beats: DoubleArray
    )

    /**
     * Cumulative tables, so a frame does not re-integrate a two-hour recording.
     *
     * A plain access-ordered map rather than `android.util.LruCache`: that class is a stub in unit
     * tests and throws, which would make the arithmetic here untestable — and untestable arithmetic
     * is exactly how the bug it replaces survived.
     */
    private val tables = object : LinkedHashMap<Long, Table>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Table>?): Boolean =
            size > MAX_CACHED
    }

    private const val MAX_CACHED = 8

    private fun tableFor(record: BpmRecordWithPoints): Table? {
        val points = record.dataPoints
        if (points.size < 2) return null

        val id = record.metadata.recordId
        // Timings *and* readings. Keying on the count and the endpoints' timestamps alone looked
        // sufficient and was not: a recording whose values change while its timing does not — the
        // same watch re-read, an import correcting a run of samples — has an identical key, and
        // would go on pulsing to the rate it used to have. Cheap to include the readings, and the
        // failure without them is silent.
        val middle = points[points.size / 2]
        val fingerprint = points.size * 31 +
            points.first().timestamp.hashCode() * 7 +
            points.last().timestamp.hashCode() +
            points.first().bpm.hashCode() * 13 +
            points.last().bpm.hashCode() * 17 +
            middle.bpm.hashCode() * 19

        synchronized(tables) {
            tables[id]?.takeIf { it.fingerprint == fingerprint }?.let { return it }
        }

        val timestamps = LongArray(points.size)
        val beats = DoubleArray(points.size)
        var running = 0.0
        timestamps[0] = points[0].timestamp
        for (i in 1 until points.size) {
            val dtMinutes = (points[i].timestamp - points[i - 1].timestamp) / 60_000.0
            // Trapezoid: the average of the two readings across the gap between them.
            running += (points[i].bpm + points[i - 1].bpm) / 2.0 * dtMinutes
            timestamps[i] = points[i].timestamp
            beats[i] = running
        }

        val table = Table(fingerprint, timestamps, beats)
        synchronized(tables) { tables[id] = table }
        return table
    }
}
