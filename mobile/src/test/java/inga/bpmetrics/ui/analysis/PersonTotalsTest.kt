package inga.bpmetrics.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the per-person totals a group or filtered analysis is judged on.
 *
 * The weighting is the part worth guarding: a plain mean of each recording's average lets a
 * forty-second recording count as much as a three-hour one, which is how "who went hardest all
 * weekend" gets the wrong answer.
 */
class PersonTotalsTest {

    private val minute = 60_000L

    private fun record(
        id: Long,
        wearer: String,
        personId: Long? = null,
        min: Double = 60.0,
        avg: Double = 100.0,
        max: Double = 150.0,
        activeMinutes: Long = 10,
        colorArgb: Int? = null,
        eventName: String = ""
    ) = AnalysisRecord(
        recordId = id,
        title = "Record $id",
        date = 0L,
        minBpm = min,
        avgBpm = avg,
        maxBpm = max,
        activeDurationMs = activeMinutes * minute,
        wearerName = wearer,
        personId = personId,
        personColorArgb = colorArgb,
        eventName = eventName
    )

    @Test
    fun `one row per person, not per recording`() {
        val totals = AnalysisViewModel.perPersonTotals(
            listOf(
                record(1, "Kyle", personId = 1),
                record(2, "Kyle", personId = 1),
                record(3, "Ben", personId = 2)
            )
        )

        assertEquals(2, totals.size)
        assertEquals(2, totals.first { it.name == "Kyle" }.recordCount)
        assertEquals(1, totals.first { it.name == "Ben" }.recordCount)
    }

    @Test
    fun `the average is weighted by active time`() {
        // Three hours at 120 and one minute at 190. An unweighted mean says 155; the truth is
        // barely above 120, and 155 would put this person top of a ranking they should not lead.
        val totals = AnalysisViewModel.perPersonTotals(
            listOf(
                record(1, "Kyle", personId = 1, avg = 120.0, activeMinutes = 180),
                record(2, "Kyle", personId = 1, avg = 190.0, activeMinutes = 1)
            )
        ).single()

        assertEquals(120.4, totals.avgBpm, 0.1)
        assertEquals(181 * minute, totals.activeDurationMs)
    }

    @Test
    fun `min and max are the extremes across everything, not averages of them`() {
        val totals = AnalysisViewModel.perPersonTotals(
            listOf(
                record(1, "Kyle", personId = 1, min = 55.0, max = 140.0),
                record(2, "Kyle", personId = 1, min = 70.0, max = 190.0)
            )
        ).single()

        assertEquals(55.0, totals.minBpm, 0.001)
        assertEquals(190.0, totals.maxBpm, 0.001)
    }

    @Test
    fun `rows are ordered by peak, hardest first`() {
        val totals = AnalysisViewModel.perPersonTotals(
            listOf(
                record(1, "Kyle", personId = 1, max = 150.0),
                record(2, "Ben", personId = 2, max = 190.0),
                record(3, "Sam", personId = 3, max = 170.0)
            )
        )

        assertEquals(listOf("Ben", "Sam", "Kyle"), totals.map { it.name })
    }

    @Test
    fun `a person with no active time does not divide by zero`() {
        val totals = AnalysisViewModel.perPersonTotals(
            listOf(record(1, "Kyle", personId = 1, avg = 120.0, activeMinutes = 0))
        ).single()

        assertEquals(0.0, totals.avgBpm, 0.001)
        assertEquals(0L, totals.activeDurationMs)
    }

    @Test
    fun `records with no wearer are left out rather than pooled together`() {
        // Pooling them would invent a person made of everyone the app does not know about.
        val totals = AnalysisViewModel.perPersonTotals(
            listOf(
                record(1, "Kyle", personId = 1),
                record(2, ""),
                record(3, "")
            )
        )

        assertEquals(1, totals.size)
        assertEquals("Kyle", totals.single().name)
    }

    @Test
    fun `a saved analysis still produces rows, without ids or colour`() {
        // Snapshots store names only, so grouping is by name and isolation is unavailable — but
        // the numbers are all there and the row must still appear.
        val totals = AnalysisViewModel.perPersonTotals(
            listOf(
                record(1, "Kyle", personId = null, colorArgb = null, max = 150.0),
                record(2, "Kyle", personId = null, colorArgb = null, max = 180.0)
            )
        ).single()

        assertEquals("Kyle", totals.name)
        assertEquals(2, totals.recordCount)
        assertEquals(180.0, totals.maxBpm, 0.001)
        assertNull(totals.personId)
        assertNull(totals.colorArgb)
    }

    @Test
    fun `nothing in scope is no rows`() {
        assertTrue(AnalysisViewModel.perPersonTotals(emptyList()).isEmpty())
    }
}
