package inga.bpmetrics.ui.analysis

/**
 * A way of cutting an analysis into lanes to compare.
 *
 * Deliberately separate from *scope*. Scope is which recordings are in play — a festival, a set, a
 * collection; a split is how those get divided up once they are. Conflating the two is why "compare
 * my rate across characters" previously meant filtering to Spiderman, reading the number, filtering
 * to Hulk, reading that, and holding both in your head.
 */
sealed interface SplitAxis {
    val key: String
    val label: String

    /**
     * What to call the lane holding everything this axis does not label.
     *
     * "Unlabelled" is right for a tag category — those recordings genuinely have no value on that
     * axis. It is wrong for an axis that narrows to one kind of thing, where the residue is not
     * unlabelled but *other*: the sports games are not missing an event type, they simply are not
     * concerts.
     */
    val residualLabel: String get() = "Unlabelled"

    /** Who was wearing the watch. The comparison a multi-watch recording exists for. */
    data object Person : SplitAxis {
        override val key = "person"
        override val label = "Person"
    }

    /** The events directly inside the scope — Day 1 against Day 2, one artist against the next. */
    data object ChildEvent : SplitAxis {
        override val key = "event"
        override val label = "Event"
    }

    /**
     * A tag category. The axis this whole document exists to make possible.
     *
     * One tag per category per recording is what makes this a partition rather than a set of
     * overlapping filters — see `LibraryRepository.addTagToRecord`.
     */
    data class TagCategory(val categoryId: Long, val name: String) : SplitAxis {
        override val key = "tag-$categoryId"
        override val label = name
    }

    /** What kind of thing the event was. Spiderman vs Hulk, one level up. */
    data object EventType : SplitAxis {
        override val key = "type"
        override val label = "Event type"
    }

    /**
     * The events of one kind, against each other. Every concert; every sports game.
     *
     * An event type behaves like a tag category, and this is the half that was missing. [EventType]
     * answers "concerts or sports games?" — one lane each — and that is the *category* question.
     * The one people actually ask next is "which concert?", and there was no way to ask it: the
     * [ChildEvent] axis puts every event in the scope side by side, so the concerts were scattered
     * among the sports games with nothing to separate them.
     *
     * Keyed on the type string rather than an id because that is what an event type is — there is
     * no registry behind it.
     */
    data class EventsOfType(val type: String) : SplitAxis {
        override val key = "type-$type"
        override val label = type
        override val residualLabel = "Other event types"
    }

    /**
     * Where it happened. The Gorge against Showbox.
     *
     * Works whether or not any coordinates were ever captured — a venue is a registry entry with a
     * name, and the name is all a comparison needs.
     */
    data object Place : SplitAxis {
        override val key = "place"
        override val label = "Location"
    }

    /**
     * Every recording on its own.
     *
     * The finest split there is, and the reason the Recordings tab is gone: a list of recordings
     * ranked by peak *is* a comparison, and having it on its own tab meant two ways of asking one
     * question with two different controls and two different-looking answers.
     */
    data object Recording : SplitAxis {
        override val key = "recording"
        override val label = "Recording"
    }
}

/**
 * One lane of a comparison, and everything in it.
 *
 * The statistics are derived from [records] rather than passed in, so a lane's summary and the rows
 * beneath it cannot disagree — the failure this codebase keeps having in other forms.
 */
data class SplitLane(
    /**
     * What identifies this lane, as opposed to what it is called.
     *
     * Two events can share a short name, and a snapshot saved before qualified names existed has
     * only short names — so labels are not unique and cannot be the key. Grouping on the label
     * would merge two different nights and average them together, which is the bug this whole
     * qualified-name change exists to prevent.
     */
    val key: String,
    val value: String,
    val records: List<AnalysisRecord>,
    /** The person's colour where the axis is people, so lanes read as the app colours them. */
    val colorArgb: Int? = null,
    /**
     * True for the lane holding everything the axis does not label.
     *
     * Shown last and never treated as a result. "Untagged beat Hulk" is not a finding.
     */
    val isUnlabelled: Boolean = false
) {
    val count: Int get() = records.size

    val minBpm: Double? get() = records.mapNotNull { it.minBpm }.minOrNull()
    val maxBpm: Double? get() = records.mapNotNull { it.maxBpm }.maxOrNull()

    /**
     * Weighted by how long each recording actually ran.
     *
     * A plain mean would let a two-minute recording count as much as a two-hour one, so a lane
     * containing one short spike would win every comparison it appeared in.
     */
    val avgBpm: Double?
        get() {
            val total = records.sumOf { it.activeDurationMs }
            if (total <= 0L) return records.mapNotNull { it.avgBpm }.average().takeIf { !it.isNaN() }
            return records.sumOf { (it.avgBpm ?: 0.0) * it.activeDurationMs } / total
        }

    val activeDurationMs: Long get() = records.sumOf { it.activeDurationMs }
}

