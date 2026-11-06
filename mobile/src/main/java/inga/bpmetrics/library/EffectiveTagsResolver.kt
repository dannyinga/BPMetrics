package inga.bpmetrics.library

import androidx.room.Embedded

/**
 * A tag with whatever it was attached to, for reading many owners' tags in one query.
 */
data class OwnedTag(
    val ownerId: Long,
    @Embedded val tag: TagEntity
)

/**
 * Works out which tags apply to a recording, given what its events carry.
 *
 * The one implementation. Filtering, analysis grouping and display all resolve through here, so
 * "does this recording match `Festivals › Coachella 2026`" has exactly one answer.
 *
 * Nothing is written. A recording's own tags live in `record_tag_cross_ref` and nowhere else;
 * inheritance is computed every time it is needed. That is what makes moving a recording to a
 * different event immediately correct, with nothing to clean up.
 *
 * Each tag says where it came from. An inherited one is shown differently and can't be removed
 * here: taking `Coachella` off one recording can't mean anything while it's still filed under an
 * event tagged `Coachella`. The walk is [gather], the same one covers and places use.
 */
object EffectiveTagsResolver {

    /**
     * @param directTags The recording's own tags.
     * @param ancestry The events containing it, **innermost first**, exactly what
     *   [EventTree.ancestryOf] returns.
     * @param eventTags Tags by event id.
     */
    fun resolve(
        directTags: List<TagEntity>,
        ancestry: List<Long>,
        eventTags: Map<Long, List<TagEntity>>
    ): List<Inherited<TagEntity>> =
        // Nearest source wins. The same tag on a recording and on an event above it is one tag,
        // and calling it the recording's own keeps it removable where it was actually applied.
        gather(directTags, ancestry, { eventTags[it].orEmpty() }) { it.tagId }

    /**
     * The same thing for a whole library, resolved once rather than per row.
     *
     * @param events Every event, so ancestry comes from [EventTree] rather than from a chain
     *   assembled here. A screen holding the whole library already has this.
     */
    fun resolveAll(
        records: List<BpmRecord>,
        eventTags: Map<Long, List<TagEntity>>,
        events: List<EventEntity>
    ): Map<Long, List<Inherited<TagEntity>>> {
        // Resolved once per event rather than once per recording: a festival with four hundred
        // recordings would otherwise walk the same three links four hundred times.
        val chains = mutableMapOf<Long, List<Long>>()
        fun chainFor(eventId: Long): List<Long> =
            chains.getOrPut(eventId) { EventTree.ancestryOf(events, eventId).map { it.eventId } }

        return records.associate { record ->
            record.metadata.recordId to resolve(
                directTags = record.tags,
                ancestry = record.metadata.eventId?.let(::chainFor).orEmpty(),
                eventTags = eventTags
            )
        }
    }

    /** Turns the flat query result into tags keyed by what they are attached to. */
    fun index(owned: List<OwnedTag>): Map<Long, List<TagEntity>> =
        owned.groupBy({ it.ownerId }, { it.tag })
}
