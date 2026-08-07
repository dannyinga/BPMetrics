package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.EventGroupEntity
import inga.bpmetrics.ui.library.LibraryViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What a set of recordings is being analysed *as*.
 *
 * A filter and a group are two ways of naming the same kind of thing — "these recordings, together"
 * — so they produce the same analysis and differ only in how the top of it reads. Modelling the
 * difference as a scope rather than as two screens is what keeps the two from drifting apart.
 */
sealed interface AnalysisScope {

    /** What the screen is called. */
    val title: String

    /** One line under the title saying what was included. Empty when there is nothing to add. */
    val detail: String

    /** A group of events. */
    data class Group(
        val group: EventGroupEntity,
        val eventCount: Int
    ) : AnalysisScope {
        override val title: String get() = group.displayName
        override val detail: String
            get() = "$eventCount event${if (eventCount == 1) "" else "s"}"
    }

    /** Whatever the library's filter was set to. */
    data class Filter(
        val filter: LibraryViewModel.FilterState
    ) : AnalysisScope {
        override val title: String get() = "Analysis"
        override val detail: String get() = describe(filter)
    }

    /** A stored analysis, which no longer knows what produced it. */
    data class Saved(val name: String) : AnalysisScope {
        override val title: String get() = name
        override val detail: String get() = "Saved — these numbers are frozen"
    }

    /** Placeholder while the real scope loads. */
    data object Unknown : AnalysisScope {
        override val title: String get() = "Analysis"
        override val detail: String get() = ""
    }

    companion object {
        /**
         * Says what a filter actually restricts, and nothing else.
         *
         * The screen used to print three lines reading "Categories: All / Tags: All / Date Range:
         * All Time" whatever was selected — three lines of vertical space to say nothing. Naming
         * only the active restrictions means the line is short when the scope is broad and
         * informative exactly when it is not.
         */
        private fun describe(filter: LibraryViewModel.FilterState): String {
            val parts = buildList {
                filter.dateRange?.let { (from, to) ->
                    add("${shortDate(from)} – ${shortDate(to)}")
                }
                if (filter.selectedPersonIds.isNotEmpty()) {
                    add("${filter.selectedPersonIds.size} " +
                        if (filter.selectedPersonIds.size == 1) "person" else "people")
                }
                if (filter.selectedWatchIds.isNotEmpty()) {
                    add("${filter.selectedWatchIds.size} watch" +
                        if (filter.selectedWatchIds.size == 1) "" else "es")
                }
                if (filter.selectedTagIds.isNotEmpty()) {
                    add("${filter.selectedTagIds.size} tag" +
                        if (filter.selectedTagIds.size == 1) "" else "s")
                }
                if (filter.minBpm > 0.0 || filter.maxBpm != null) {
                    add(
                        "${filter.minBpm.toInt()}–" +
                            (filter.maxBpm?.toInt()?.toString() ?: "∞") + " bpm"
                    )
                }
            }
            return if (parts.isEmpty()) "Everything in your library" else parts.joinToString(" · ")
        }

        private fun shortDate(ms: Long): String =
            SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))
    }
}

/**
 * The span a set of analysed recordings covers, phrased the way someone would say it.
 *
 * Derived from the records rather than from the scope, so it describes what is actually there — a
 * group whose events were all cancelled but one reads as that one day, not as the range someone
 * once had in mind.
 */
fun dateRangeText(records: List<AnalysisRecord>): String {
    if (records.isEmpty()) return ""

    val first = records.minOf { it.date }
    val last = records.maxOf { it.date }

    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val firstCal = Calendar.getInstance().apply { timeInMillis = first }
    val lastCal = Calendar.getInstance().apply { timeInMillis = last }

    val pattern = if (firstCal.get(Calendar.YEAR) == thisYear) "d MMM" else "d MMM yyyy"
    val format = SimpleDateFormat(pattern, Locale.getDefault())

    val sameDay = firstCal.get(Calendar.YEAR) == lastCal.get(Calendar.YEAR) &&
        firstCal.get(Calendar.DAY_OF_YEAR) == lastCal.get(Calendar.DAY_OF_YEAR)

    return if (sameDay) format.format(Date(first))
    else "${format.format(Date(first))} – ${format.format(Date(last))}"
}
