package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a set actually contains.
 *
 * The design being guarded: a collection holds things **by reference**, and what that reference
 * covers is resolved when it is asked rather than when it was made. Storing the descendants instead
 * would freeze the answer, and filing a recording into a day the week after would silently leave it
 * out of "compare every festival" — a quietly smaller number, which is this app's recurring failure
 * mode rather than a hypothetical one.
 *
 * The other half is that a set is a *view*, not a home. Nothing here moves a recording, and nothing
 * here reads or writes membership of an event. Those are [EventMembership]'s business and a set has
 * no opinion about them.
 */
class CollectionScopeTest {

    private val day = 1_700_000_000_000L
    private fun at(hours: Int) = day + hours * 3_600_000L

    private fun event(id: Long, name: String, parent: Long? = null, from: Long? = null) =
        EventEntity(
            eventId = id,
            name = name,
            parentId = parent,
            windowStart = from,
            windowEnd = from?.plus(3_600_000L),
            createdAt = from ?: id
        )

    private fun recording(id: Long, startsAt: Long, filedAs: Long? = null) = BpmRecordEntity(
        recordId = id,
        title = "Record $id",
        date = startsAt,
        startTime = startsAt,
        endTime = startsAt + 600_000L,
        durationMs = 600_000L,
        eventId = filedAs
    )

    /** Two festivals months apart, each a day holding a set. */
    private fun library() = listOf(
        event(1, "Griztronics"),
        event(2, "Day 1", parent = 1),
        event(3, "Subtronics", parent = 2, from = at(21)),
        event(10, "Bass Canyon"),
        event(11, "Night 1", parent = 10),
        event(12, "Excision", parent = 11, from = at(2000))
    )

    private fun records() = listOf(
        recording(100, at(21), filedAs = 3),
        recording(101, at(22), filedAs = 3),
        recording(200, at(2000), filedAs = 12),
        recording(300, at(500))
    )

    private fun eventLink(c: Long, e: Long) = CollectionEventCrossRef(c, e)
    private fun recordLink(c: Long, r: Long) = CollectionRecordCrossRef(c, r)

    // --- The verify step from the product doc, as an assertion ---

    @Test
    fun `a set of two festivals months apart resolves to everything in both`() {
        val links = listOf(eventLink(1, 1), eventLink(1, 10))

        val found = CollectionScope.recordsIn(1, library(), records(), links, emptyList())

        assertEquals(setOf(100L, 200L, 101L), found.map { it.recordId }.toSet())
    }

    @Test
    fun `naming a festival reaches recordings three levels below it`() {
        // The reference is to the festival; the recordings hang off sets inside days. Resolving one
        // level down is the defect this app has had four times.
        val links = listOf(eventLink(1, 1))

        assertEquals(2, CollectionScope.recordsIn(1, library(), records(), links, emptyList()).size)
    }

    @Test
    fun `a set does not move anything`() {
        // Both festivals stay exactly where they are on the timeline. A set is a second view over
        // the same objects, not a second home for them.
        val links = listOf(eventLink(1, 1), eventLink(1, 10))
        CollectionScope.recordsIn(1, library(), records(), links, emptyList())

        assertEquals(3L, records().first { it.recordId == 100L }.eventId)
        assertEquals(1L, library().first { it.eventId == 2L }.parentId)
    }

    // --- References resolve now, not when they were made ---

    @Test
    fun `a recording filed later is in the set without anything being re-added`() {
        val links = listOf(eventLink(1, 1))
        val before = CollectionScope.recordsIn(1, library(), records(), links, emptyList())

        // Somebody files the loose recording into Day 1 afterwards.
        val after = records().map { if (it.recordId == 300L) it.copy(eventId = 2) else it }

        assertEquals(before.size + 1, CollectionScope.recordsIn(1, library(), after, links, emptyList()).size)
    }

