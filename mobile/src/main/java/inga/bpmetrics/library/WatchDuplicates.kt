package inga.bpmetrics.library

/**
 * Which watch entries are probably one watch under two ids (Audit #39).
 *
 * Duplicates already in a library came from reinstalls before the phone matched on the node, and
 * nothing about them proves they are one watch. What suggests it: the same model, and recordings
 * that never overlap in time. One watch can't record twice at once; two watches of the same model
 * used at different times would look identical, which is why this only ever suggests, and a person
 * confirms the merge or says "not the same".
 *
 * Spans come from the recordings rather than the registry's first/last seen dates, which an older
 * restore stamped with the time of the restore.
 */
object WatchDuplicates {

    /** The key for a pair someone has said are different watches, in either order. */
    fun pairKey(a: String, b: String): String = if (a < b) "$a|$b" else "$b|$a"

    /**
     * For each watch, the others that look like the same one.
     *
     * @param spans when each watch's recordings start and end, by watch id. A watch with none has
     *   nothing to compare and is never suggested.
     * @param distinct pairs already marked as different watches, as [pairKey]s.
     */
    fun likelySame(
        watches: List<WatchEntity>,
        spans: Map<String, LongRange>,
        distinct: Set<String> = emptySet()
    ): Map<String, List<WatchEntity>> {
        val withSpans = watches.filter { spans[it.watchId] != null && it.lastKnownModel.isNotBlank() }
        return withSpans.associate { watch ->
            val span = spans.getValue(watch.watchId)
            watch.watchId to withSpans.filter { other ->
                other.watchId != watch.watchId &&
                    other.lastKnownModel.equals(watch.lastKnownModel, ignoreCase = true) &&
                    pairKey(watch.watchId, other.watchId) !in distinct &&
                    !overlaps(span, spans.getValue(other.watchId))
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun overlaps(a: LongRange, b: LongRange) = a.first <= b.last && b.first <= a.last
}
