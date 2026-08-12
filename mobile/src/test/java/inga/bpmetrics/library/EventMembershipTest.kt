package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which event a recording belongs to.
 *
 * A membership bug does not crash. It produces a total that is quietly wrong — a day's average with
 * the merch queue in it, a festival reporting zero sets — and a plausible number is one nobody looks
 * at twice. Four bugs in this codebase were exactly that, and the reason they survived is that the
 * rule lived at the call sites and could not be asserted anywhere.
 *
 * This is the rule, asserted.
 */
class EventMembershipTest {

    private val day = 1_700_000_000_000L
    private fun at(hours: Int, minutes: Int = 0) = day + hours * 3_600_000L + minutes * 60_000L

    private fun event(
        id: Long,
        name: String,
        parent: Long? = null,
        from: Long? = null,
        to: Long? = null
    ) = EventEntity(
        eventId = id,
        name = name,
        parentId = parent,
        windowStart = from,
        windowEnd = to,
        createdAt = from ?: id
    )

    private fun recording(id: Long, startsAt: Long, person: Long? = null, filedAs: Long? = null) =
        BpmRecordEntity(
            recordId = id,
            title = "Record $id",
            date = startsAt,
            startTime = startsAt,
            endTime = startsAt + 600_000L,
            durationMs = 600_000L,
            personId = person,
            eventId = filedAs
        )

    // --- The core rule ---

    @Test
    fun `a recording inside a window belongs to that event`() {
        val events = listOf(event(1, "Subtronics", from = at(21), to = at(22, 30)))
        val records = listOf(recording(10, at(21, 15)))

        val membership = EventMembership.resolve(events, emptyMap(), records)

        assertEquals(1L, membership[10L])
    }

    @Test
    fun `the deepest window wins`() {
        // A set inside a day inside a festival. All three contain the recording; the set holds it.
        val events = listOf(
            event(1, "Griztronics", from = at(0), to = at(48)),
            event(2, "Day 1", parent = 1, from = at(10), to = at(26)),
            event(3, "Subtronics", parent = 2, from = at(21), to = at(22, 30))
        )
        val records = listOf(recording(10, at(21, 15)))

        assertEquals(3L, EventMembership.resolve(events, emptyMap(), records)[10L])
    }

    @Test
    fun `a recording between sets falls to the day that contains it`() {
        // The camp break: inside Day 1, inside no set. This is the case that must not be "unfiled".
        val events = listOf(
            event(1, "Griztronics", from = at(0), to = at(48)),
            event(2, "Day 1", parent = 1, from = at(10), to = at(26)),
            event(3, "Subtronics", parent = 2, from = at(21), to = at(22, 30))
        )
        val records = listOf(recording(10, at(23, 30)))

        assertEquals(2L, EventMembership.resolve(events, emptyMap(), records)[10L])
    }

    @Test
    fun `a recording outside every window is unfiled`() {
        val events = listOf(event(1, "Subtronics", from = at(21), to = at(22, 30)))
        val records = listOf(recording(10, at(9)))

        assertNull(EventMembership.resolve(events, emptyMap(), records)[10L])
    }

    @Test
    fun `a hand-filed recording outside every window keeps its filing`() {
        // Windows decide where they apply; manual filing decides everywhere else.
        val events = listOf(event(1, "Subtronics", from = at(21), to = at(22, 30)))
        val records = listOf(recording(10, at(9), filedAs = 1L))

        assertEquals(1L, EventMembership.resolve(events, emptyMap(), records)[10L])
    }

    @Test
    fun `a window overrides a stale hand filing`() {
        // Filed under one event, then a window was drawn around it by another. The window wins, or
        // "windows define membership" would be untrue whenever anything had ever been filed.
        val events = listOf(
            event(1, "Old", from = null, to = null),
            event(2, "Subtronics", from = at(21), to = at(22, 30))
        )
        val records = listOf(recording(10, at(21, 15), filedAs = 1L))

        assertEquals(2L, EventMembership.resolve(events, emptyMap(), records)[10L])
    }

    @Test
    fun `a recording is placed by where it starts`() {
        // A watch left running from one set into the next belongs to the set it began in. Placing
        // by overlap would put it in both, and the whole point is that there is one answer.
        val events = listOf(
            event(1, "Subtronics", from = at(21), to = at(22)),
            event(2, "Excision", from = at(22), to = at(23))
        )
        // Starts at 21:55, runs ten minutes, so it crosses into Excision.
        val records = listOf(recording(10, at(21, 55)))

        assertEquals(1L, EventMembership.resolve(events, emptyMap(), records)[10L])
    }

    @Test
    fun `the window boundaries are inclusive`() {
        val events = listOf(event(1, "Set", from = at(21), to = at(22)))
        val records = listOf(recording(10, at(21)), recording(11, at(22)), recording(12, at(22, 1)))

        val membership = EventMembership.resolve(events, emptyMap(), records)

        assertEquals(1L, membership[10L])
        assertEquals(1L, membership[11L])
        assertNull(membership[12L])
    }

    // --- Two stages at once ---

