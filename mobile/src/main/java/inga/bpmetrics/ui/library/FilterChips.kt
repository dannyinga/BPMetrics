package inga.bpmetrics.ui.library

import inga.bpmetrics.library.FilterState
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.LocationEntity
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.TagEntity
import inga.bpmetrics.library.WatchEntity

/** Which dimension a chip narrows. Also what an "add" menu offers. */
enum class FilterDimension(val label: String) {
    PERSON("Person"),
    TAG("Tag"),
    EVENT("Event"),
    COLLECTION("Collection"),
    LOCATION("Location"),
    EVENT_TYPE("Event type"),
    WATCH("Watch"),
    DATE("When"),
    RATE("Rate")
}

/**
 * One active narrowing, as it reads on screen.
 *
 * @property label What the chip says. A resolved name, never a count: "1 tag" tells you nothing you
 * did not already know, and "Character › Hulk" tells you what you are looking at.
 * @property colorArgb A person's colour, so a chip reads the way that person reads everywhere else.
 */
data class FilterChip(
    val dimension: FilterDimension,
    val id: String,
    val label: String,
    val colorArgb: Int? = null
)

/**
 * Turning a filter into the sentence it is.
 *
 * The old filter was a dialog with a section per dimension, which made people think in the app's
 * schema rather than in their own question — "Kyle at Coachella in the rain" was spread across three
 * collapsed sections and invisible once the dialog closed. As chips it is one line you can read, and
 * removing a term is one tap on the term itself.
 *
 * Pure, and the only thing that describes a filter. Two places rendering "what is currently applied"
 * is two places that can disagree about it.
 */
object FilterChips {

    /**
     * Every active narrowing, in a stable order.
     *
     * Ordered by dimension rather than by when each was added, so the same filter always reads the
     * same way — a bar that reshuffles as you add terms is one nobody can scan.
     */
    fun of(
        filter: FilterState,
        people: List<PersonEntity> = emptyList(),
        tags: List<TagEntity> = emptyList(),
        categories: List<CategoryEntity> = emptyList(),
        events: List<EventEntity> = emptyList(),
        locations: List<LocationEntity> = emptyList(),
        watches: List<WatchEntity> = emptyList(),
        formatDate: (Long) -> String = { it.toString() }
    ): List<FilterChip> {
        val chips = mutableListOf<FilterChip>()
        val categoryNames = categories.associate { it.categoryId to it.name }

        people.filter { it.personId in filter.selectedPersonIds }.forEach {
            chips += FilterChip(FilterDimension.PERSON, "person-${it.personId}", it.name, it.colorArgb)
        }

        tags.filter { it.tagId in filter.selectedTagIds }.forEach { tag ->
            // Qualified by its axis, because "Hulk" alone does not say what is being compared and
            // two categories can hold the same word.
            val axis = categoryNames[tag.parentCategoryId]
            chips += FilterChip(
                FilterDimension.TAG,
                "tag-${tag.tagId}",
                axis?.let { "$it › ${tag.name}" } ?: tag.name
            )
        }

        events.filter { it.eventId in filter.selectedEventIds }.forEach {
            chips += FilterChip(FilterDimension.EVENT, "event-${it.eventId}", it.displayName)
        }

        events.filter { it.eventId in filter.selectedGroupIds }.forEach {
            chips += FilterChip(FilterDimension.COLLECTION, "group-${it.eventId}", it.displayName)
        }

        locations.filter { it.locationId in filter.selectedLocationIds }.forEach {
            chips += FilterChip(FilterDimension.LOCATION, "place-${it.locationId}", it.displayName)
        }

        // From the filter itself rather than from a registry: an event type is a string somebody
        // typed, so the set of them is whatever is in use, and the chip is the value.
        filter.selectedEventTypes.sorted().forEach {
            chips += FilterChip(FilterDimension.EVENT_TYPE, "type-$it", it)
        }

        watches.filter { it.watchId in filter.selectedWatchIds }.forEach {
            chips += FilterChip(FilterDimension.WATCH, "watch-${it.watchId}", it.displayName)
        }

        filter.dateRange?.let { (start, end) ->
            chips += FilterChip(
                FilterDimension.DATE,
                "date",
                "${formatDate(start)} – ${formatDate(end)}"
            )
        }

        // Only when it actually narrows. A minimum of zero is the default and describes nothing.
        if (filter.minBpm > 0.0 || filter.maxBpm != null) {
            chips += FilterChip(
                FilterDimension.RATE,
                "rate",
                buildString {
                    append("${filter.minBpm.toInt()}")
                    append("–")
                    append(filter.maxBpm?.toInt()?.toString() ?: "max")
                    append(" bpm")
                }
            )
        }

        return chips
    }

