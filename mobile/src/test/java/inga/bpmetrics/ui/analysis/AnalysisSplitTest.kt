package inga.bpmetrics.ui.analysis

import inga.bpmetrics.core.BpmPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comparing an analysis along an axis.
 *
 * The feature the taxonomy document exists for. "Compare my rate across characters" used to mean
 * filtering to Spiderman, reading the number, filtering to Hulk, reading that, and holding both in
 * your head — which is not a comparison, it is two lookups and a memory test.
 *
 * The property everything here protects is that **the lanes partition the scope**: every record
 * lands in exactly one, and they sum to the whole. That is what makes a percentage mean anything,
 * and it is the reason a category may hold only one tag per recording. A comparison whose lanes
 * overlap is one whose totals double-count, and explaining that at every figure is worse than the
 * constraint that avoids it.
 */
class AnalysisSplitTest {

    private fun tag(name: String, categoryId: Long = 1, category: String = "Character") =
        AnalysisTag(tagName = name, categoryId = categoryId, categoryName = category)

    private fun record(
        id: Long,
        max: Double = 150.0,
        avg: Double = 100.0,
        durationMs: Long = 600_000L,
        tags: List<AnalysisTag> = emptyList(),
        wearer: String = "",
        personId: Long? = null,
        colour: Int? = null,
        eventName: String = "",
        eventId: Long? = null,
        eventType: String = "",
        locationId: Long? = null,
        locationName: String = ""
    ) = AnalysisRecord(
        recordId = id,
        title = "Record $id",
        date = id,
        minBpm = 60.0,
        avgBpm = avg,
        maxBpm = max,
        activeDurationMs = durationMs,
        tags = tags,
        wearerName = wearer,
        personId = personId,
        personColorArgb = colour,
        eventName = eventName,
        // An event axis keys on identity, so a fixture naming an event has to give it one.
        eventId = eventId ?: eventName.takeIf { it.isNotBlank() }?.hashCode()?.toLong(),
        eventType = eventType,
        locationId = locationId ?: locationName.takeIf { it.isNotBlank() }?.hashCode()?.toLong(),
        locationName = locationName
    )

    // --- Which axes are worth offering (TX-3.5) ---

    @Test
    fun `an axis with two values is offered`() {
        val records = listOf(
            record(1, tags = listOf(tag("Spiderman"))),
            record(2, tags = listOf(tag("Hulk")))
        )

        assertEquals(
            listOf(SplitAxis.TagCategory(1, "Character"), SplitAxis.Recording),
            AnalysisSplit.axesFor(records)
        )
    }

    @Test
    fun `an axis with one value is not a comparison`() {
        // An evening that was all Spiderman produces one lane and answers nothing. Offering it
        // trains people to tap things that do not do anything.
        val records = listOf(
            record(1, tags = listOf(tag("Spiderman"))),
            record(2, tags = listOf(tag("Spiderman")))
        )

        // Only the recordings themselves, which two of anything can always be compared along.
        assertEquals(listOf(SplitAxis.Recording), AnalysisSplit.axesFor(records))
    }

    @Test
    fun `one value plus untagged is still not a comparison`() {
        // One character and some gaps. "Spiderman beat Unlabelled" is not a finding.
        val records = listOf(
            record(1, tags = listOf(tag("Spiderman"))),
            record(2)
        )

        assertEquals(listOf(SplitAxis.Recording), AnalysisSplit.axesFor(records))
    }

    @Test
    fun `people, events and types are offered on the same rule`() {
        val records = listOf(
            record(1, wearer = "Kyle", personId = 1, eventName = "Day 1", eventType = "Concert"),
            record(2, wearer = "Ben", personId = 2, eventName = "Day 2", eventType = "Raid")
        )

        assertEquals(
            listOf(
                SplitAxis.Person,
                SplitAxis.ChildEvent,
                SplitAxis.EventType,
                SplitAxis.Recording
            ),
            AnalysisSplit.axesFor(records)
        )
    }

    @Test
    fun `an axis nobody used is not offered`() {
        val records = listOf(record(1), record(2))

        assertEquals(listOf(SplitAxis.Recording), AnalysisSplit.axesFor(records))
    }

    @Test
    fun `an empty scope offers nothing`() {
        assertTrue(AnalysisSplit.axesFor(emptyList()).isEmpty())
    }

    @Test
    fun `two categories are two separate axes`() {
        val records = listOf(
            record(1, tags = listOf(tag("Spiderman"), tag("Ranked", 2, "Mode"))),
            record(2, tags = listOf(tag("Hulk"), tag("Casual", 2, "Mode")))
        )

        assertEquals(
            setOf("Character", "Mode", "Recording"),
            AnalysisSplit.axesFor(records).map { it.label }.toSet()
        )
    }

