package inga.bpmetrics.ui.library

import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.EventGroupEntity
import inga.bpmetrics.library.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * What a collection's row claims about what is inside it.
 *
 * A collection used to be counted by its own events alone, which was right while nothing nested and
 * wrong the moment something did: a festival holding only days reported "0 events, 0 recordings" —
 * true of the row, false of the thing the row stands for.
 */
class GroupSummaryTest {

    private fun record(id: Long, eventId: Long, personId: Long, startTime: Long) = BpmRecord(
        metadata = BpmRecordEntity(
            recordId = id,
            title = "Recording $id",
            date = 0L,
            startTime = startTime,
            endTime = startTime + 60_000L,
            durationMs = 60_000L,
            eventId = eventId,
            personId = personId
        ),
        dataPoints = emptyList(),
        minDataPoint = null,
        maxDataPoint = null
    )

    private fun person(id: Long) = PersonEntity(personId = id, name = "Person $id", colorArgb = 0)

    private fun eventSummary(eventId: Long, groupId: Long?, records: List<BpmRecord>) =
        EventSummary(
            event = EventEntity(eventId = eventId, name = "Event $eventId", groupId = groupId),
            records = records,
            people = records.mapNotNull { it.metadata.personId }.distinct().map(::person)
        )

    /** Coachella holds Day 1 and Day 2; only the days hold events. */
    private val subtronics = eventSummary(
        eventId = 100, groupId = 2,
        records = listOf(record(1, 100, personId = 5, startTime = 1_000))
    )
    private val zedsDead = eventSummary(
        eventId = 101, groupId = 3,
        records = listOf(
            record(2, 101, personId = 5, startTime = 5_000),
            record(3, 101, personId = 6, startTime = 5_500)
        )
    )

    private fun festival() = GroupSummary(
        group = EventGroupEntity(groupId = 1, name = "Coachella"),
        events = emptyList(),
        allEvents = listOf(subtronics, zedsDead),
        nestedCollectionCount = 2
    )

    @Test
    fun `a collection holding only collections still counts what is under it`() {
        val summary = festival()

        assertEquals(2, summary.eventCount)
        assertEquals(3, summary.recordCount)
    }

    @Test
    fun `it reports how many collections are inside`() {
        assertEquals(2, festival().nestedCollectionCount)
    }

    @Test
    fun `its people are everyone anywhere under it, counted once`() {
        // Kyle appears in both days. One person, one dot.
        assertEquals(listOf(5L, 6L), festival().people.map { it.personId })
    }

    @Test
    fun `its span covers the whole subtree, not just its own events`() {
        val span = festival().span

        assertNotNull("a festival holding only days still has a span", span)
        assertEquals(1_000L, span!!.startMs)
        assertEquals(5_500L + 60_000L, span.endMs)
    }

    @Test
    fun `direct events stay separate from the subtree`() {
        // The expanded card lists only what a collection holds directly; anything deeper is its own
        // card below. Conflating the two would list the same event twice.
        val day = GroupSummary(
            group = EventGroupEntity(groupId = 2, name = "Day 1"),
            events = listOf(subtronics),
            allEvents = listOf(subtronics),
            nestedCollectionCount = 0
        )

        assertEquals(1, day.events.size)
        assertEquals(1, day.eventCount)
    }

    @Test
    fun `a flat collection is unchanged by the subtree defaulting to its own events`() {
        // The default keeps every existing construction correct: a collection with nothing nested
        // counts exactly what it holds.
        val flat = GroupSummary(
            group = EventGroupEntity(groupId = 9, name = "Bass Canyon"),
            events = listOf(zedsDead)
        )

        assertEquals(1, flat.eventCount)
        assertEquals(2, flat.recordCount)
        assertEquals(0, flat.nestedCollectionCount)
    }
}