    /**
     * The filter with one chip taken out.
     *
     * By id rather than by index, because the list is rebuilt on every change and an index would
     * remove whatever happened to slide into that position.
     */
    fun without(filter: FilterState, chip: FilterChip): FilterState =
        when (chip.dimension) {
            FilterDimension.PERSON -> filter.copy(
                selectedPersonIds = filter.selectedPersonIds - chip.numericId()
            )
            FilterDimension.TAG -> filter.copy(
                selectedTagIds = filter.selectedTagIds - chip.numericId()
            )
            FilterDimension.EVENT -> filter.copy(
                selectedEventIds = filter.selectedEventIds - chip.numericId()
            )
            FilterDimension.COLLECTION -> filter.copy(
                selectedGroupIds = filter.selectedGroupIds - chip.numericId()
            )
            FilterDimension.LOCATION -> filter.copy(
                selectedLocationIds = filter.selectedLocationIds - chip.numericId()
            )
            FilterDimension.EVENT_TYPE -> filter.copy(
                selectedEventTypes = filter.selectedEventTypes - chip.id.removePrefix("type-")
            )
            FilterDimension.WATCH -> filter.copy(
                selectedWatchIds = filter.selectedWatchIds - chip.id.removePrefix("watch-")
            )
            FilterDimension.DATE -> filter.copy(dateRange = null)
            FilterDimension.RATE -> filter.copy(minBpm = 0.0, maxBpm = null)
        }

    /**
     * This filter with one more term.
     *
     * The counterpart to [without], and pure for the same reason: a rule being edited on a
     * collection is a [FilterState] nobody is filtering by yet, so adding to it cannot mean
     * "change what the library is showing".
     *
     * Ids arrive as strings because a watch is a UUID and everything else is not.
     */
    fun with(filter: FilterState, dimension: FilterDimension, id: String): FilterState {
        val numeric = id.toLongOrNull()
        return when (dimension) {
            FilterDimension.PERSON -> numeric?.let {
                filter.copy(selectedPersonIds = filter.selectedPersonIds + it)
            }
            FilterDimension.TAG -> numeric?.let {
                filter.copy(selectedTagIds = filter.selectedTagIds + it)
            }
            FilterDimension.EVENT -> numeric?.let {
                filter.copy(selectedEventIds = filter.selectedEventIds + it)
            }
            FilterDimension.COLLECTION -> numeric?.let {
                filter.copy(selectedGroupIds = filter.selectedGroupIds + it)
            }
            FilterDimension.LOCATION -> numeric?.let {
                filter.copy(selectedLocationIds = filter.selectedLocationIds + it)
            }
            FilterDimension.EVENT_TYPE ->
                filter.copy(selectedEventTypes = filter.selectedEventTypes + id)
            FilterDimension.WATCH -> filter.copy(selectedWatchIds = filter.selectedWatchIds + id)
            FilterDimension.DATE, FilterDimension.RATE -> filter
        } ?: filter
    }

    /**
     * Whether this filter already names that value.
     *
     * So a picker can show what is on rather than only what has been tapped since it opened —
     * reopening it used to present a list with no memory of the last visit.
     */
    fun holds(filter: FilterState, dimension: FilterDimension, id: String): Boolean {
        val numeric = id.toLongOrNull()
        return when (dimension) {
            FilterDimension.PERSON -> numeric in filter.selectedPersonIds
            FilterDimension.TAG -> numeric in filter.selectedTagIds
            FilterDimension.EVENT -> numeric in filter.selectedEventIds
            FilterDimension.COLLECTION -> numeric in filter.selectedGroupIds
            FilterDimension.LOCATION -> numeric in filter.selectedLocationIds
            FilterDimension.EVENT_TYPE -> id in filter.selectedEventTypes
            FilterDimension.WATCH -> id in filter.selectedWatchIds
            FilterDimension.DATE -> filter.dateRange != null
            FilterDimension.RATE -> filter.minBpm > 0.0 || filter.maxBpm != null
        }
    }

    private fun FilterChip.numericId(): Long = id.substringAfterLast('-').toLongOrNull() ?: -1L
}