    @Test
    fun `an event moved out of a named festival leaves the set`() {
        val links = listOf(eventLink(1, 1))
        val moved = library().map { if (it.eventId == 2L) it.copy(parentId = null) else it }

        assertTrue(CollectionScope.recordsIn(1, moved, records(), links, emptyList()).isEmpty())
    }

    // --- Many-to-many ---

    @Test
    fun `one event can be in several sets at once`() {
        // The property the old tier-based collection could not have, and the reason arbitrary
        // grouping had nowhere to live.
        val links = listOf(eventLink(1, 1), eventLink(2, 1), eventLink(3, 1))

        listOf(1L, 2L, 3L).forEach { collection ->
            assertEquals(2, CollectionScope.recordsIn(collection, library(), records(), links, emptyList()).size)
        }
    }

    @Test
    fun `sets do not see each other's members`() {
        val links = listOf(eventLink(1, 1), eventLink(2, 10))

        assertEquals(
            setOf(100L, 101L),
            CollectionScope.recordsIn(1, library(), records(), links, emptyList())
                .map { it.recordId }.toSet()
        )
    }

    // --- Recordings named directly ---

    @Test
    fun `a loose recording can be in a set on its own`() {
        val found = CollectionScope.recordsIn(
            1, library(), records(), emptyList(), listOf(recordLink(1, 300))
        )

        assertEquals(listOf(300L), found.map { it.recordId })
    }

    @Test
    fun `a recording reachable twice is counted once`() {
        // An event in a set alongside one of its own recordings. Counting it twice would make the
        // set report more than the library holds.
        val found = CollectionScope.recordsIn(
            1, library(), records(), listOf(eventLink(1, 3)), listOf(recordLink(1, 100))
        )

        assertEquals(listOf(100L, 101L), found.map { it.recordId })
    }

    // --- Degenerate input ---

    @Test
    fun `an empty set contains nothing`() {
        assertTrue(CollectionScope.recordsIn(1, library(), records(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `a reference to a deleted event contributes nothing rather than everything`() {
        val links = listOf(eventLink(1, 999))

        assertTrue(CollectionScope.recordsIn(1, library(), records(), links, emptyList()).isEmpty())
    }

    @Test
    fun `a cycle among named events does not hang`() {
        val cyclic = listOf(
            event(1, "A", parent = 3),
            event(2, "B", parent = 1),
            event(3, "C", parent = 2)
        )
        val links = listOf(eventLink(1, 1))

        assertEquals(0, CollectionScope.recordsIn(1, cyclic, emptyList(), links, emptyList()).size)
    }

    // --- What the card shows ---

    @Test
    fun `the events listed are the ones named, not their descendants`() {
        // A card saying "Griztronics, Bass Canyon" describes the set. Expanding that to every day
        // and every artist would describe the library instead.
        val links = listOf(eventLink(1, 1), eventLink(1, 10))

        assertEquals(
            setOf("Griztronics", "Bass Canyon"),
            CollectionScope.eventsIn(1, library(), links).map { it.name }.toSet()
        )
    }

    @Test
    fun `a set spanning months reports both ends`() {
        val links = listOf(eventLink(1, 1), eventLink(1, 10))
        val found = CollectionScope.recordsIn(1, library(), records(), links, emptyList())

        val span = CollectionScope.spanOf(found)!!

        assertEquals(at(21), span.startMs)
        assertEquals(at(2000) + 600_000L, span.endMs)
    }

    @Test
    fun `a set holding nothing has no span`() {
        assertNull(CollectionScope.spanOf(emptyList()))
    }

    @Test
    fun `a picker can tell whether a set already names an event`() {
        val links = listOf(eventLink(1, 1))

        assertTrue(CollectionScope.holdsEvent(1, 1, links))
        assertTrue(!CollectionScope.holdsEvent(1, 10, links))
        assertTrue(!CollectionScope.holdsEvent(2, 1, links))
    }
}
