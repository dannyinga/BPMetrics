package inga.bpmetrics.library

/**
 * A set of unfiled recordings that look like they were made at the same occasion.
 */
data class EventSuggestion(
    val records: List<BpmRecord>,
    val span: TimeSpan
) {
    val size: Int get() = records.size
}

/**
 * Groups unfiled recordings into occasions by when they happened.
 *
 * Recordings join the same cluster when they overlap, or when the gap between one ending and the
 * next starting is under [maxGapMs]. Handing three watches out at a party produces three recordings
 * that start within a minute of each other and run together; the next party is hours away. Time is
 * the only signal available — the app is never told what an occasion was.
 *
 * This suggests and never files. A cluster of two recordings an hour apart on a quiet afternoon is
 * as likely to be two unrelated sessions as one event, and silently merging them would leave the
 * user undoing work they did not ask for.
 *
 * Clusters of one are dropped: "create an event from this 1 recording" is not a suggestion, it is
 * the ordinary create flow with extra steps.
 *
 * @param records Unfiled recordings, in any order.
 * @param maxGapMs How long a quiet stretch can be before it starts a new occasion. Default 30 min.
 */
fun suggestEvents(
    records: List<BpmRecord>,
    maxGapMs: Long = DEFAULT_EVENT_GAP_MS
): List<EventSuggestion> {
    if (records.size < 2) return emptyList()

    val sorted = records.sortedBy { it.metadata.startTime }
    val clusters = mutableListOf<MutableList<BpmRecord>>()
    // Tracked separately from the cluster's last record because the *latest* end is what a new
    // recording is adjacent to. A long recording followed by a short one inside it would otherwise
    // make the cluster look like it ended early and split the rest off.
    var clusterEnd = Long.MIN_VALUE

    sorted.forEach { record ->
        val start = record.metadata.startTime
        if (clusters.isEmpty() || start - clusterEnd > maxGapMs) {
            clusters += mutableListOf(record)
            clusterEnd = record.metadata.endTime
        } else {
            clusters.last() += record
            clusterEnd = maxOf(clusterEnd, record.metadata.endTime)
        }
    }

    return clusters
        .filter { it.size >= 2 }
        .map { cluster ->
            EventSuggestion(
                records = cluster,
                span = TimeSpan(
                    cluster.minOf { it.metadata.startTime },
                    cluster.maxOf { it.metadata.endTime }
                )
            )
        }
}

/** Half an hour: long enough to cover a break between sets, short enough to separate two evenings. */
const val DEFAULT_EVENT_GAP_MS = 30 * 60 * 1000L
