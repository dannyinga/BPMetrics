package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the library reads in.
 *
 * "Why is this above that" is the one question a list cannot answer for itself. Every rule here is
 * invisible on screen and obvious when it is wrong, which is exactly the kind of thing that gets
 * reimplemented slightly differently somewhere else — the failure this whole initiative exists to
 * stop. So the order is decided by one pure function, and asserted here.
 */
class LibraryTimelineTest {

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

    private fun recording(id: Long, startsAt: Long, filedAs: Long? = null) = BpmRecordEntity(
        recordId = id,
        title = "Record $id",
        date = startsAt,
        startTime = startsAt,
        endTime = startsAt + 600_000L,
        durationMs = 600_000L,
        eventId = filedAs
    )

    private fun names(rows: List<TimelineRow>) = rows.map {
        when (val e = it.entry) {
            is TimelineEntry.Event -> e.event.name
            is TimelineEntry.Recording -> e.record.title
        }
    }

    /** Griztronics › Day 1 › two sets, plus Day 2. */
    private fun festival() = listOf(
        event(1, "Griztronics"),
        event(2, "Day 1", parent = 1, from = at(10), to = at(26)),
        event(3, "Subtronics", parent = 2, from = at(21), to = at(22)),
        event(4, "Excision", parent = 2, from = at(23), to = at(24)),
        event(5, "Day 2", parent = 1, from = at(34), to = at(50))
    )

    // --- What is shown ---

    @Test
    fun `closed containers show only the top level`() {
        val rows = LibraryTimeline.build(festival(), emptyList())

        assertEquals(listOf("Griztronics"), names(rows))
        assertEquals(0, rows.single().depth)
        assertTrue(rows.single().hasChildren)
    }

    @Test
    fun `opening a container reveals one level, not all of them`() {
        val rows = LibraryTimeline.build(festival(), emptyList(), expandedIds = setOf(1L))

        assertEquals(listOf("Griztronics", "Day 1", "Day 2"), names(rows))
        assertEquals(listOf(0, 1, 1), rows.map { it.depth })
    }

    @Test
    fun `opening every level reads as the tree`() {
        val rows = LibraryTimeline.build(festival(), emptyList(), setOf(1L, 2L, 5L))

        assertEquals(
            listOf("Griztronics", "Day 1", "Subtronics", "Excision", "Day 2"),
            names(rows)
        )
        assertEquals(listOf(0, 1, 2, 2, 1), rows.map { it.depth })
    }

    @Test
    fun `an event with nothing in it does not offer to open`() {
        // A chevron that reveals nothing is worse than no chevron: it reads as a broken row.
        val rows = LibraryTimeline.build(listOf(event(1, "Empty")), emptyList())

        assertFalse(rows.single().hasChildren)
    }

    @Test
    fun `a container holding only recordings offers to open`() {
        val rows = LibraryTimeline.build(
            listOf(event(1, "Subtronics", from = at(21), to = at(22))),
            listOf(recording(10, at(21, 15), filedAs = 1))
        )

        assertTrue(rows.single().hasChildren)
    }

    // --- Order ---

    @Test
    fun `the top level reads newest first`() {
        // Scanning the library means looking for the most recent night.
        val events = listOf(
            event(1, "Older", from = at(0), to = at(2)),
            event(2, "Newer", from = at(40), to = at(42))
        )

        assertEquals(listOf("Newer", "Older"), names(LibraryTimeline.build(events, emptyList())))
    }

    @Test
    fun `inside a container reads oldest first`() {
        // Opening a festival means reading Day 1 then Day 2. The two directions are deliberate and
        // are what the app's two separate lists already did.
        val rows = LibraryTimeline.build(festival(), emptyList(), setOf(1L))

        assertEquals(listOf("Griztronics", "Day 1", "Day 2"), names(rows))
    }

    @Test
    fun `an event with no window sorts by what is inside it`() {
        // Griztronics has no window of its own; it is as early as Day 1.
        val events = listOf(
            event(1, "Griztronics"),
            event(2, "Day 1", parent = 1, from = at(10), to = at(26)),
            event(9, "Something later", from = at(100), to = at(102))
        )

        assertEquals(
            listOf("Something later", "Griztronics"),
            names(LibraryTimeline.build(events, emptyList()))
        )
    }

    @Test
    fun `an event with a window keeps it whatever its recordings did`() {
        // A day that ends at midnight ends at midnight, however long a watch was left running.
        val events = listOf(
            event(1, "Set", from = at(21), to = at(22)),
            event(2, "Later set", from = at(23), to = at(24))
        )
        // A recording inside the later set that started absurdly early would move it if the window
        // were ignored.
        val records = listOf(recording(10, at(1), filedAs = 2))

        assertEquals(listOf("Later set", "Set"), names(LibraryTimeline.build(events, records)))
    }