    @Test
    fun `overlapping windows are separated by the people they name`() {
        // The case that made refusing overlap wrong: Kyle at Subtronics, Ben at Excision, same
        // half hour, different stages.
        val events = listOf(
            event(1, "Subtronics", from = at(21), to = at(22, 30)),
            event(2, "Excision", from = at(21), to = at(22, 30))
        )
        val people = mapOf(1L to setOf(100L), 2L to setOf(200L))
        val records = listOf(
            recording(10, at(21, 15), person = 100L),
            recording(11, at(21, 15), person = 200L)
        )

        val membership = EventMembership.resolve(events, people, records)

        assertEquals(1L, membership[10L])
        assertEquals(2L, membership[11L])
    }

    @Test
    fun `a window naming nobody claims everyone`() {
        val events = listOf(event(1, "Set", from = at(21), to = at(22)))
        val records = listOf(recording(10, at(21, 15), person = 100L))

        assertEquals(1L, EventMembership.resolve(events, emptyMap(), records)[10L])
    }

    @Test
    fun `a window naming people ignores everyone else`() {
        val events = listOf(event(1, "Set", from = at(21), to = at(22)))
        val people = mapOf(1L to setOf(100L))
        val records = listOf(recording(10, at(21, 15), person = 200L))

        assertNull(EventMembership.resolve(events, people, records)[10L])
    }

    @Test
    fun `a recording with no wearer is not claimed by a window naming people`() {
        // An import with no profile behind it. It cannot be shown to be Kyle's, so a window that is
        // only Kyle's must not swallow it.
        val events = listOf(event(1, "Set", from = at(21), to = at(22)))
        val people = mapOf(1L to setOf(100L))
        val records = listOf(recording(10, at(21, 15), person = null))

        assertNull(EventMembership.resolve(events, people, records)[10L])
    }

    // --- Conflicts ---

    @Test
    fun `siblings overlapping in time and people are a conflict`() {
        val events = listOf(
            event(1, "Subtronics", from = at(21), to = at(22, 30)),
            event(2, "Excision", from = at(22), to = at(23))
        )

        assertEquals(1, EventMembership.conflicts(events, emptyMap()).size)
    }

    @Test
    fun `siblings overlapping in time but not people are not a conflict`() {
        val events = listOf(
            event(1, "Subtronics", from = at(21), to = at(22, 30)),
            event(2, "Excision", from = at(21), to = at(22, 30))
        )
        val people = mapOf(1L to setOf(100L), 2L to setOf(200L))

        assertTrue(EventMembership.conflicts(events, people).isEmpty())
    }

    @Test
    fun `nesting is not a conflict`() {
        // The ordinary case. A day contains a set; depth resolves it, so it must not be reported.
        val events = listOf(
            event(1, "Day 1", from = at(10), to = at(26)),
            event(2, "Subtronics", parent = 1, from = at(21), to = at(22, 30))
        )

        assertTrue(EventMembership.conflicts(events, emptyMap()).isEmpty())
    }

    @Test
    fun `a proposed window names what it would collide with`() {
        val events = listOf(event(1, "Subtronics", from = at(21), to = at(22, 30)))

        val collision = EventMembership.wouldCollide(
            events, emptyMap(),
            eventId = 2L, parentId = null,
            startMs = at(22), endMs = at(23), people = emptySet()
        )

        assertEquals(1L, collision)
    }

    @Test
    fun `a proposed window clear of its siblings collides with nothing`() {
        val events = listOf(event(1, "Subtronics", from = at(21), to = at(22, 30)))

        assertNull(
            EventMembership.wouldCollide(
                events, emptyMap(),
                eventId = 2L, parentId = null,
                startMs = at(23), endMs = at(24), people = emptySet()
            )
        )
    }

    @Test
    fun `an event does not collide with itself while being edited`() {
        val events = listOf(event(1, "Subtronics", from = at(21), to = at(22, 30)))

        assertNull(
            EventMembership.wouldCollide(
                events, emptyMap(),
                eventId = 1L, parentId = null,
                startMs = at(21), endMs = at(23), people = emptySet()
            )
        )
    }

    // --- Degenerate input ---

    @Test
    fun `an event with only half a window claims nothing`() {
        val events = listOf(event(1, "Half", from = at(21), to = null))
        val records = listOf(recording(10, at(21, 15)))

        assertNull(EventMembership.resolve(events, emptyMap(), records)[10L])
    }

    @Test
    fun `a backwards window claims nothing rather than everything`() {
        val events = listOf(event(1, "Backwards", from = at(22), to = at(21)))
        val records = listOf(recording(10, at(21, 30)))

        assertNull(EventMembership.resolve(events, emptyMap(), records)[10L])
    }

    @Test
    fun `every recording gets an answer`() {
        val events = listOf(event(1, "Set", from = at(21), to = at(22)))
        val records = listOf(recording(10, at(21, 15)), recording(11, at(9)))

        val membership = EventMembership.resolve(events, emptyMap(), records)

        assertEquals(2, membership.size)
        assertNotNull(membership)
        assertTrue(membership.containsKey(11L))
    }
}
