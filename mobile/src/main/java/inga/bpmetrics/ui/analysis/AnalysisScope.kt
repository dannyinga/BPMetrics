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

    /**
     * Whatever the library's filter was set to, described by what it actually selected.
     *
     * [labels] are resolved names rather than counts. "1 tag" tells you nothing you did not
     * already know; "Artists › Subtronics" tells you what you are looking at, which is the point
     * of a header.
     */
    data class Filter(
        val filter: LibraryViewModel.FilterState,
        val labels: FilterLabels = FilterLabels()
    ) : AnalysisScope {
        override val title: String
            get() = labels.singleSubject ?: "Analysis"

        override val detail: String get() = describe(filter, labels)
    }

    /**
     * The names behind a filter's ids, resolved by whoever built the scope.
     *
     * The filter itself stores ids, which is right — a filter that stored names would break when
     * something was renamed. Names are looked up once here so the header can read like a sentence.
     */
    data class FilterLabels(
        /** Tag name to the category it belongs to, so a tag can be shown as `Category › Tag`. */
        val tags: List<Pair<String, String>> = emptyList(),
        val people: List<String> = emptyList(),
        val watches: List<String> = emptyList(),
        val events: List<String> = emptyList(),
        val groups: List<String> = emptyList()
    ) {
        /**
         * The one thing this filter is about, when there is exactly one.
         *
         * Filtering to a single tag or a single event is the common case, and the screen should be
         * named after it. Anything more than one thing has no single subject and keeps the generic
         * title with the specifics in the line below.
         */
        val singleSubject: String?
            get() {
                val named = groups.map { it } +
                    events.map { it } +
                    tags.map { (category, tag) -> "$category › $tag" } +
                    people.map { it }
                return named.singleOrNull()
            }
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
        private fun describe(
            filter: LibraryViewModel.FilterState,
            labels: FilterLabels
        ): String {
            val parts = buildList {
                labels.groups.namedOr(filter.selectedGroupIds.size, "group", "groups")
                    ?.let { add(it) }
                labels.events.namedOr(filter.selectedEventIds.size, "event", "events")
                    ?.let { add(it) }
                labels.tags
                    .map { (category, tag) -> "$category › $tag" }
                    .namedOr(filter.selectedTagIds.size, "tag", "tags")
                    ?.let { add(it) }
                labels.people.namedOr(filter.selectedPersonIds.size, "person", "people")
                    ?.let { add(it) }
                labels.watches.namedOr(filter.selectedWatchIds.size, "watch", "watches")
                    ?.let { add(it) }

                filter.dateRange?.let { (from, to) ->
                    add("${shortDate(from)} – ${shortDate(to)}")
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

        /**
         * Names them when there are few enough to read, counts them when there are not.
         *
         * Three names is a sentence; nine is a wall. The count is a fallback for the wall, not the
         * default — naming what was selected is the whole point.
         */
        private fun List<String>.namedOr(
            selectedCount: Int,
            singular: String,
            plural: String
        ): String? = when {
            selectedCount == 0 -> null
            isEmpty() -> "$selectedCount ${if (selectedCount == 1) singular else plural}"
            size <= MAX_NAMED -> joinToString(", ")
            else -> "$size $plural"
        }

        /** Beyond this, names stop being readable and a count says more. */
        private const val MAX_NAMED = 3

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
