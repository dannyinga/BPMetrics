package inga.bpmetrics.export

import inga.bpmetrics.library.FilterState

/**
 * Translates the ids in a restored collection's rule into the ids things got on import.
 *
 * A rule is stored as ids: events, people, tags, venues and other collections. Every one of those
 * is renumbered on import, so a rule copied verbatim names whatever happens to hold its old
 * numbers in the library it lands in. That is how "Danny BC" (an event and a person) came back
 * matching nothing, and "Colin BC" came back matching one unrelated recording.
 *
 * Watch ids are the watch's own UUID and survive as they are. Event types and the query are text.
 *
 * ## When an id can't be translated
 *
 * It is dropped, and the dimension is reported in [Result.lost] so the restore can say which
 * collections need their rule checked. If dropping it would leave a dimension empty that wasn't,
 * the dimension keeps [NOTHING] instead. An empty dimension means "don't narrow on this", so
 * emptying it would make the rule match *more* than it did. A collection that quietly grows is
 * far harder to notice than one that comes back empty, the same reasoning as
 * [inga.bpmetrics.library.FilterCodec.parseOrNull].
 */
internal object RuleRemap {

    /** An id nothing has, so a dimension that keeps it matches nothing. */
    const val NOTHING = -1L

    /** Old id to new id, per kind of thing a rule can name. */
    data class Maps(
        val events: Map<Long, Long> = emptyMap(),
        val people: Map<Long, Long> = emptyMap(),
        val tags: Map<Long, Long> = emptyMap(),
        val locations: Map<Long, Long> = emptyMap(),
        val collections: Map<Long, Long> = emptyMap()
    )

    /** What a dimension is called when telling the user which part of a rule was lost. */
    enum class Kind(val noun: String) {
        EVENT("event"), PERSON("person"), TAG("tag"), VENUE("venue"), COLLECTION("collection")
    }

    data class Result(val rule: FilterState, val lost: Set<Kind>)

    fun remap(rule: FilterState, maps: Maps): Result {
        val lost = mutableSetOf<Kind>()

        fun Set<Long>.through(map: Map<Long, Long>, kind: Kind): Set<Long> {
            if (isEmpty()) return this
            val kept = mapNotNull { map[it] }.toSet()
            if (kept.size < size) lost += kind
            return kept.ifEmpty { setOf(NOTHING) }
        }

        return Result(
            rule = rule.copy(
                selectedEventIds = rule.selectedEventIds.through(maps.events, Kind.EVENT),
                selectedPersonIds = rule.selectedPersonIds.through(maps.people, Kind.PERSON),
                selectedTagIds = rule.selectedTagIds.through(maps.tags, Kind.TAG),
                selectedLocationIds = rule.selectedLocationIds.through(maps.locations, Kind.VENUE),
                selectedGroupIds = rule.selectedGroupIds.through(maps.collections, Kind.COLLECTION)
            ),
            lost = lost
        )
    }
}
