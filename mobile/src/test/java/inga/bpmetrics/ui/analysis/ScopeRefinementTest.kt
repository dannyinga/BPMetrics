package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an analysis leaves out, and why.
 *
 * Two mechanisms that look the same on screen and mean different things. Unticking a row is a fact
 * about *this analysis* — "not what I want to look at right now" — and dies with it unless the
 * analysis is saved. `excludedFromParentAnalysis` is a standing fact about the *event*: the merch
 * queue is genuinely part of the day and genuinely not part of what the day's average should mean,
 * said once instead of at every roll-up.
 *
 * Keeping them apart matters because they compose: an event out by its flag has to be tickable back
 * in, or the box springs back and looks broken.
 */
class ScopeRefinementTest {

    private val day = 1_700_000_000_000L
    private fun at(hours: Int) = day + hours * 3_600_000L

    private fun event(
        id: Long,
        name: String,
        parent: Long? = null,
        from: Long? = null,
        excluded: Boolean = false
    ) = EventEntity(
        eventId = id,
        name = name,
        parentId = parent,
        windowStart = from,
        windowEnd = from?.plus(3_600_000L),
        createdAt = from ?: id,
        excludedFromParentAnalysis = excluded
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

    /** Griztronics › Day 1 › (Subtronics, Merch queue), plus a loose recording on Day 1. */
    private fun festival() = listOf(
        event(1, "Griztronics"),
        event(2, "Day 1", parent = 1, from = at(10)),
        event(3, "Subtronics", parent = 2, from = at(21)),
        event(4, "Merch queue", parent = 2, from = at(15), excluded = true)
    )

    private fun records() = listOf(
        recording(10, at(21), filedAs = 3),
        recording(11, at(22), filedAs = 3),
        recording(12, at(15), filedAs = 4),
        recording(13, at(12), filedAs = 2)
    )

    private fun items() = records().map {
        ScopeRefinement.ScopeItem(
            recordId = it.recordId,
            eventId = it.eventId,
            title = it.title,
            startTime = it.startTime
        )
    }

    private fun idsIn(rootId: Long?, exclusions: ScopeExclusions = ScopeExclusions()) =
        ScopeRefinement.recordsIn(festival(), records(), rootId, exclusions)
            .map { it.recordId }
            .toSet()

    // --- Everything included by default (TX-3.6) ---

    @Test
    fun `a scope covers its whole subtree`() {
        // Everything except the merch queue, which is out by its own flag.
        assertEquals(setOf(10L, 11L, 13L), idsIn(1))
    }

    @Test
    fun `an ordinary event with no flags includes everything beneath it`() {
        val plain = listOf(event(1, "Griztronics"), event(2, "Day 1", parent = 1))
        val recs = listOf(recording(10, at(1), filedAs = 2), recording(11, at(2), filedAs = 1))

        assertEquals(
            setOf(10L, 11L),
            ScopeRefinement.recordsIn(plain, recs, 1).map { it.recordId }.toSet()
        )
    }

    // --- Manual exclusion (TX-3.6) ---

    @Test
    fun `excluding an event excludes its subtree`() {
        // Leaving out Day 1 but keeping the sets inside it would be a scope nobody asked for.
        assertEquals(emptySet<Long>(), idsIn(1, ScopeExclusions(excludedEventIds = setOf(2))))
    }

    @Test
    fun `excluding one child leaves its siblings alone`() {
        assertEquals(setOf(13L), idsIn(1, ScopeExclusions(excludedEventIds = setOf(3))))
    }

    @Test
    fun `a single recording can be excluded on its own`() {
        assertEquals(setOf(11L, 13L), idsIn(1, ScopeExclusions(excludedRecordIds = setOf(10))))
    }

    // --- The standing flag (TX-3.7) ---

    @Test
    fun `a flagged event is left out of its parent's analysis`() {
        assertFalse(12L in idsIn(1))
        assertFalse(12L in idsIn(2))
    }

    @Test
    fun `a flagged event analysed directly still shows itself`() {
        // The flag says it is not part of the day's average, not that it does not exist.
        assertEquals(setOf(12L), idsIn(4))
    }

    @Test
    fun `a flagged event can be counted anyway, just this once`() {
        assertEquals(
            setOf(10L, 11L, 12L, 13L),
            idsIn(1, ScopeExclusions(includedDespiteFlag = setOf(4)))
        )
    }

    @Test
    fun `a flagged event takes its subtree out with it`() {
        val nested = festival() + event(5, "Queue photos", parent = 4, from = at(15))
        val recs = records() + recording(14, at(15), filedAs = 5)

        assertFalse(
            14L in ScopeRefinement.recordsIn(nested, recs, 1).map { it.recordId }.toSet()
        )
    }

    // --- The refinement sheet (TX-3.6) ---

    @Test
    fun `the sheet nests the whole subtree`() {
        // One level was wrong. A festival offered its days and no way to reach the set that
        // actually needs leaving out, and a recording filed three levels down was unreachable.
        val entries = ScopeRefinement.entriesFor(festival(), items(), 1)

        // A row's own recordings come directly under it, before its child events. Emitting them
        // after the whole nested subtree left Day 1's loose recording sitting below Subtronics'
        // two, where it read as belonging to Subtronics.
        assertEquals(
            listOf("Day 1", "Record 13", "Merch queue", "Record 12", "Subtronics", "Record 10", "Record 11"),
            entries.map { it.label }
        )
    }

    @Test
    fun `depth follows the tree`() {
        val entries = ScopeRefinement.entriesFor(festival(), items(), 1)
        val depths = entries.associate { it.label to it.depth }

        assertEquals(0, depths["Day 1"])
        assertEquals(1, depths["Subtronics"])
        // A recording sits under whatever it is filed on, wherever that is.
        assertEquals(2, depths["Record 10"])
        assertEquals(1, depths["Record 13"])
    }

    @Test
    fun `the sheet lists loose recordings alongside the events`() {
        val entries = ScopeRefinement.entriesFor(festival(), items(), 2)

        assertEquals(
            listOf("Merch queue", "Record 12", "Subtronics", "Record 10", "Record 11", "Record 13"),
            entries.map { it.label }
        )
    }

    @Test
    fun `a row counts everything beneath it`() {
        val entries = ScopeRefinement.entriesFor(festival(), items(), 1)

        // Day 1 holds two sets and a loose recording: four recordings in total.
        assertEquals(4, entries.first { it.label == "Day 1" }.recordCount)
    }

    @Test
    fun `everything is ticked by default`() {
        val entries = ScopeRefinement.entriesFor(festival(), items(), 1)

        // Except the merch queue, which is out by its own standing flag rather than by a choice
        // anyone made here — and Record 12, which is inside the merch queue.
        val out = setOf("Merch queue", "Record 12")
        assertTrue(entries.filterNot { it.label in out }.all { it.isIncluded })
    }

    // --- Excluding something takes everything under it (TX-3.6) ---

    @Test
    fun `unticking an event unticks its whole subtree`() {
        // The rule was always in the numbers. The sheet drew the children ticked, which described
        // a scope that did not exist — Day 1 out, and its six sets apparently still in.
        val entries = ScopeRefinement.entriesFor(
            festival(),
            items(),
            1,
            ScopeExclusions(excludedEventIds = setOf(2))
        )

        assertTrue(entries.none { it.isIncluded })
        // Only Day 1 itself was unticked by hand. Everything below it is out *with* it, which the
        // sheet says rather than showing an identical-looking empty box.
        assertFalse(entries.first { it.label == "Day 1" }.excludedByAncestor)
        assertTrue(entries.first { it.label == "Subtronics" }.excludedByAncestor)
        assertTrue(entries.first { it.label == "Record 10" }.excludedByAncestor)
    }

    @Test
    fun `a recording inside a flagged event is out with it`() {
        val entries = ScopeRefinement.entriesFor(festival(), items(), 1)

        val record = entries.first { it.label == "Record 12" }
        assertFalse(record.isIncluded)
        assertTrue(record.excludedByAncestor)
    }

    @Test
    fun `depth is what makes the sheet readable`() {
        // Every row carries the level it sits at, and the dialog indents by it. Without this the
        // sheet is one flat column of checkboxes with nothing saying what belongs to what.
        val entries = ScopeRefinement.entriesFor(festival(), items(), 1)

        assertEquals(
            listOf(0, 1, 1, 2, 1, 2, 2),
            entries.map { it.depth }
        )
    }

    @Test
    fun `a flagged event shows as unticked, and says why`() {
        val entries = ScopeRefinement.entriesFor(festival(), items(), 2)
        val queue = entries.first { it.label == "Merch queue" }

        assertFalse(queue.isIncluded)
        assertTrue(queue.excludedByFlag)
    }

    @Test
    fun `an event unticked by hand is not reported as flagged`() {
        val entries = ScopeRefinement.entriesFor(
            festival(), items(), 2, ScopeExclusions(excludedEventIds = setOf(3))
        )
        val set = entries.first { it.label == "Subtronics" }

        assertFalse(set.isIncluded)
        assertFalse(set.excludedByFlag)
    }

    // --- Ticking (TX-3.6) ---

    @Test
    fun `unticking an event records it`() {
        val entries = ScopeRefinement.entriesFor(festival(), items(), 1)

        val after = ScopeRefinement.toggle(
            ScopeExclusions(),
            entries.first { it.label == "Day 1" },
            include = false
        )

        assertEquals(setOf(2L), after.excludedEventIds)
    }

    @Test
    fun `ticking a flagged event back in overrides the flag`() {
        // Otherwise the box springs back and looks broken.
        val entries = ScopeRefinement.entriesFor(festival(), items(), 2)
        val queue = entries.first { it.label == "Merch queue" }

        val after = ScopeRefinement.toggle(ScopeExclusions(), queue, include = true)

        assertEquals(setOf(4L), after.includedDespiteFlag)
        assertTrue(12L in idsIn(1, after))
    }

    @Test
    fun `unticking a flagged event that was overridden drops the override`() {
        val overridden = ScopeExclusions(includedDespiteFlag = setOf(4))
        val entries = ScopeRefinement.entriesFor(festival(), items(), 2, overridden)
        val queue = entries.first { it.label == "Merch queue" }

        val after = ScopeRefinement.toggle(overridden, queue, include = false)

        assertTrue(after.includedDespiteFlag.isEmpty())
        assertFalse(12L in idsIn(1, after))
    }

    @Test
    fun `ticking and unticking returns to where it started`() {
        val entries = ScopeRefinement.entriesFor(festival(), items(), 1)
        val row = entries.first { it.label == "Day 1" }

        val off = ScopeRefinement.toggle(ScopeExclusions(), row, include = false)
        val on = ScopeRefinement.toggle(off, row, include = true)

        assertTrue(on.isEmpty)
    }

    @Test
    fun `unticking a recording records it separately from events`() {
        val entries = ScopeRefinement.entriesFor(festival(), items(), 2)
        // The one filed on Day 1 itself, which sits at the end after the nested sets.
        val loose = entries.first { it.recordId != null && it.depth == 0 }

        val after = ScopeRefinement.toggle(ScopeExclusions(), loose, include = false)

        assertEquals(setOf(13L), after.excludedRecordIds)
        assertTrue(after.excludedEventIds.isEmpty())
    }

    // --- Degenerate input ---

    @Test
    fun `a scope with no root covers the whole library`() {
        // A filter-based analysis has no container. Everything is in play, loose recordings too.
        assertEquals(setOf(10L, 11L, 13L), idsIn(null))
    }

    @Test
    fun `an unknown root covers nothing but itself`() {
        assertTrue(idsIn(99).isEmpty())
    }

    // --- No root: a collection, a filter, or a saved analysis ---

    @Test
    fun `with no root the sheet starts from whatever the scope reaches`() {
        // A collection holds unrelated branches and a filter holds whatever it matched, so there is
        // no single level to take. The tops are the events whose parent is not itself in scope —
        // which for this fixture is the festival, since its whole subtree is present.
        val entries = ScopeRefinement.entriesFor(festival(), items(), rootId = null)

        assertEquals(
            listOf("Griztronics", "Day 1", "Merch queue", "Subtronics"),
            entries.filter { it.eventId != null }.map { it.label }
        )
    }

    @Test
    fun `the flat sheet still honours the standing flag`() {
        val entries = ScopeRefinement.entriesFor(festival(), items(), rootId = null)
        val queue = entries.first { it.label == "Merch queue" }

        assertFalse(queue.isIncluded)
        assertTrue(queue.excludedByFlag)
    }

    @Test
    fun `a saved analysis with no tree still lists its events`() {
        // Nothing but the snapshot: the events may since have been rearranged or deleted, and the
        // rows still have to be listable and untickable.
        val snapshot = listOf(
            ScopeRefinement.ScopeItem(10, 3, "Record 10", at(21), "Subtronics  |  Griztronics"),
            ScopeRefinement.ScopeItem(11, 4, "Record 11", at(15), "Excision  |  Griztronics")
        )

        val entries = ScopeRefinement.entriesFor(emptyList(), snapshot, rootId = null)

        // Its events, each with the recording that named it underneath — the label the snapshot
        // carries is the only structure there is, and it is enough to tick a set off.
        assertEquals(
            listOf(
                "Excision  |  Griztronics", "Record 11",
                "Subtronics  |  Griztronics", "Record 10"
            ),
            entries.map { it.label }
        )
        assertTrue(entries.all { it.isIncluded })
    }

    @Test
    fun `an empty library refines to nothing`() {
        assertTrue(ScopeRefinement.recordsIn(emptyList(), emptyList(), 1).isEmpty())
        assertTrue(ScopeRefinement.entriesFor(emptyList(), emptyList(), 1).isEmpty())
    }
}