    // --- Event types behave like tag categories ---

    @Test
    fun `a type holding several events becomes an axis of its own`() {
        // "Concert or sports game" is the category question and EventType answers it. "Which
        // concert" is the one asked next, and ChildEvent could not answer it — it puts every event
        // in the scope side by side, so the concerts sit scattered among the sports games.
        val records = listOf(
            record(1, eventName = "Subtronics", eventType = "Concert"),
            record(2, eventName = "Excision", eventType = "Concert"),
            record(3, eventName = "Sounders", eventType = "Sports game")
        )

        assertTrue(AnalysisSplit.axesFor(records).contains(SplitAxis.EventsOfType("Concert")))
        // One sports game is not a comparison of sports games.
        assertFalse(AnalysisSplit.axesFor(records).contains(SplitAxis.EventsOfType("Sports game")))
    }

    @Test
    fun `an event type axis compares only that type, and says so`() {
        val records = listOf(
            record(1, eventName = "Subtronics", eventType = "Concert", max = 180.0),
            record(2, eventName = "Excision", eventType = "Concert", max = 170.0),
            record(3, eventName = "Sounders", eventType = "Sports game", max = 120.0)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.EventsOfType("Concert"))

        assertEquals(listOf("Subtronics", "Excision"), lanes.filterNot { it.isUnlabelled }.map { it.value })
        // The sports game is still counted — the lanes sum to the whole — but it is *other*, not
        // unlabelled. It has an event type; it simply is not this one.
        assertEquals("Other event types", lanes.last().value)
        assertTrue(lanes.last().isUnlabelled)
    }

    // --- The scope's own event is never a lane ---

    @Test
    fun `the event being analysed is not compared against itself`() {
        // A festival's own loose recordings carry its id. Left in, they become a lane holding
        // everything, drawn at full width, saying nothing.
        val records = listOf(
            record(1, eventName = "Coachella", eventId = 10),
            record(2, eventName = "Day 1", eventId = 11),
            record(3, eventName = "Day 2", eventId = 12)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.ChildEvent, excludeEventId = 10)

        assertEquals(listOf("Day 1", "Day 2"), lanes.filterNot { it.isUnlabelled }.map { it.value })
        // Its recording is still in the totals, in the residual lane.
        assertEquals(3, lanes.sumOf { it.count })
    }

    @Test
    fun `an axis that only qualified because of the root is not offered`() {
        val records = listOf(
            record(1, eventName = "Coachella", eventId = 10),
            record(2, eventName = "Day 1", eventId = 11)
        )

        assertFalse(AnalysisSplit.axesFor(records, excludeEventId = 10).contains(SplitAxis.ChildEvent))
    }

    // --- The partition property ---