    @Test
    fun `an empty event sorts last rather than to the epoch`() {
        // No window and nothing inside means the library cannot say when it happened. Zero would
        // bury it under everything that ever did.
        val events = listOf(
            event(1, "Nothing yet"),
            event(2, "Real", from = at(10), to = at(12))
        )

        assertEquals(listOf("Nothing yet", "Real"), names(LibraryTimeline.build(events, emptyList())))
    }

    // --- Loose recordings, which is the point of the view ---

    @Test
    fun `an unfiled recording sits in the timeline, not in a section at the bottom`() {
        val events = listOf(
            event(1, "Morning", from = at(8), to = at(9)),
            event(2, "Evening", from = at(20), to = at(21))
        )
        val loose = listOf(recording(10, at(14)))

        assertEquals(
            listOf("Evening", "Record 10", "Morning"),
            names(LibraryTimeline.build(events, loose))
        )
    }

    @Test
    fun `a recording filed onto a container appears among that container's events`() {
        // The walk between stages. It belongs to Day 1, not to any set inside it, and it belongs
        // between them in time.
        val records = listOf(recording(10, at(22, 30), filedAs = 2))

        val rows = LibraryTimeline.build(festival(), records, setOf(1L, 2L))

        assertEquals(
            listOf("Griztronics", "Day 1", "Subtronics", "Record 10", "Excision", "Day 2"),
            names(rows)
        )
    }

    @Test
    fun `recordings inside an event read oldest first`() {
        val events = listOf(event(1, "Set", from = at(21), to = at(23)))
        val records = listOf(recording(10, at(22)), recording(11, at(21, 10)))
            .map { it.copy(eventId = 1) }

        assertEquals(
            listOf("Set", "Record 11", "Record 10"),
            names(LibraryTimeline.build(events, records, setOf(1L)))
        )
    }

    @Test
    fun `an unfiled recording newer than everything leads the list`() {
        val events = listOf(event(1, "Old", from = at(1), to = at(2)))

        assertEquals(
            listOf("Record 10", "Old"),
            names(LibraryTimeline.build(events, listOf(recording(10, at(99)))))
        )
    }

    // --- Degenerate input, which must not hang or hide anything ---

    @Test
    fun `a cycle does not hang, and its events still appear`() {
        val cyclic = listOf(
            event(1, "A", parent = 3),
            event(2, "B", parent = 1),
            event(3, "C", parent = 2)
        )

        val rows = LibraryTimeline.build(cyclic, emptyList(), setOf(1L, 2L, 3L))

        assertEquals(setOf("A", "B", "C"), names(rows).toSet())
    }

    @Test
    fun `an event whose parent does not exist still appears`() {
        // Unreachable in the list means unrepairable through the UI.
        val orphaned = listOf(event(1, "Lost", parent = 99, from = at(10), to = at(11)))

        assertEquals(listOf("Lost"), names(LibraryTimeline.build(orphaned, emptyList())))
    }

