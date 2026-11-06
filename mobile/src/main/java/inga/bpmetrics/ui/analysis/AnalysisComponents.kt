package inga.bpmetrics.ui.analysis

/**
 * "3d 3h", "1h 24m", "8m 12s", "40s" — the largest two units that say anything.
 *
 * Lived on the event screen until Sprint 5 folded it away. Every subject's page states a duration,
 * so it belongs with the components they share rather than with whichever one happened to need it
 * first.
 *
 * Days from a day up (S15.4): a three-day festival read "75h 16m", which is a number to work out
 * rather than a length anyone says.
 */
internal fun shortDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val days = totalSeconds / 86_400
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
