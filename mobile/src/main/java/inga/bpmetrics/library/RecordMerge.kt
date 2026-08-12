package inga.bpmetrics.library

/**
 * Joining several stretches of one person's evening back into one recording.
 *
 * The opposite of splitting, and needed for the same reason: a watch that dropped out mid-set
 * leaves two recordings of one thing, and from then on every count, chart and export treats them
 * as two.
 *
 * Kept pure so the arithmetic can be tested without a database — and the arithmetic is the part
 * that matters, because getting it wrong produces a recording that looks entirely plausible and
 * describes something that never happened.
 */
object RecordMerge {

    /** Timestamps rebased onto a shared origin, ready to be saved as one recording. */
    data class Merged(
        val startTime: Long,
        val endTime: Long,
        val points: List<Point>
    ) {
        data class Point(val timestampMs: Long, val bpm: Double)

        val durationMs: Long get() = endTime - startTime
    }

    /**
     * Whether these recordings can honestly become one.
     *
     * The same person, and more than one of them. Two people's readings on a single timeline is a
     * *concurrent analysis*, which the app already does properly — merging them would produce a
     * curve that jumps between two hearts and claims to be one, and nothing downstream could
     * detect that it had. Recordings attributed to nobody are refused for the same reason: without
     * a person there is no evidence they are the same heart, and joining them would be a guess
     * presented as data.
     */
    fun canMerge(records: List<BpmRecordEntity>): Boolean {
        if (records.size < 2) return false
        val people = records.map { it.personId }
        return people.none { it == null } && people.distinct().size == 1
    }

    /** Why a merge is being refused, for the UI to say rather than just disabling a button. */
    fun refusal(records: List<BpmRecordEntity>): String? = when {
        records.size < 2 -> "Select more than one recording."
        records.any { it.personId == null } ->
            "Every recording has to be attributed to someone first."
        records.map { it.personId }.distinct().size > 1 ->
            "These are different people. Compare them with a same-time analysis instead."
        else -> null
    }

    /**
     * Lays the recordings end to end on one clock.
     *
     * Clock order and real distances, not concatenation. These are stretches of one evening, and
     * the gap between two of them happened — sliding the second up against the first would invent
     * a continuity that never existed and quietly shorten the recording.
     *
     * @return null when there is nothing to draw, which is a refusal rather than an empty result.
     */
    fun combine(records: List<BpmRecordWithPoints>): Merged? {
        val withData = records.filter { it.dataPoints.isNotEmpty() }
        if (withData.isEmpty()) return null

        val origin = withData.minOf { it.metadata.startTime }
        val points = withData
            .flatMap { record ->
                record.dataPoints.map { point ->
                    // Each reading keeps the instant it was actually taken at.
                    Merged.Point(record.metadata.startTime + point.timestamp - origin, point.bpm)
                }
            }
            .sortedBy { it.timestampMs }

        val end = withData.maxOf { record ->
            record.metadata.startTime +
                maxOf(record.metadata.durationMs, record.dataPoints.last().timestamp)
        }

        return Merged(startTime = origin, endTime = end, points = points)
    }

    /**
     * How much of the merged span has no readings in it.
     *
     * Worth showing before the merge rather than after: joining two sets an hour apart is a
     * legitimate thing to want, and it is also a mistake someone might be about to make.
     */
    /**
     * The time between the parts that no recording covers.
     *
     * Metadata only, so the preview dialog can show it without loading every reading of every
     * recording being merged — the readings are what the merge itself needs, not the description
     * of it. Span minus what the parts cover, which is the same answer [combine] arrives at.
     *
     * A recording with no readings at all counts toward the span here and contributes nothing to
     * the merge, so the two differ in that one degenerate case. It is a preview.
     */
    fun gapMs(records: List<BpmRecordEntity>): Long {
        if (records.size < 2) return 0L
        val span = records.maxOf { it.startTime + it.durationMs } - records.minOf { it.startTime }
        val covered = records.sumOf { it.durationMs }
        return (span - covered).coerceAtLeast(0L)
    }
}
