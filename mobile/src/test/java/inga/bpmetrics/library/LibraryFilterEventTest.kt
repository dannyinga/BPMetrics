package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filtering by event means filtering by the *subtree*.
 *
 * The seventh instance in this codebase of one mistake: reading "which event is this in" as the
 * event a recording is filed **directly** on. A recording hangs off the deepest thing that claims
 * it, so a festival almost never holds any itself — its days do, or its sets do. Every previous
 * instance produced a plausible wrong number rather than a failure: a count, an export scope, a
 * picker, a page.
 *
 * Here it produced an empty smart collection. Choosing a person and a festival, to gather what they
 * did across it, matched the recordings filed on the festival itself — which is usually none.
 */
class LibraryFilterEventTest {

    private val festival = EventEntity(eventId = 1, name = "Bass Canyon")
    private val dayTwo = EventEntity(eventId = 2, name = "Day 2", parentId = 1)
    private val theSet = EventEntity(eventId = 3, name = "Excision", parentId = 2)
    private val elsewhere = EventEntity(eventId = 4, name = "Griztronics")

    private val events = listOf(festival, dayTwo, theSet, elsewhere)

    private fun record(id: Long, event: Long?, person: Long? = null) = BpmRecord(
        metadata = BpmRecordEntity(
            recordId = id,
            title = "Record $id",
            date = id * 1000,
            startTime = id * 1000,
            endTime = id * 1000 + 100,
            durationMs = 100,
            eventId = event,
            personId = person
        ),
        minDataPoint = null,
        maxDataPoint = null
    )

    private val context = filterContextOf(events = events, places = emptyList())

    /** Two levels down still counts as "at Bass Canyon", because it is. */
    @Test
    fun `an event term reaches the whole subtree`() {
        val records = listOf(
            record(1, event = 3),   // a set inside Day 2 inside the festival
            record(2, event = 2),   // filed on the day
            record(3, event = 1),   // filed on the festival itself
            record(4, event = 4),   // a different festival
            record(5, event = null) // unfiled
        )

        val matched = LibraryFilter.apply(
            records = records,
            filter = FilterState(selectedEventIds = setOf(1L)),
            context = context
        ).map { it.metadata.recordId }

        assertEquals(listOf(1L, 2L, 3L), matched)
    }

    /** The case reported: a person *and* an event, where their recordings are all nested. */
    @Test
    fun `a person and an event together reach the subtree`() {
        val colin = 10L
        val someoneElse = 11L
        val records = listOf(
            record(1, event = 3, person = colin),
            record(2, event = 3, person = someoneElse),
            record(3, event = 4, person = colin)
        )

        val matched = LibraryFilter.apply(
            records = records,
            filter = FilterState(selectedEventIds = setOf(1L), selectedPersonIds = setOf(colin)),
            context = context
        ).map { it.metadata.recordId }

        assertEquals(listOf(1L), matched)
    }

    /** Narrowing to the day still excludes what happened on the other days. */
    @Test
    fun `choosing a day does not reach back up to the festival`() {
        val records = listOf(
            record(1, event = 3),
            record(2, event = 1),
            record(3, event = 4)
        )

        val matched = LibraryFilter.apply(
            records = records,
            filter = FilterState(selectedEventIds = setOf(2L)),
            context = context
        ).map { it.metadata.recordId }

        assertEquals(listOf(1L), matched)
    }

    /**
     * With no tree supplied, the direct comparison stands.
     *
     * Some callers filter a snapshot with no registry behind them, and answering "no ancestry known"
     * with "matches nothing" is how three other terms silently emptied a collection once already.
     */
    @Test
    fun `no tree falls back to a direct match`() {
        val records = listOf(record(1, event = 3), record(2, event = 1))

        val matched = LibraryFilter.apply(
            records = records,
            filter = FilterState(selectedEventIds = setOf(1L)),
            context = FilterContext()
        ).map { it.metadata.recordId }

        assertEquals(listOf(2L), matched)
    }

    /** Selecting nothing is not a term, and must not start excluding things. */
    @Test
    fun `an empty selection matches everything`() {
        val records = listOf(record(1, event = 3), record(2, event = null))

        val matched = LibraryFilter.apply(records, FilterState(), context)

        assertEquals(records.size, matched.size)
        assertTrue(matched.any { it.metadata.eventId == null })
    }
}