    @Test
    fun `every event appears exactly once`() {
        val rows = LibraryTimeline.build(festival(), emptyList(), setOf(1L, 2L, 5L))
        val ids = rows.mapNotNull { (it.entry as? TimelineEntry.Event)?.event?.eventId }

        assertEquals(festival().size, ids.size)
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `a recording pointing at a deleted event still appears`() {
        // reconcileMembership clears dangling ids, but a row must not vanish in the window between
        // the delete and the reconcile — nor if that reconcile ever failed to run.
        val rows = LibraryTimeline.build(emptyList(), listOf(recording(10, at(10), filedAs = 99)))

        assertEquals(emptyList<String>(), names(rows))
    }

    @Test
    fun `an empty library is an empty list`() {
        assertEquals(emptyList<TimelineRow>(), LibraryTimeline.build(emptyList(), emptyList()))
    }

    // --- Reversing, which is what the sort control's "reverse" means for a chronology ---

    @Test
    fun `reversing reads the library from the beginning`() {
        val events = listOf(
            event(1, "Older", from = at(0), to = at(2)),
            event(2, "Newer", from = at(40), to = at(42))
        )

        assertEquals(
            listOf("Older", "Newer"),
            names(LibraryTimeline.build(events, emptyList(), newestFirst = false))
        )
    }

    @Test
    fun `reversing does not turn a festival inside out`() {
        // Only the top level flips. Day 1 before Day 2 is not a preference, it is what the
        // container is — reversing it would read a festival backwards through itself.
        val rows = LibraryTimeline.build(festival(), emptyList(), setOf(1L), newestFirst = false)

        assertEquals(listOf("Griztronics", "Day 1", "Day 2"), names(rows))
    }

    @Test
    fun `reversing still puts an orphan somewhere it can be reached`() {
        val orphaned = listOf(
            event(1, "Lost", parent = 99, from = at(10), to = at(11)),
            event(2, "Also lost", parent = 99, from = at(50), to = at(51))
        )

        assertEquals(
            listOf("Lost", "Also lost"),
            names(LibraryTimeline.build(orphaned, emptyList(), newestFirst = false))
        )
    }

    // --- Pruning, for when a filter is narrowing the library ---

    @Test
    fun `an event holding nothing that matched is dropped`() {
        // Filter to Kyle and the timeline must show where Kyle actually recorded. Leaving the rest
        // in place gives a full tree of empty containers with three recordings buried in it.
        val matched = listOf(recording(10, at(21, 15), filedAs = 3))

        val rows = LibraryTimeline.build(festival(), matched, setOf(1L, 2L), pruneEmpty = true)

        assertEquals(listOf("Griztronics", "Day 1", "Subtronics"), names(rows))
    }

    @Test
    fun `a festival survives when only something deep inside it matched`() {
        // Pruning on direct contents alone would cut a festival whose recordings all live in sets.
        val matched = listOf(recording(10, at(21, 15), filedAs = 3))

        val rows = LibraryTimeline.build(festival(), matched, emptySet(), pruneEmpty = true)

        assertEquals(listOf("Griztronics"), names(rows))
    }

    @Test
    fun `pruning never orphans a survivor to the top`() {
        // A surviving child always has a surviving parent, because the test is over the subtree.
        // Were it over direct contents, Day 1 would be cut and Subtronics rescued to depth 0.
        val matched = listOf(recording(10, at(21, 15), filedAs = 3))

        val rows = LibraryTimeline.build(festival(), matched, setOf(1L, 2L), pruneEmpty = true)

        assertEquals(listOf(0, 1, 2), rows.map { it.depth })
    }

    @Test
    fun `a loose recording that matched still appears`() {
        val rows = LibraryTimeline.build(
            festival(), listOf(recording(14, at(100))), emptySet(), pruneEmpty = true
        )

        assertEquals(listOf("Record 14"), names(rows))
    }

    @Test
    fun `nothing matching leaves an empty list rather than a bare tree`() {
        // The screen shows "nothing matches this filter" off the back of this, so a tree of empty
        // containers coming back here would present as a library that had simply lost its records.
        assertTrue(
            LibraryTimeline.build(festival(), emptyList(), setOf(1L), pruneEmpty = true).isEmpty()
        )
    }

    @Test
    fun `without pruning an empty event still shows`() {
        // What a window you have just drawn looks like before anything lands in it.
        val rows = LibraryTimeline.build(festival(), emptyList(), emptySet())

        assertEquals(listOf("Griztronics"), names(rows))
    }

    // --- Opening to a depth ---

    @Test
    fun `expanding to an event opens everything above it`() {
        assertEquals(setOf(1L, 2L, 3L), LibraryTimeline.expansionFor(festival(), 3))
    }

    @Test
    fun `expanding to a top-level event opens only itself`() {
        assertEquals(setOf(1L), LibraryTimeline.expansionFor(festival(), 1))
    }

    // --- Hiding what was never filed ---

    /**
     * "Show me what I have filed."
     *
     * A library accumulates loose recordings faster than it accumulates events — every session
     * nobody tidied up is one — and once there are two hundred of them the events they sit between
     * are unfindable, which is the opposite of what a timeline is for.
     */
    @Test
    fun `hiding the unfiled drops recordings belonging to no event`() {
        val records = listOf(
            recording(100, at(21), filedAs = 3),
            recording(200, at(30))
        )

        val rows = LibraryTimeline.build(
            festival(),
            records,
            expandedIds = emptySet(),
            includeUnfiled = false
        )

        assertTrue(rows.none { it.entry is TimelineEntry.Recording })
        assertTrue(rows.any { (it.entry as? TimelineEntry.Event)?.event?.name == "Griztronics" })
    }

    /**
     * And only the unfiled ones.
     *
     * A recording inside an event is part of that event and still appears when it is opened.
     * Hiding those as well would make expanding an event pointless, which is not what "hide the
     * unfiled" says or means.
     */
    @Test
    fun `a filed recording still shows inside its event`() {
        val records = listOf(
            recording(100, at(21), filedAs = 3),
            recording(200, at(30))
        )

        val rows = LibraryTimeline.build(
            festival(),
            records,
            expandedIds = setOf(1L, 2L, 3L),
            includeUnfiled = false
        )

        assertEquals(
            listOf(100L),
            rows.mapNotNull { (it.entry as? TimelineEntry.Recording)?.record?.recordId }
        )
    }

    @Test
    fun `showing them again brings them back`() {
        val records = listOf(recording(200, at(30)))

        val rows = LibraryTimeline.build(festival(), records, includeUnfiled = true)

        assertEquals(
            listOf(200L),
            rows.mapNotNull { (it.entry as? TimelineEntry.Recording)?.record?.recordId }
        )
    }
}
