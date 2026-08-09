package inga.bpmetrics.library

/**
 * Which event each recording belongs to.
 *
 * The single definition of "inside", and the only thing permitted to decide it. Everything else in
 * the app reads the answer from a column; this is what puts it there.
 *
 * The rule, in order:
 *
 * 1. The **deepest** event whose window contains the recording, where the window applies to that
 *    recording's wearer. Deepest, so a set inside a day inside a festival lands on the set.
 * 2. Failing that, whatever the recording was filed as by hand.
 * 3. Failing that, unfiled — which is a real state and not an error.
 *
 * Pure, so it can be checked exhaustively without a device. A membership bug does not crash; it
 * produces a total that is quietly wrong, and the only defence against that is being able to assert
 * the rule directly.
 */
object EventMembership {

    /**
     * A window, and who it applies to.
     *
     * @property people Empty means everyone, which is the common case. Naming people is what lets
     * two stages at one festival both run from nine until half past — see [Conflict].
     */
    data class Window(
        val eventId: Long,
        val startMs: Long,
        val endMs: Long,
        val depth: Int,
        val people: Set<Long>
    ) {
        fun claims(atMs: Long, personId: Long?): Boolean =
            atMs >= startMs && atMs <= endMs &&
                (people.isEmpty() || (personId != null && personId in people))

        /** Whether two windows could both claim the same recording. */
        fun collidesWith(other: Window): Boolean {
            val inTime = startMs <= other.endMs && other.startMs <= endMs
            val inPeople = people.isEmpty() || other.people.isEmpty() ||
                people.any { it in other.people }
            return inTime && inPeople
        }
    }

    /** Two sibling windows that could both claim the same recording. */
    data class Conflict(val first: Long, val second: Long)

    /**
     * The windows of [events], with the people qualifying each.
     *
     * Depth comes from the tree so "deepest wins" can be decided by comparing two numbers rather
     * than by walking upward from each candidate.
     */
    fun windowsOf(
        events: List<EventEntity>,
        windowPeople: Map<Long, Set<Long>>
    ): List<Window> {
        val depths = EventTree.flatten(events).associate { it.event.eventId to it.depth }
        return events.mapNotNull { event ->
            val start = event.windowStart ?: return@mapNotNull null
            val end = event.windowEnd ?: return@mapNotNull null
            if (end < start) return@mapNotNull null
            Window(
                eventId = event.eventId,
                startMs = start,
                endMs = end,
                depth = depths[event.eventId] ?: 0,
                people = windowPeople[event.eventId].orEmpty()
            )
        }
    }

    /**
     * Where every recording belongs. Absent from the map, or null, means unfiled.
     *
     * A recording is placed by its **start**, not by any overlap of its whole span. A watch left
     * running from one set into the next belongs to the set it began in — placing it by overlap
     * would put it in both, and the whole point of this function is that it returns one answer.
     */
    fun resolve(
        events: List<EventEntity>,
        windowPeople: Map<Long, Set<Long>>,
        recordings: List<BpmRecordEntity>
    ): Map<Long, Long?> {
        val windows = windowsOf(events, windowPeople)

        return recordings.associate { record ->
            val claiming = windows.filter { it.claims(record.startTime, record.personId) }

            val winner = when {
                claiming.isEmpty() -> record.eventId
                // Deepest wins, and where two sit at the same depth the later window does — that
                // pair is a conflict the app should already have refused, so this only decides
                // what to show while it is being repaired. Deterministic beats arbitrary.
                else -> claiming
                    .sortedWith(compareBy({ it.depth }, { it.startMs }))
                    .last()
                    .eventId
            }
            record.recordId to winner
        }
    }

    /**
     * Sibling windows that could both claim a recording.
     *
     * Checked between siblings only. Nesting is not a conflict — a set inside a day is the ordinary
     * case and rule 1 already resolves it by depth. Two events at the same level overlapping in
     * both time *and* people genuinely has no answer, and is what the app refuses at the point of
     * creation rather than resolving silently later.
     */
    fun conflicts(
        events: List<EventEntity>,
        windowPeople: Map<Long, Set<Long>>
    ): List<Conflict> {
        val windows = windowsOf(events, windowPeople).associateBy { it.eventId }
        val found = mutableListOf<Conflict>()

        events.groupBy { it.parentId }.values.forEach { siblings ->
            val theirWindows = siblings.mapNotNull { windows[it.eventId] }
            for (i in theirWindows.indices) {
                for (j in i + 1 until theirWindows.size) {
                    if (theirWindows[i].collidesWith(theirWindows[j])) {
                        found += Conflict(theirWindows[i].eventId, theirWindows[j].eventId)
                    }
                }
            }
        }
        return found
    }

    /**
     * Whether giving [eventId] this window would collide with one of its siblings.
     *
     * The check a window editor runs before saving, so the refusal can name what it collided with
     * rather than reporting that something went wrong.
     */
    fun wouldCollide(
        events: List<EventEntity>,
        windowPeople: Map<Long, Set<Long>>,
        eventId: Long,
        parentId: Long?,
        startMs: Long,
        endMs: Long,
        people: Set<Long>
    ): Long? {
        val proposed = Window(eventId, startMs, endMs, 0, people)
        return windowsOf(events, windowPeople)
            .firstOrNull { existing ->
                existing.eventId != eventId &&
                    events.firstOrNull { it.eventId == existing.eventId }?.parentId == parentId &&
                    proposed.collidesWith(existing)
            }
            ?.eventId
    }
}
