package inga.bpmetrics.library

/**
 * A question asked of the library.
 *
 * Lives here rather than on the library screen because it is no longer only the screen's. A
 * collection can carry one as its rule — see [CollectionEntity.filterJson] — so the layer that
 * resolves what a collection contains has to be able to evaluate it, and a filter owned by a
 * ViewModel would mean the library depending on the UI to answer a question about itself.
 *
 * **Every field has a default, and that is load-bearing.** This is deserialised by Gson from a
 * collection's stored rule, and Gson does not run constructors: given a class with one required
 * parameter it allocates through `Unsafe` and leaves omitted keys null, including on properties
 * declared non-null. With all parameters defaulted, Kotlin emits a no-arg constructor, Gson calls
 * it, and a rule saved before a dimension existed simply reads as not narrowing on it.
 */
data class FilterState(
    /**
     * Free text, matched against a recording's title, its person, its event and its venue.
     *
     * The thing a filter dialog could never be: you know what you are looking for before you know
     * which of the app's dimensions it lives in. Typing "gorge" should find it whether that is a
     * venue, an event name or something someone typed in a title.
     */
    val query: String = "",
    val dateRange: Pair<Long, Long>? = null,
    val selectedTagIds: Set<Long> = emptySet(),
    val minBpm: Double = 0.0,
    val maxBpm: Double? = null,
    /**
     * People to include, matched against who was wearing the watch at the time.
     *
     * Answers "show me Kyle's recordings" — and because each record settled on a person when it
     * arrived, it keeps answering correctly after that watch has been handed to someone else.
     * Renaming Kyle does not disturb it either, since the match is on the profile rather than on a
     * copy of the name.
     */
    val selectedPersonIds: Set<Long> = emptySet(),
    /**
     * Watches to include, matched on the physical device rather than the name.
     *
     * Answers the other question: "show me everything this watch ever recorded", whoever was
     * wearing it and whatever it was called at the time.
     */
    val selectedWatchIds: Set<String> = emptySet(),
    /**
     * Events to include, matched on the event a recording is filed under.
     *
     * Distinct from filtering by a tag the event carries: this asks "what was at this occasion",
     * which is true regardless of how anything was tagged.
     */
    val selectedEventIds: Set<Long> = emptySet(),
    /**
     * Collections to include.
     *
     * Resolved by [Scope] rather than matched here, because a collection's membership is a walk —
     * references followed through the event tree, plus whatever its own rule selects — and this is
     * a predicate over one record at a time.
     *
     * The name is `selectedGroupIds` because that is what the stored JSON of every saved rule calls
     * it. Renaming the property would silently empty the collection term of every rule anyone has
     * kept, which is worse than an awkward name.
     */
    val selectedGroupIds: Set<Long> = emptySet(),
    /** Venues to include, matched on the location a recording resolves to. */
    val selectedLocationIds: Set<Long> = emptySet()
) {
    /** Whether anything is narrowing the library at all. */
    val isEmpty: Boolean get() = this == FilterState()
}

/**
 * Everything a filter needs to look up, resolved once for the whole library.
 *
 * One shape rather than six parameters, because [LibraryFilter.apply] is called from the library,
 * from analysis and from inside [Scope] while resolving a collection's rule — and a parameter list
 * that has to be threaded through three call sites is a parameter list that gets a default at one
 * of them and quietly stops narrowing.
 *
 * @property effectiveTags Each recording's tags including those inherited from its event and the
 *   events above it, keyed by record id. Empty falls back to the recording's own tags.
 * @property recordIdsByCollection What each collection actually holds, from [Scope]. Passing the
 *   resolved answer rather than the links is the whole fix: membership is decided in one place, so
 *   filtering by a collection and opening that collection cannot give different sets.
 */
data class FilterContext(
    val effectiveTags: Map<Long, List<EffectiveTag>> = emptyMap(),
    val recordIdsByCollection: Map<Long, Set<Long>> = emptyMap(),
    val eventNames: Map<Long, String> = emptyMap(),
    val placeNames: Map<Long, String> = emptyMap(),
    val locationIdByEvent: Map<Long?, Long?> = emptyMap()
)

