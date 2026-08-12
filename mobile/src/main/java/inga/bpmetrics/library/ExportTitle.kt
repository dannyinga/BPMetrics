package inga.bpmetrics.library

/**
 * What an exported picture or video is called.
 *
 * One rule, because a video and an image of the same night must not be captioned two different
 * ways — and because "what is this of" is answered from the tree, which is the sort of question this
 * codebase has repeatedly answered locally and inconsistently.
 *
 * **The event and everything it is inside, innermost first.**
 *
 * ```
 * Tape B  |  Levitape
 * Griztronics  |  Day 2  |  Griz @ The Gorge
 * ```
 *
 * The order matters and it is the opposite of a file path. A caption is read left to right and the
 * reader stops as soon as they know what they are looking at — so the specific thing goes first and
 * the ancestry trails off behind it as context. Outermost-first puts three containers in front of
 * the answer and buries the subject at the end of the line, where a narrow canvas truncates it.
 *
 * **The recording's own name is a fallback, not a prefix.** A graph of four recordings from the
 * Tape B set is a picture of *the set*, whoever's watches produced it — and captioning it with one
 * of the four names it happens to have been drawn from is both arbitrary and wrong. Naming it after
 * the recording is right in exactly one case: nothing has been filed, so the recording is the most
 * specific thing there is to name.
 *
 * The venue is appended once, to the whole thing, because it qualifies the occasion rather than any
 * one level of it — and because a set inherits its festival's venue, so attaching it per level would
 * repeat the same words down the line.
 */
object ExportTitle {

    /** How the parts are joined. Wide enough to read as separation rather than punctuation. */
    const val SEPARATOR = "  |  "

    /**
     * The title for something recorded at [eventId].
     *
     * @param recordingName The recording's own name, used only when there is no event to name
     *   instead — an unfiled recording, or one whose event has since been deleted.
     */
    fun of(
        recordingName: String? = null,
        eventId: Long? = null,
        events: List<EventEntity> = emptyList(),
        places: Map<Long, LocationEntity> = emptyMap()
    ): String {
        val family = eventId?.let { EventTree.ancestryOf(events, it).map { e -> e.displayName } }
            .orEmpty()

        val parts = family.ifEmpty { listOfNotNull(recordingName?.takeIf { it.isNotBlank() }) }
        if (parts.isEmpty()) return ""

        val venue = eventId
            ?.let { LocationResolver.forEvent(it, events, places)?.location?.displayName }
            ?.takeIf { it.isNotBlank() }

        val joined = parts.joinToString(SEPARATOR)
        return if (venue == null) joined else "$joined @ $venue"
    }
}
