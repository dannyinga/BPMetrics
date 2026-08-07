package inga.bpmetrics.library

import androidx.room.Embedded

/**
 * A tag with whatever it was attached to, for reading many owners' tags in one query.
 */
data class OwnedTag(
    val ownerId: Long,
    @Embedded val tag: TagEntity
)

/** Where a tag on a recording came from. */
enum class TagSource {
    /** Applied to this recording directly. Removable here. */
    DIRECT,

    /** Applied to the event this recording is filed under. Removable only on the event. */
    EVENT,

    /** Applied to the group that event belongs to. Removable only on the group. */
    GROUP
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
     * @param eventId Which event it is filed under, if any.
     * @param groupChain The collections above it, nearest first — its event's collection, then
     *   that collection's parent, and so on to the top.
     * @param eventTags Tag ids by event id.
     * @param groupTags Tag ids by group id.
     */
    fun resolve(
        directTags: List<TagEntity>,
        eventId: Long?,
        groupChain: List<Long>,
        eventTags: Map<Long, List<TagEntity>>,
        groupTags: Map<Long, List<TagEntity>>
    ): List<EffectiveTag> {
        val seen = mutableSetOf<Long>()
        val result = mutableListOf<EffectiveTag>()

        // Nearest source wins. The same tag applied to a recording *and* to its collection is one
        // tag, and calling it direct is what keeps it removable where it was actually applied.
        fun add(tags: List<TagEntity>, source: TagSource) {
            tags.forEach { tag ->
                if (seen.add(tag.tagId)) result += EffectiveTag(tag, source)
            }
        }

        add(directTags, TagSource.DIRECT)
        eventId?.let { add(eventTags[it].orEmpty(), TagSource.EVENT) }
        // Nearest first, so a tag set on both a day and the festival above it is attributed to the
        // day. Collections nest, so this is a walk rather than a single lookup — but "nearest
        // wins" is unchanged, which is what stops nesting complicating the rule.
        groupChain.forEach { add(groupTags[it].orEmpty(), TagSource.GROUP) }

        return result
    }

    /**
     * The same thing for a whole library, resolved once rather than per row.
     *
     * @param groupIdByEvent Which group each event belongs to, so a recording reaches its group
     *   through its event rather than storing a second link that could disagree.
     */
    fun resolveAll(
        records: List<BpmRecord>,
        eventTags: Map<Long, List<TagEntity>>,
        groupTags: Map<Long, List<TagEntity>>,
        groupIdByEvent: Map<Long, Long?>,
        /** Each collection's parent, so a recording reaches every collection above it. */
        parentByGroup: Map<Long, Long?> = emptyMap()
    ): Map<Long, List<EffectiveTag>> {
        // Chains are resolved once per collection rather than once per recording: a festival with
        // four hundred recordings would otherwise walk the same three links four hundred times.
        val chains = mutableMapOf<Long, List<Long>>()
        fun chainFor(groupId: Long): List<Long> = chains.getOrPut(groupId) {
            val chain = mutableListOf<Long>()
            var current: Long? = groupId
            val seen = mutableSetOf<Long>()
            // Guarded, because a cycle here would hang the library rather than throw.
            while (current != null && seen.add(current)) {
                chain += current
                current = parentByGroup[current]
            }
            chain
        }

        return records.associate { record ->
            val eventId = record.metadata.eventId
            val groupId = eventId?.let { groupIdByEvent[it] }
            record.metadata.recordId to resolve(
                directTags = record.tags,
                eventId = eventId,
                groupChain = groupId?.let(::chainFor).orEmpty(),
                eventTags = eventTags,
                groupTags = groupTags
            )
        }
    }

    /** Turns the flat query result into tags keyed by what they are attached to. */
    fun index(owned: List<OwnedTag>): Map<Long, List<TagEntity>> =
        owned.groupBy({ it.ownerId }, { it.tag })
}
