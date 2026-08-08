package inga.bpmetrics.ui.export

import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a scope becomes pictures.
 *
 * Only the group-split case has any decisions in it, and both ways of getting it wrong are quiet:
 * an image nobody asked for, or a person missing from one that nobody notices until it is posted.
 */
class ImagePlanTest {

    private fun record(id: Long, startTime: Long, eventId: Long? = null, personId: Long? = null) =
        BpmRecord(
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

    private fun event(id: Long, name: String) = EventEntity(eventId = id, name = name)

    @Test
    fun `nothing in scope plans nothing`() {
        assertEquals(emptyList<ImagePlanEntry>(), ImagePlanEntry.plan(emptyList(), emptyList(), true, "Coachella"))
    }

    @Test
    fun `without a split everything shares one timeline`() {
        val records = listOf(record(1, 100, eventId = 7), record(2, 200, eventId = 8))

        val plan = ImagePlanEntry.plan(records, listOf(event(7, "A"), event(8, "B")), splitByEvent = false, scopeTitle = "Coachella")

        assertEquals(1, plan.size)
        assertEquals(listOf(1L, 2L), plan.single().records.map { it.metadata.recordId })
        // Named after what it is of. This used to read "Whole timeline", which described the shape
        // of the picture rather than its subject and so captioned nothing anyone would recognise.
        assertEquals("Coachella", plan.single().label)
    }

    @Test
    fun `a scope with no name of its own still gets a usable one`() {
        val plan = ImagePlanEntry.plan(
            listOf(record(1, 100)), emptyList(), splitByEvent = false, scopeTitle = ""
        )

        assertTrue("must not be captioned with an empty string", plan.single().label.isNotBlank())
    }

    @Test
    fun `a split gives one image per event, in the order the evening happened`() {
        val records = listOf(
            record(1, startTime = 3_000, eventId = 8),
            record(2, startTime = 1_000, eventId = 7),
            record(3, startTime = 1_500, eventId = 7)
        )
        // Deliberately listed out of order, so sorting cannot be passing by accident.
        val events = listOf(event(8, "Zeds Dead"), event(7, "Subtronics"))

        val plan = ImagePlanEntry.plan(records, events, splitByEvent = true, scopeTitle = "Coachella")

        assertEquals(listOf("Subtronics", "Zeds Dead"), plan.map { it.label })
        assertEquals(listOf(2L, 3L), plan.first().records.map { it.metadata.recordId })
    }

    @Test
    fun `recordings in no event are gathered rather than dropped`() {
        // The failure this guards against is silent: an image set that quietly leaves somebody out.
        val records = listOf(
            record(1, startTime = 1_000, eventId = 7),
            record(2, startTime = 2_000, eventId = null)
        )

        val plan = ImagePlanEntry.plan(records, listOf(event(7, "Subtronics")), splitByEvent = true, scopeTitle = "Coachella")

        assertEquals(listOf("Subtronics", "Unfiled"), plan.map { it.label })
        assertEquals(
            "every recording in scope must land on exactly one image",
            records.size,
            plan.sumOf { it.records.size }
        )
    }

    @Test
    fun `a split with no matching events still produces an image`() {
        // A group whose events were deleted out from under it. One timeline is a better answer than
        // an empty step 4 that gives no hint why nothing appeared.
        val records = listOf(record(1, startTime = 1_000, eventId = 99))

        val plan = ImagePlanEntry.plan(records, emptyList(), splitByEvent = true, scopeTitle = "Coachella")

        assertEquals(1, plan.size)
        assertEquals(1, plan.single().records.size)
        // And it falls back to the group's name, not to a placeholder.
        assertEquals("Coachella", plan.single().label)
    }

    @Test
    fun `an entry counts people rather than recordings when it knows them`() {
        val entry = ImagePlanEntry(
            label = "Subtronics",
            records = listOf(
                record(1, 1_000, personId = 5),
                record(2, 2_000, personId = 5),
                record(3, 3_000, personId = 6)
            )
        )

        assertEquals(2, entry.peopleCount)
    }

    @Test
    fun `recordings with nobody attached fall back to counting recordings`() {
        val entry = ImagePlanEntry(
            label = "Unfiled",
            records = listOf(record(1, 1_000), record(2, 2_000))
        )

        assertTrue(entry.peopleCount == 2)
    }
}