/**
 * Splitting an analysis into comparable lanes.
 *
 * Pure. Given the records in scope it says which axes are worth offering and cuts them into lanes,
 * with no knowledge of screens, databases or what is currently selected.
 */
object AnalysisSplit {

    /**
     * The axes worth offering for these records.
     *
     * **Only where the scope holds two or more values of that axis**, because an axis with one
     * value is not a comparison — "compare by character" over an evening that was all Spiderman
     * produces one lane and answers nothing. Offering it anyway trains people to tap things that do
     * nothing.
     *
     * Counted over *labelled* values only. A scope of five Spiderman recordings and three untagged
     * is not a character comparison; it is one character and some gaps. The untagged still get a
     * lane when the axis does qualify, so the lanes always sum to the whole.
     */
    fun axesFor(
        records: List<AnalysisRecord>,
        /** The event the scope *is*. It is never a lane, so it never makes an axis qualify. */
        excludeEventId: Long? = null
    ): List<SplitAxis> {
        if (records.isEmpty()) return emptyList()

        val axes = mutableListOf<SplitAxis>()

        if (records.mapNotNull { it.personId }.distinct().size > 1 ||
            records.map { it.wearerName }.filter { it.isNotBlank() }.distinct().size > 1
        ) {
            axes += SplitAxis.Person
        }

        // By id, not by name. Two events called "Subtronics" from different weekends are two
        // nights, and counting them as one axis value would be a wrong answer rather than an
        // untidy one — short names are only unique within their own branch.
        val eventIds = records.mapNotNull { it.eventId }.filter { it != excludeEventId }.distinct()
        if (eventIds.size > 1) {
            axes += SplitAxis.ChildEvent
        }

        if (records.map { it.eventType }.filter { it.isNotBlank() }.distinct().size > 1) {
            axes += SplitAxis.EventType
        }

        // One axis per event type that holds more than one event, so "Concert" is offered as a
        // thing to compare within and a type with a single event under it is not. Ordered by name
        // rather than by however the records happened to arrive, or the row of chips would
        // rearrange itself whenever an exclusion changed.
        records
            .filter { it.eventType.isNotBlank() && it.eventId != null && it.eventId != excludeEventId }
            .groupBy { it.eventType }
            .filter { (_, ofType) -> ofType.mapNotNull { it.eventId }.distinct().size > 1 }
            .keys
            .sorted()
            .forEach { axes += SplitAxis.EventsOfType(it) }

        // By id where there is one, so two venues sharing a name stay two — the same reason events
        // split on identity rather than on what they are called.
        if (records.mapNotNull { it.locationId }.distinct().size > 1) {
            axes += SplitAxis.Place
        }

        // Categories come from the records rather than the library, so a saved analysis still
        // offers the axes it was taken with after one has been renamed or removed.
        records.flatMap { it.tags }
            .groupBy { it.categoryId }
            .filter { (_, tags) -> tags.map { it.tagName }.distinct().size > 1 }
            .forEach { (categoryId, tags) ->
                axes += SplitAxis.TagCategory(categoryId, tags.first().categoryName)
            }

        // Last, because it is the finest cut there is and the least likely to be the question. It
        // is offered at all because a list of recordings ranked by peak *is* a comparison — it used
        // to be its own tab, with its own control and its own look, answering the same question in
        // a second way.
        if (records.size > 1) axes += SplitAxis.Recording

        return axes
    }

