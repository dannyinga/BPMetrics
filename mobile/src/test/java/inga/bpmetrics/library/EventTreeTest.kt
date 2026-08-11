package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one walk of the event tree.
 *
 * Written against the bug this whole initiative exists to end: four times now, a count of what a
 * container held has disagreed with what it actually held, because the walk was rewritten wherever
 * it was needed. Everything here is a property some call site got wrong at least once.
 */
class EventTreeTest {

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

    /** Griztronics → two days → two sets on day one. */
    private fun festival() = listOf(
        event(1, "Griztronics"),
        event(2, "Day 1", parent = 1, from = at(10)),
        event(3, "Day 2", parent = 1, from = at(34)),
        event(4, "Subtronics", parent = 2, from = at(21)),
        event(5, "Excision", parent = 2, from = at(22))
    )

    @Test
    fun `a subtree includes its own root`() {
        // The export scope did not, which is how a collection holding only collections exported
        // nothing at all.
        assertTrue(EventTree.descendantsOf(festival(), 1).contains(1L))
    }

    @Test
    fun `a subtree reaches every level, not just the first`() {
        // The "0 events" defect: counting direct children only, three levels deep.
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), EventTree.descendantsOf(festival(), 1))
    }

    @Test
    fun `a subtree of a leaf is just the leaf`() {
        assertEquals(setOf(4L), EventTree.descendantsOf(festival(), 4))
    }

    @Test
    fun `a subtree of an unknown event is empty of anything but itself`() {
        assertEquals(setOf(99L), EventTree.descendantsOf(festival(), 99))
    }

    @Test
    fun `ancestry runs innermost first`() {
        // The order nearest-wins inheritance depends on: the first entry carrying a tag applies.
        assertEquals(
            listOf(4L, 2L, 1L),
            EventTree.ancestryOf(festival(), 4).map { it.eventId }
        )
    }

    @Test
    fun `children come back in the order they happened`() {
        assertEquals(
            listOf(4L, 5L),
            EventTree.childrenOf(festival(), 2).map { it.eventId }
        )
    }

    @Test
    fun `flatten reads as the tree does`() {
        val flat = EventTree.flatten(festival())

        assertEquals(listOf(1L, 2L, 4L, 5L, 3L), flat.map { it.event.eventId })
        assertEquals(listOf(0, 1, 2, 2, 1), flat.map { it.depth })
    }

    @Test
    fun `flatten covers every event exactly once`() {
        val flat = EventTree.flatten(festival())

        assertEquals(festival().size, flat.size)
        assertEquals(flat.size, flat.map { it.event.eventId }.distinct().size)
    }

    // --- Cycles, which should be impossible and must not hang ---

    @Test
    fun `a cycle does not hang the descendant walk`() {
        val cyclic = listOf(
            event(1, "A", parent = 3),
            event(2, "B", parent = 1),
            event(3, "C", parent = 2)
        )

        assertEquals(setOf(1L, 2L, 3L), EventTree.descendantsOf(cyclic, 1))
    }

    @Test
    fun `a cycle does not hang the ancestry walk`() {
        val cyclic = listOf(
            event(1, "A", parent = 3),
            event(2, "B", parent = 1),
            event(3, "C", parent = 2)
        )

        assertEquals(3, EventTree.ancestryOf(cyclic, 1).size)
    }

    @Test
    fun `an event that is its own parent does not hang`() {
        val self = listOf(event(1, "A", parent = 1))

        assertEquals(setOf(1L), EventTree.descendantsOf(self, 1))
        assertEquals(1, EventTree.ancestryOf(self, 1).size)
    }

    @Test
    fun `an event orphaned by a cycle still appears in the list`() {
        // Otherwise it is unreachable in the UI and cannot be repaired by the person looking at it.
        val cyclic = listOf(
            event(1, "Top"),
            event(2, "B", parent = 3),
            event(3, "C", parent = 2)
        )

        assertEquals(setOf(1L, 2L, 3L), EventTree.flatten(cyclic).map { it.event.eventId }.toSet())
    }

    @Test
    fun `moving an event inside its own descendant is refused`() {
        assertTrue(EventTree.wouldCycle(festival(), eventId = 1, candidateParent = 4))
        assertTrue(EventTree.wouldCycle(festival(), eventId = 1, candidateParent = 1))
    }

    @Test
    fun `moving an event somewhere unrelated is allowed`() {
        assertFalse(EventTree.wouldCycle(festival(), eventId = 4, candidateParent = 3))
        assertFalse(EventTree.wouldCycle(festival(), eventId = 4, candidateParent = null))
    }

    // --- Spans ---

    @Test
    fun `a window is the span, whatever the recordings did`() {
        // A day that ends at midnight ends at midnight, however long a watch was left running.
        val span = EventTree.spanOf(festival(), 2, emptyList(), emptyMap())

        assertEquals(at(10), span?.startMs)
    }

    @Test
    fun `an event with no window takes the span of what is beneath it`() {
        val records = listOf(
            BpmRecordEntity(
                recordId = 10, title = "a", date = at(21),
                startTime = at(21), endTime = at(22), durationMs = 3_600_000L
            ),
            BpmRecordEntity(
                recordId = 11, title = "b", date = at(35),
                startTime = at(35), endTime = at(36), durationMs = 3_600_000L
            )
        )
        val membership = mapOf(10L to 4L, 11L to 3L)

        val span = EventTree.spanOf(festival(), 1, records, membership)

        assertEquals(at(21), span?.startMs)
        assertEquals(at(36), span?.endMs)
    }

    @Test
    fun `an empty event with no window has no span`() {
        val empty = listOf(event(1, "Nothing yet"))

        assertEquals(null, EventTree.spanOf(empty, 1, emptyList(), emptyMap()))
    }

    // --- Flattening a tree into rows, collapsed and reversed ---

    @Test
    fun `everything shows when nothing says otherwise`() {
        assertEquals(
            listOf("Griztronics", "Day 1", "Subtronics", "Excision", "Day 2"),
            EventTree.flatten(festival()).map { it.event.name }
        )
    }

    @Test
    fun `collapsed shows the tops and nothing under them`() {
        assertEquals(
            listOf("Griztronics"),
            EventTree.flatten(festival(), expanded = emptySet()).map { it.event.name }
        )
    }

    @Test
    fun `opening one level opens only that level`() {
        assertEquals(
            listOf("Griztronics", "Day 1", "Day 2"),
            EventTree.flatten(festival(), expanded = setOf(1L)).map { it.event.name }
        )
    }

    /**
     * The trap collapsing sets, and the reason this is tested rather than eyeballed.
     *
     * The walk kept one set of ids for "emitted", and a recovery pass appended everything not in
     * it — there so a cycle cannot make an event unreachable in the UI. With collapsing, *most* of
     * the tree is legitimately unemitted, so that pass would have dumped every collapsed child at
     * the top level: the exact opposite of collapsing, and it would have looked like the feature
     * simply did not work.
     */
    @Test
    fun `collapsed children are not swept up by the cycle recovery`() {
        val rows = EventTree.flatten(festival(), expanded = emptySet())

        assertEquals(1, rows.size)
        assertTrue(rows.none { it.event.name == "Subtronics" })
    }

    @Test
    fun `a row says whether opening it would reveal anything`() {
        val rows = EventTree.flatten(festival()).associateBy { it.event.name }

        assertTrue(rows.getValue("Griztronics").hasChildren)
        assertTrue(rows.getValue("Day 1").hasChildren)
        // A chevron here would expand into nothing, which reads as a broken row.
        assertFalse(rows.getValue("Excision").hasChildren)
    }

    @Test
    fun `newest first reverses each level and keeps parents above children`() {
        assertEquals(
            listOf("Griztronics", "Day 2", "Day 1", "Excision", "Subtronics"),
            EventTree.flatten(festival(), newestFirst = true).map { it.event.name }
        )
    }

    /** An event a cycle keeps out of the walk still has to appear, collapsed or not. */
    @Test
    fun `an unreachable event still appears`() {
        val tangled = festival() + event(9, "Orphan", parent = 99)

        assertTrue(
            EventTree.flatten(tangled, expanded = emptySet())
                .any { it.event.name == "Orphan" }
        )
    }
}