/**
 * The one predicate.
 *
 * Pure and static, because the library filters for display while analysis filters independently for
 * what it is analysing — choosing what to compare must not disturb what the library is showing —
 * and two copies of "does this recording match" is how the two come to disagree.
 */
object LibraryFilter {

    fun apply(
        records: List<BpmRecord>,
        filter: FilterState,
        context: FilterContext = FilterContext()
    ): List<BpmRecord> {
        // Tag ids resolved through the hierarchy, so filtering by an event's tag returns every
        // recording underneath it — the point of §2.5. Falls back to the recording's own tags where
        // inheritance has not been resolved.
        fun tagIdsFor(record: BpmRecord): Set<Long> =
            context.effectiveTags[record.metadata.recordId]
                ?.map { it.tag.tagId }
                ?.toSet()
                ?: record.tags.map { it.tagId }.toSet()

        // Category comes from the tags in play, which include inherited ones — a tag that only ever
        // appears via an event would otherwise have no category and be skipped, silently matching
        // everything.
        val tagToCategory = buildMap {
            records.forEach { record ->
                record.tags.forEach { put(it.tagId, it.parentCategoryId) }
                context.effectiveTags[record.metadata.recordId]?.forEach {
                    put(it.tag.tagId, it.tag.parentCategoryId)
                }
            }
        }

        // The union of what every named collection holds, resolved before the loop rather than per
        // record. A recording in any one of them satisfies the term.
        val inSomeCollection: Set<Long>? = filter.selectedGroupIds
            .takeIf { it.isNotEmpty() }
            ?.flatMapTo(mutableSetOf()) { context.recordIdsByCollection[it].orEmpty() }

        return records.filter { record ->
            val meta = record.metadata

            val dateMatch = filter.dateRange?.let { (start, end) ->
                meta.startTime in start..end
            } ?: true

            // OR within a category, AND between them: two artists means either artist, but an
            // artist and a venue means both.
            val tagMatch = if (filter.selectedTagIds.isEmpty()) true else {
                val recordTagIds = tagIdsFor(record)
                filter.selectedTagIds
                    .mapNotNull { tagId -> tagToCategory[tagId]?.let { it to tagId } }
                    .groupBy({ it.first }, { it.second })
                    .all { (_, wanted) -> wanted.any { it in recordTagIds } }
            }

            val avg = meta.avg ?: 0.0
            val bpmMatch = avg >= filter.minBpm && (filter.maxBpm == null || avg <= filter.maxBpm)

            // Who was wearing the watch at the time, so past recordings stay attributed to whoever
            // actually made them.
            val personMatch = filter.selectedPersonIds.isEmpty() ||
                meta.personId in filter.selectedPersonIds

            // The physical device, independent of naming.
            val watchMatch = filter.selectedWatchIds.isEmpty() ||
                meta.watchId in filter.selectedWatchIds

            val eventMatch = filter.selectedEventIds.isEmpty() ||
                meta.eventId in filter.selectedEventIds

            // Resolved by Scope, not derived here. This used to compare a *collection* id against
            // the event's *parent event* id — two different id spaces — so the term narrowed the
            // library by an unrelated rule and rendered no chip to undo it.
            val collectionMatch = inSomeCollection?.contains(meta.recordId) ?: true

            // Free text across everything someone might remember about a recording. They know what
            // they are looking for before they know which dimension it lives in, which is the thing
            // a sectioned dialog could never do.
            val queryMatch = filter.query.isBlank() || listOfNotNull(
                meta.title,
                meta.description,
                meta.wearerName,
                meta.eventId?.let { context.eventNames[it] },
                meta.eventId?.let { context.placeNames[it] }
            ).any { it.contains(filter.query.trim(), ignoreCase = true) }

            val locationMatch = filter.selectedLocationIds.isEmpty() ||
                context.locationIdByEvent[meta.eventId] in filter.selectedLocationIds

            dateMatch && tagMatch && bpmMatch && personMatch && watchMatch &&
                eventMatch && collectionMatch && queryMatch && locationMatch
        }
    }
}
