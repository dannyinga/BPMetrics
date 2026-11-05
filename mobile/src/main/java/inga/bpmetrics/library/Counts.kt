package inga.bpmetrics.library

/**
 * One row of a grouped count: how many things point at [id].
 *
 * The management screens show a count on every row. Asking once per row, once, at start-up, read
 * the list before it had loaded and so counted nothing; one grouped query observed as a Flow
 * stays right as recordings arrive, move and go.
 */
data class CountById(val id: Long, val n: Int)

/** [CountById] for things keyed by a string, which is how watches are known. */
data class CountByKey(val key: String, val n: Int)

/** When one watch's recordings start and end, by its id. */
data class WatchSpan(val key: String, val earliest: Long, val latest: Long)
