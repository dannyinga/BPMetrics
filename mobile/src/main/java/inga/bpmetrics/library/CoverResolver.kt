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
    val cropBottom: Float = 1f,
    /**
     * How far to soften the picture behind the writing, 0..1.
     *
     * For covers that are themselves made of type. An event flyer is the obvious and common case —
     * a headliner in ninety-point letters and a support list underneath — and no amount of
     * protecting *our* text fixes that, because the problem is not contrast, it is two sets of
     * words competing in one space. Blurring dissolves the flyer's type while keeping its colour
     * and composition, which is what identifies the night at a glance anyway.
     *
     * Zero, and therefore off, for the ordinary case: a photograph of a crowd needs none of this.
     */
    val blur: Float = 0f,
    /**
     * How far to darken the picture, 0..1.
     *
     * The honest answer to "the writing is hard to read", and the one that survived. A stroked
     * outline on the text was tried first: it fixes contrast at the cost of making every letter
     * look like a sticker, and it treats the symptom on the wrong object. The picture is what is
     * too bright — a white-sky festival shot, a flash photo — and the picture is what should give.
     *
     * Per-cover rather than a global scrim, because only *some* covers are too bright, and dimming
     * all of them is how every photograph ends up the same grey rectangle.
     *
     * Zero, and therefore off, for the ordinary case.
     */
    val dim: Float = 0f
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
            bottom: Float?,
            blur: Float? = null,
            dim: Float? = null
        ): Cover? {
            val name = path?.takeIf { it.isNotBlank() } ?: return null
            val softness = (blur ?: 0f).coerceIn(0f, 1f)
            val darkness = (dim ?: 0f).coerceIn(0f, 1f)
            val l = left ?: 0f
            val t = top ?: 0f
            val r = right ?: 1f
            val b = bottom ?: 1f
            val degenerate = (r - l) < 0.01f || (b - t) < 0.01f
            return if (degenerate) {
                Cover(name, blur = softness, dim = darkness)
            } else {
                Cover(
                    name,
                    l.coerceIn(0f, 1f),
                    t.coerceIn(0f, 1f),
                    r.coerceIn(0f, 1f),
                    b.coerceIn(0f, 1f),
                    softness,
                    darkness
                )
            }
        }
    }
}

/**
 * Works out which picture stands for a recording or an event.
 *
 * Nothing is written downward. A cover set on "Coachella Day 1" applies to every recording under it
 * because it is *found* there when asked, not because it was copied onto each one. That is what
 * makes filing a recording into an event immediately correct with nothing to clean up, and what
 * makes a recording arriving late from a watch pick up the right picture on its own.
 *
 * Nearest wins: a recording's own cover, then its event's, then the events above. The walk itself
 * is [nearest], shared with places and tags; this only says what to pick.
 */
object CoverResolver {

    /**
     * From a chain already in hand.
     *
     * @param eventCover the cover on the event it is filed under, if any.
     * @param groupChainCovers the covers of the events above that, nearest first; null where one
     *   has none of its own.
     */
    fun resolve(directCover: Cover?, eventCover: Cover?, groupChainCovers: List<Cover?>): Inherited<Cover>? =
        nearest(directCover, listOf(eventCover) + groupChainCovers) { it }

    /**
     * The cover for anything in the tree: its own, else the nearest event's above it. What every
     * screen should ask, so the card and the page it opens show the same picture.
     */
    fun of(item: TreeItem, events: List<EventEntity>): Inherited<Cover>? =
        EventTree.nearest(item, events) { it.ownCover }

    /**
     * The cover for a recording, from whole maps rather than a pre-walked chain.
     *
     * @param eventCovers each event's own cover, by id.
     */
    fun forRecording(
        directCover: Cover?,
        eventId: Long?,
        eventCovers: Map<Long, Cover?>,
        events: List<EventEntity>
    ): Inherited<Cover>? = nearest(
        own = directCover,
        ancestry = eventId?.let { EventTree.ancestryOf(events, it) }.orEmpty()
    ) { eventCovers[it.eventId] }

    /**
     * Every event's picture, its own or the nearest one above it.
     *
     * The map any list of events wants. A day inside a festival shows the festival's photograph
     * rather than nothing. Shared because it had already been written twice with different
     * answers: the library walked the ancestry, and the export picker read `ownCover` directly.
     */
    fun byEvent(events: List<EventEntity>): Map<Long, Cover> =
        events.mapNotNull { event ->
            EventTree.nearest(event, events) { it.ownCover }?.let { event.eventId to it.value }
        }.toMap()
}
