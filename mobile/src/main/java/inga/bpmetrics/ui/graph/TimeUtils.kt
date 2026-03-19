package inga.bpmetrics.ui.graph

/**
 * Utility functions for formatting and parsing time strings used in the graph.
 */
object TimeUtils {
    /**
     * Formats milliseconds into a string: "H:MM:SS" if hours exist, otherwise "MM:SS".
     */
    fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /**
     * Parses a "H:M:S", "M:S", or "S" string into milliseconds.
     * Returns null if the input is invalid.
     */
    fun parseToMs(input: String): Long? {
        val parts = input.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            1 -> parts[0] * 1000L
            else -> null
        }
    }
}
