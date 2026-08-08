package inga.bpmetrics.library

/**
 * A picture that stands for something, and which part of it to show.
 *
 * The crop is fractions of the source image, never pixels — for the same reason the export stores
 * graph placement that way. A library tile is wide, a grid thumbnail is square and a page header is
 * wider still; fractions survive all three, pixels survive none of them.
 *
 * @property path A file name inside `files/covers/`, not a path. The app's files directory differs
 * between installs, so an absolute path written on one device means nothing on another.
 */
data class Cover(
    val path: String,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f
) {
    val cropWidth: Float get() = cropRight - cropLeft
    val cropHeight: Float get() = cropBottom - cropTop

    companion object {
        /**
         * A cover from stored columns, or null when there is no picture.
         *
         * A crop with no area is treated as no crop rather than as a cover that draws nothing —
         * that is what a row written before the crop columns existed looks like, and an invisible
         * cover is indistinguishable from a bug.
         */
        fun of(
            path: String?,
            left: Float?,
            top: Float?,
            right: Float?,
            bottom: Float?
        ): Cover? {
            val name = path?.takeIf { it.isNotBlank() } ?: return null
            val l = left ?: 0f
            val t = top ?: 0f
            val r = right ?: 1f
            val b = bottom ?: 1f
            val degenerate = (r - l) < 0.01f || (b - t) < 0.01f
            return if (degenerate) {
                Cover(name)
            } else {
                Cover(name, l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
            }
        }
    }
}

/** Where the cover shown on a recording came from. */
enum class CoverSource {
    /** Set on this recording. The exception, for the one that deserves its own picture. */
    DIRECT,

    /** Set on the event this recording is filed under. */
    EVENT,

    /** Set on a collection above it — its event's, or one of that collection's parents. */
    GROUP
}

/** A cover as it applies to something, and why. */
data class EffectiveCover(
    val cover: Cover,
    val source: CoverSource
) {
    val isInherited: Boolean get() = source != CoverSource.DIRECT
}

/**
 * Works out which picture stands for a recording, given what its event and collections carry.
 *
 * Deliberately the same shape as [EffectiveTagsResolver], and for the same reason: nothing is
 * written downward. A cover set on "Coachella Day 1" applies to every recording under it because it
 * is *found* there when asked, not because it was copied onto each one. That is what makes filing a
 * recording into an event immediately correct with nothing to clean up — and what makes a recording
 * arriving late from a watch pick up the right picture on its own, which was the entire reason for
 * putting the cover on the event rather than on the recording.
 *
 * Nearest wins. A recording's own cover beats its event's, which beats its collection's, which
 * beats that collection's parent's.
 */
object CoverResolver {

    /**
     * @param directCover The recording's own cover, if it has been given one.
     * @param eventCover The cover on the event it is filed under, if any.
     * @param groupChainCovers The covers of the collections above it, nearest first — its event's
     *   collection, then that collection's parent, and so on to the top. Entries are null where
     *   that collection has no cover of its own.
     */
    fun resolve(
        directCover: Cover?,
        eventCover: Cover?,
        groupChainCovers: List<Cover?>
    ): EffectiveCover? {
        directCover?.let { return EffectiveCover(it, CoverSource.DIRECT) }
        eventCover?.let { return EffectiveCover(it, CoverSource.EVENT) }
        groupChainCovers.firstNotNullOfOrNull { it }
            ?.let { return EffectiveCover(it, CoverSource.GROUP) }
        return null
    }

    /**
     * The collections above [groupId], nearest first.
     *
     * Cycle-guarded. A collection that is its own ancestor should be impossible — `CollectionTree`
     * refuses to create one — but a walk up parents is the code that *hangs* rather than throws if
     * one ever exists, and this runs while a list is being drawn.
     */
    fun ancestryOf(groupId: Long?, parents: Map<Long, Long?>): List<Long> {
        val chain = mutableListOf<Long>()
        val seen = mutableSetOf<Long>()
        var current = groupId
        while (current != null && seen.add(current)) {
            chain += current
            current = parents[current]
        }
        return chain
    }

    /**
     * The cover for a recording, from whole maps rather than a pre-walked chain.
     *
     * The convenience the UI actually wants: a screen holds every event and collection already, and
     * asking it to assemble an ancestry per row is how one screen comes to walk the tree slightly
     * differently from another.
     */
    fun forRecording(
        directCover: Cover?,
        eventId: Long?,
        eventCovers: Map<Long, Cover?>,
        eventGroups: Map<Long, Long?>,
        groupCovers: Map<Long, Cover?>,
        groupParents: Map<Long, Long?>
    ): EffectiveCover? {
        val groupId = eventId?.let { eventGroups[it] }
        return resolve(
            directCover = directCover,
            eventCover = eventId?.let { eventCovers[it] },
            groupChainCovers = ancestryOf(groupId, groupParents).map { groupCovers[it] }
        )
    }
}
