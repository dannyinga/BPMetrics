package inga.bpmetrics.library

/**
 * Cutting a shorter recording out of a longer one.
 *
 * The opposite of [RecordMerge], and split out here for the same reason: the arithmetic is the part
 * that matters, because getting it wrong produces a recording that looks entirely plausible and
 * describes something that never happened.
 *
 * It also settles an older problem. Splitting had two ways in — the chart's own button and the
 * typed dialog behind the overflow — and each carried its own idea of what a valid range was and
 * what a split produced. Two implementations of one act is two answers to one question; this is the
 * one both now ask.
 *
 * Everything here is in **offsets from the recording's start**, which is how readings are stored.
 * Wall-clock instants belong to the chart and to whoever is typing, and are converted at the edge.
 */
object RecordSplit {

    /** A reading in the new recording, rebased onto its own start. */
    data class Point(val timestampMs: Long, val bpm: Double)

    /** The readings of the new recording, and the span they were taken from. */
    data class Slice(
        /** Wall clock, so the new recording sits where it actually happened. */
        val startTime: Long,
        val endTime: Long,
        val points: List<Point>
    ) {
        val durationMs: Long get() = endTime - startTime
    }

    /**
     * Why this range cannot become a recording, for a dialog to say rather than just refusing.
     *
     * Bounds checked against the recording's *duration* rather than its last reading: a recording
     * that stopped measuring four minutes before it ended still ran for those four minutes, and a
     * range ending inside them is a legitimate thing to ask for.
     *
     * @param fromMs offset from the recording's start.
     * @param toMs offset from the recording's start.
     */
    fun refusal(record: BpmRecordWithPoints, fromMs: Long, toMs: Long): String? = when {
        toMs <= fromMs -> "The end has to come after the start."
        fromMs < 0L || toMs > record.metadata.durationMs ->
            "That range falls outside this recording."
        readingsIn(record, fromMs, toMs) == 0 -> "Nothing was recorded in that range."
        else -> null
    }

    /** How many readings the split would contain, so the dialog can say so before it happens. */
    fun readingsIn(record: BpmRecordWithPoints, fromMs: Long, toMs: Long): Int =
        record.dataPoints.count { it.timestamp in fromMs..toMs }

    /**
     * The readings between two offsets, rebased onto the new recording's own start.
     *
     * Rebasing is what makes the result a recording rather than a fragment that begins forty
     * minutes in — every chart, export and duration in the app reads a timestamp as time since the
     * recording started.
     *
     * @return null when the range holds no readings, which is a refusal rather than an empty
     *   recording: a recording with no readings draws a flat line and reports zero, which looks
     *   like an answer.
     */
    fun slice(record: BpmRecordWithPoints, fromMs: Long, toMs: Long): Slice? {
        if (toMs <= fromMs) return null

        val points = record.dataPoints
            .filter { it.timestamp in fromMs..toMs }
            .sortedBy { it.timestamp }
            .map { Point(it.timestamp - fromMs, it.bpm) }
        if (points.isEmpty()) return null

        val base = record.metadata.startTime
        return Slice(startTime = base + fromMs, endTime = base + toMs, points = points)
    }
}
