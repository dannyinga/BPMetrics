package inga.bpmetrics.ui

/** How long a recording has run, as the watch shows it. */
object Elapsed {

    /**
     * "5s", "12m 5s", "1h 0m 5s".
     *
     * Every unit from the largest down. Minutes used to be left out when they were zero, so an
     * hour and five seconds read "1h 5s", which looks like a typo for 1h 5m (Audit §3 Watch).
     */
    fun format(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
