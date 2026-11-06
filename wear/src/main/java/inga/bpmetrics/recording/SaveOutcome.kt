package inga.bpmetrics.recording

/**
 * How the last recording ended: saved, or not saved and why.
 *
 * Stopping used to end in silence either way. A recording with no readings was dropped without a
 * word, an exception was logged and swallowed, and a stop the sensor never confirmed was reset
 * after 30 seconds, deleting the readings (Audit #12). Each of those now says so on the watch.
 */
sealed interface SaveOutcome {

    /** What the watch says about it. */
    val message: String

    data class Saved(val readings: Int, val durationMs: Long) : SaveOutcome {
        override val message: String
            get() = "Saved · ${formatDuration(durationMs)}, " +
                if (readings == 1) "1 reading" else "$readings readings"
    }

    data class NotSaved(val reason: String) : SaveOutcome {
        override val message: String get() = "Not saved: $reason"
    }

    companion object {
        /**
         * The outcome of finishing a recording that kept [stored] readings, [valid] of them in
         * range, over [durationMs].
         */
        fun of(stored: Int, valid: Int, durationMs: Long): SaveOutcome = when {
            valid > 0 -> Saved(valid, durationMs)
            stored > 0 -> NotSaved(
                if (stored == 1) "its one reading was out of range."
                else "all $stored readings were out of range."
            )
            else -> NotSaved("no heart rate was measured. Check the watch is snug on your wrist.")
        }

        /** "1h 2m", "12m 5s", "40s". */
        fun formatDuration(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s"
            }
        }
    }
}