    @Test
    fun `every record lands in exactly one lane`() {
        val records = listOf(
            record(1, tags = listOf(tag("Spiderman"))),
            record(2, tags = listOf(tag("Hulk"))),
            record(3, tags = listOf(tag("Spiderman"))),
            record(4)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character"))
        val placed = lanes.flatMap { it.records }

        assertEquals(records.size, placed.size)
        assertEquals(records.map { it.recordId }.toSet(), placed.map { it.recordId }.toSet())
    }

    @Test
    fun `lanes sum to the whole`() {
        val records = listOf(
            record(1, durationMs = 100, tags = listOf(tag("Spiderman"))),
            record(2, durationMs = 200, tags = listOf(tag("Hulk"))),
            record(3, durationMs = 300)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character"))

        assertEquals(600L, lanes.sumOf { it.activeDurationMs })
        assertEquals(3, lanes.sumOf { it.count })
    }

    @Test
    fun `unlabelled records get a lane rather than being dropped`() {
        // A total quietly smaller than the scope it claims to describe is this app's recurring
        // failure. The lane is there so the sum is honest.
        val records = listOf(
            record(1, tags = listOf(tag("Spiderman"))),
            record(2, tags = listOf(tag("Hulk"))),
            record(3)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character"))

        assertEquals(1, lanes.count { it.isUnlabelled })
        assertEquals(1, lanes.last().count)
        assertTrue(lanes.last().isUnlabelled)
    }

    @Test
    fun `no unlabelled lane when everything is labelled`() {
        val records = listOf(
            record(1, tags = listOf(tag("Spiderman"))),
            record(2, tags = listOf(tag("Hulk")))
        )

        assertTrue(AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character"))
            .none { it.isUnlabelled })
    }

    @Test
    fun `a tag from another category does not split this one`() {
        val records = listOf(
            record(1, tags = listOf(tag("Spiderman"), tag("Ranked", 2, "Mode"))),
            record(2, tags = listOf(tag("Hulk"), tag("Ranked", 2, "Mode")))
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.TagCategory(2, "Mode"))

        assertEquals(1, lanes.size)
        assertEquals("Ranked", lanes.single().value)
    }

    // --- Ordering and summaries ---

    @Test
    fun `lanes read highest first by whatever is being measured`() {
        // Ordered by the figure on show, not always by peak. Ranking by peak while the screen
        // displays averages is a list that looks mis-sorted with no way to tell that it is not.
        val records = listOf(
            record(1, max = 190.0, avg = 90.0, tags = listOf(tag("Spiderman"))),
            record(2, max = 140.0, avg = 130.0, tags = listOf(tag("Hulk")))
        )
        val axis = SplitAxis.TagCategory(1, "Character")

        // The default is the average, which is what the screen opens on.
        assertEquals(
            listOf("Hulk", "Spiderman"),
            AnalysisSplit.split(records, axis).map { it.value }
        )

        // Asked for the peak instead, the order flips — the two disagree here on purpose.
        assertEquals(
            listOf("Spiderman", "Hulk"),
            AnalysisSplit.split(records, axis) { it.maxBpm }.map { it.value }
        )
    }

    @Test
    fun `the unlabelled lane stays last however hard it was`() {
        val records = listOf(
            record(1, max = 140.0, tags = listOf(tag("Spiderman"))),
            record(2, max = 150.0, tags = listOf(tag("Hulk"))),
            record(3, max = 200.0)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character"))

        assertTrue(lanes.last().isUnlabelled)
    }

    @Test
    fun `a lane average is weighted by how long each recording ran`() {
        // Two hours at 100 and two minutes at 190 is not an average of 145. A plain mean would let
        // one short spike win every comparison it appeared in.
        val records = listOf(
            record(1, avg = 100.0, durationMs = 7_200_000, tags = listOf(tag("Spiderman"))),
            record(2, avg = 190.0, durationMs = 120_000, tags = listOf(tag("Spiderman")))
        )

        val lane = AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character")).single()

        assertEquals(101.5, lane.avgBpm!!, 0.5)
    }

    @Test
    fun `a lane reports the extremes across its records`() {
        val records = listOf(
            record(1, max = 140.0, tags = listOf(tag("Spiderman"))),
            record(2, max = 190.0, tags = listOf(tag("Spiderman")))
        )

        val lane = AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character")).single()

        assertEquals(190.0, lane.maxBpm!!, 0.001)
        assertEquals(60.0, lane.minBpm!!, 0.001)
    }

    @Test
    fun `a lane of recordings with no duration still averages`() {
        // Weighting by zero would divide by zero; falling back to a plain mean is better than null.
        val records = listOf(
            record(1, avg = 100.0, durationMs = 0, tags = listOf(tag("Spiderman"))),
            record(2, avg = 200.0, durationMs = 0, tags = listOf(tag("Spiderman")))
        )

        val lane = AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character")).single()

        assertEquals(150.0, lane.avgBpm!!, 0.001)
    }

    // --- People ---

    @Test
    fun `a person lane carries their colour`() {
        val records = listOf(
            record(1, wearer = "Kyle", personId = 1, colour = -65536),
            record(2, wearer = "Ben", personId = 2, colour = -16776961)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.Person)

        assertEquals(-65536, lanes.first { it.value == "Kyle" }.colorArgb)
        assertEquals(-16776961, lanes.first { it.value == "Ben" }.colorArgb)
    }

    /**
     * Replaces `a tag lane carries no colour`, which pinned the defect rather than the rule.
     *
     * Carrying no colour was not a neutral choice: it fell back at the drawing site to the metric's
     * own colour, so a comparison split six ways by tag drew six identical dots and the colour
     * column said nothing at all.
     */
    @Test
    fun `tag lanes take distinct colours from the neutral series`() {
        val records = listOf(
            record(1, colour = -65536, tags = listOf(tag("Spiderman"))),
            record(2, colour = -65536, tags = listOf(tag("Hulk")))
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character"))
        val colours = lanes.map { it.colorArgb }

        assertTrue("a lane came back uncoloured", colours.all { it != null })
        assertEquals("two lanes shared a colour", lanes.size, colours.distinct().size)

        // Never a person's colour, whatever the records happen to carry. §5: identity means a
        // person, and a tag lane borrowing that palette would claim a tag was somebody.
        assertTrue(colours.none { it == -65536 })
        assertTrue(colours.all { it in BpmPalette.NEUTRAL })
    }

    /**
     * A lane keeps its colour when the ranking changes.
     *
     * Colours are assigned over the keys in sorted order, not over the lanes in ranked order —
     * "Hulk" before "Spiderman" — because lanes are re-sorted by whichever metric is selected. A
     * dot that changed from sage to clay when you switched from average to peak would read as a
     * different lane rather than the same one re-ranked.
     */
    @Test
    fun `a tag's colour follows the tag, not its rank`() {
        val records = listOf(
            record(1, tags = listOf(tag("Spiderman"))),
            record(2, tags = listOf(tag("Hulk")))
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character"))

        assertEquals(BpmPalette.neutral(0), lanes.first { it.value == "Hulk" }.colorArgb)
        assertEquals(BpmPalette.neutral(1), lanes.first { it.value == "Spiderman" }.colorArgb)
    }

    /** The residual is the absence of a category, and recedes rather than competing. */
    @Test
    fun `the unlabelled lane is the residual colour`() {
        val records = listOf(
            record(1, tags = listOf(tag("Hulk"))),
            record(2)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.TagCategory(1, "Character"))
        val residual = lanes.first { it.isUnlabelled }

        assertEquals(BpmPalette.RESIDUAL, residual.colorArgb)
        assertTrue(BpmPalette.RESIDUAL !in BpmPalette.NEUTRAL)
    }

    @Test
    fun `a recording with no wearer falls to unlabelled rather than to a blank lane`() {
        val records = listOf(
            record(1, wearer = "Kyle", personId = 1),
            record(2, wearer = "Ben", personId = 2),
            record(3)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.Person)

        assertEquals(3, lanes.size)
        assertTrue(lanes.last().isUnlabelled)
        assertFalse(lanes.any { it.value.isBlank() })
    }

    @Test
    fun `an empty scope splits to nothing`() {
        assertTrue(AnalysisSplit.split(emptyList(), SplitAxis.Person).isEmpty())
    }

    // --- Events that share a name (the short-name / qualified-name split) ---

    private fun eventRecord(id: Long, eventId: Long, short: String, path: String, max: Double) =
        record(id, max = max, eventName = short).copy(
            eventId = eventId,
            eventQualifiedName = path
        )

    @Test
    fun `two events with the same short name stay two lanes`() {
        // Seeing Subtronics twice at two festivals is two nights. Grouping on the short name would
        // average them together and report one — a wrong answer, not an untidy one.
        val records = listOf(
            eventRecord(1, eventId = 10, short = "Subtronics", path = "Subtronics  |  Griztronics", max = 180.0),
            eventRecord(2, eventId = 20, short = "Subtronics", path = "Subtronics  |  Bass Canyon", max = 150.0)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.ChildEvent)

        assertEquals(2, lanes.size)
        assertEquals(
            listOf("Subtronics  |  Griztronics", "Subtronics  |  Bass Canyon"),
            lanes.map { it.value }
        )
    }

    @Test
    fun `same-named events stay separate even with no qualified name to tell them apart`() {
        // A snapshot saved before qualified names existed. The labels are identical and the lanes
        // must still not merge — which is why a lane is keyed by identity rather than by its label.
        val records = listOf(
            eventRecord(1, eventId = 10, short = "Subtronics", path = "", max = 180.0),
            eventRecord(2, eventId = 20, short = "Subtronics", path = "", max = 150.0)
        )

        val lanes = AnalysisSplit.split(records, SplitAxis.ChildEvent)

        assertEquals(2, lanes.size)
        assertEquals(2, lanes.map { it.key }.distinct().size)
        assertEquals(2, lanes.sumOf { it.count })
    }

    @Test
    fun `one event recorded twice is one lane`() {
        val records = listOf(
            eventRecord(1, eventId = 10, short = "Subtronics", path = "Subtronics  |  Grizt", max = 180.0),
            eventRecord(2, eventId = 10, short = "Subtronics", path = "Subtronics  |  Grizt", max = 150.0)
        )

        assertEquals(1, AnalysisSplit.split(records, SplitAxis.ChildEvent).size)
    }

    @Test
    fun `two events sharing a name make the event axis worth offering`() {
        val records = listOf(
            eventRecord(1, eventId = 10, short = "Subtronics", path = "Subtronics  |  Grizt", max = 180.0),
            eventRecord(2, eventId = 20, short = "Subtronics", path = "Subtronics  |  Bass", max = 150.0)
        )

        assertTrue(SplitAxis.ChildEvent in AnalysisSplit.axesFor(records))
    }

    @Test
    fun `a lane falls back to the short name when there is no path`() {
        val records = listOf(
            eventRecord(1, eventId = 10, short = "Subtronics", path = "", max = 180.0),
            eventRecord(2, eventId = 20, short = "Excision", path = "", max = 150.0)
        )

        assertEquals(
            listOf("Subtronics", "Excision"),
            AnalysisSplit.split(records, SplitAxis.ChildEvent).map { it.value }
        )
    }
}