    /**
     * Cuts [records] into lanes along [axis].
     *
     * Every record lands in exactly one lane and the lanes sum to the whole — that is the property
     * that makes a percentage mean something, and it is why a category may hold only one tag per
     * recording. Anything the axis does not label goes into a single trailing lane rather than
     * being dropped, so a total is never quietly smaller than the scope it claims to describe.
     *
     * Ordered by the thing being compared: hardest first, because the question is almost always
     * "which of these was the most". The unlabelled lane sits last regardless.
     */
    fun split(
        records: List<AnalysisRecord>,
        axis: SplitAxis,
        /**
         * The event the scope *is*, so it does not become a lane of its own.
         *
         * Comparing a festival's events against each other should not include the festival: it
         * would be one lane holding everything, drawn at full width, saying nothing.
         */
        excludeEventId: Long? = null,
        /**
         * Which figure decides the order.
         *
         * Ranking by peak while the screen displays averages is a list that looks mis-sorted, and
         * the reader has no way to tell it is not.
         */
        orderBy: (SplitLane) -> Double? = { it.avgBpm }
    ): List<SplitLane> {
        if (records.isEmpty()) return emptyList()

        // Keyed by identity, labelled separately. The two coincide for most axes and deliberately
        // do not for events.
        val labelled: Map<String, List<AnalysisRecord>>
        val labels: (String, List<AnalysisRecord>) -> String
        val unlabelled: List<AnalysisRecord>

        when (axis) {
            is SplitAxis.Person -> {
                val named = records.filter { it.wearerName.isNotBlank() }
                labelled = named.groupBy { it.wearerName }
                labels = { key, _ -> key }
                unlabelled = records - named.toSet()
            }
            is SplitAxis.Recording -> {
                labelled = records.associateBy { it.recordId.toString() }.mapValues { listOf(it.value) }
                labels = { _, inLane -> inLane.first().title }
                unlabelled = emptyList()
            }

            is SplitAxis.ChildEvent -> {
                // Grouped by id and labelled by the qualified name, so two events sharing a short
                // name stay two lanes and are told apart on screen.
                val named = records.filter { it.eventId != null && it.eventId != excludeEventId }
                labelled = named.groupBy { it.eventId.toString() }
                labels = { _, inLane -> inLane.first().eventLabel }
                unlabelled = records - named.toSet()
            }
            is SplitAxis.EventType -> {
                val named = records.filter { it.eventType.isNotBlank() }
                labelled = named.groupBy { it.eventType }
                labels = { key, _ -> key }
                unlabelled = records - named.toSet()
            }
            is SplitAxis.EventsOfType -> {
                // Narrowed to the type, then split by event exactly as [SplitAxis.ChildEvent] does.
                // Everything of another type still gets its lane so the totals stay honest, but it
                // is one lane called "Other event types" rather than a comparison of its own.
                val named = records.filter {
                    it.eventType == axis.type && it.eventId != null && it.eventId != excludeEventId
                }
                labelled = named.groupBy { it.eventId.toString() }
                labels = { _, inLane -> inLane.first().eventLabel }
                unlabelled = records - named.toSet()
            }
            is SplitAxis.Place -> {
                val named = records.filter { it.locationId != null }
                labelled = named.groupBy { it.locationId.toString() }
                labels = { _, inLane -> inLane.first().locationName }
                unlabelled = records - named.toSet()
            }
            is SplitAxis.TagCategory -> {
                // At most one tag per category per record, so this is a partition rather than a
                // record appearing in several lanes. `firstOrNull` is the safety net for data
                // written before that rule was enforced.
                val tagged = records.mapNotNull { record ->
                    record.tags.firstOrNull { it.categoryId == axis.categoryId }
                        ?.let { it.tagName to record }
                }
                labelled = tagged.groupBy({ it.first }, { it.second })
                labels = { key, _ -> key }
                unlabelled = records - tagged.map { it.second }.toSet()
            }
        }

        val colours = records
            .filter { it.personColorArgb != null }
            .associate { it.wearerName to it.personColorArgb }

        val lanes = labelled.map { (key, inLane) ->
            SplitLane(
                key = key,
                value = labels(key, inLane),
                records = inLane,
                colorArgb = if (axis is SplitAxis.Person) colours[key] else null
            )
        }.sortedByDescending { orderBy(it) ?: Double.NEGATIVE_INFINITY }

        return if (unlabelled.isEmpty()) lanes
        else lanes + SplitLane("unlabelled", axis.residualLabel, unlabelled, isUnlabelled = true)
    }
}
