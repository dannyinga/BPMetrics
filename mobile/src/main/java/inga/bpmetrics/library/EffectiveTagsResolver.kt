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
 * Where a tag on a recording came from.
 *
 * `GROUP` used to be the third case, meaning "the collection that event belongs to". Collections
 * are events now, so the distinction that survives is how far up it was found, not what kind of
 * thing found it.
 */
enum class TagSource {
    /** Applied to this recording directly. Removable here. */
    DIRECT,

    /** Applied to the event this recording is filed under. Removable only on that event. */
    EVENT,

    /** Applied to an event further up the tree. Removable only where it was applied. */
    ANCESTOR
}

/**
 * A tag as it applies to a recording, and why.
 *
 * The source is the whole point. An inherited tag is shown differently and cannot be removed here —
 * otherwise the first thing anyone tries is taking `Coachella` off one recording, which cannot mean
 * anything: the recording is still filed under an event in a group tagged `Coachella`.
 */
data class EffectiveTag(
    val tag: TagEntity,
    val source: TagSource
) {
    val isInherited: Boolean get() = source != TagSource.DIRECT
}

/**
 * Works out which tags apply to a recording, given what its event and group carry.
 *
 * The one implementation. Filtering, analysis grouping and display all resolve through here, so
 * "does this recording match `Festivals › Coachella 2026`" has exactly one answer.
 *
 * Nothing is written. A recording's own tags live in `record_tag_cross_ref` and nowhere else;
 * inheritance is computed every time it is needed. That is what makes moving a recording to a
 * different event immediately correct, with nothing to clean up — see §2.5.
 */
object EffectiveTagsResolver {

    /**
     * @param directTags The recording's own tags.
     * @param ancestry The events containing it, **innermost first** — the one it is filed under,
     *   then that one's parent, and so on to the top. Exactly what [EventTree.ancestryOf] returns;
     *   pass its output rather than assembling a chain, which is the whole point of TX-1.4.
     * @param eventTags Tags by event id.
     */
    fun resolve(
        directTags: List<TagEntity>,
        ancestry: List<Long>,
        eventTags: Map<Long, List<TagEntity>>
    ): List<EffectiveTag> {
        val seen = mutableSetOf<Long>()
        val result = mutableListOf<EffectiveTag>()

        // Nearest source wins. The same tag applied to a recording *and* to an event above it is
        // one tag, and calling it direct is what keeps it removable where it was actually applied.
        fun add(tags: List<TagEntity>, source: TagSource) {
            tags.forEach { tag ->
                if (seen.add(tag.tagId)) result += EffectiveTag(tag, source)
            }
        }

        add(directTags, TagSource.DIRECT)
        // Innermost first, so a tag set on both a day and the festival above it is attributed to
        // the day. One loop over one chain: before the fold this was a lookup for the event and
        // then a separate walk for its collections, which is two rules to keep in agreement.
        ancestry.forEachIndexed { depth, eventId ->
            add(
                eventTags[eventId].orEmpty(),
                if (depth == 0) TagSource.EVENT else TagSource.ANCESTOR
            )
        }

        return result
    }

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
    ): Map<Long, List<EffectiveTag>> {
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
