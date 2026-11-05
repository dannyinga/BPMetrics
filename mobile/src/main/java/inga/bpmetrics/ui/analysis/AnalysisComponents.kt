package inga.bpmetrics.ui.analysis

/**
 * "1h 24m", "8m 12s", "40s" — the largest two units that say anything.
 *
 * Lived on the event screen until Sprint 5 folded it away. Every subject's page states a duration,
 * so it belongs with the components they share rather than with whichever one happened to need it
 * first.
 */
internal fun shortDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
