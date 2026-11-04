package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things on an event's analysis page: whose recordings it is reading, and which clock it
 * reads them in.
 */
class PersonFilterAndClockTest {

    // --- The person filter ---

    private fun row(id: Long, person: Long?) = AnalysisRecord(
        recordId = id,
        title = "Record $id",
        date = id * 1000,
        minBpm = 60.0,
        avgBpm = 100.0,
        maxBpm = 150.0,
        activeDurationMs = 1000,
        personId = person
    )

    private val danny = 1L
    private val colin = 2L
    private val tyler = 3L

    private val evening = listOf(
        row(10, danny),
        row(11, danny),
        row(12, colin),
        row(13, tyler),
        row(14, null)
    )

    /** No filter is not an empty analysis. It is everyone, and nothing is taken away. */
    @Test
    fun `an empty filter keeps everything`() {
        assertEquals(evening, PersonFilterRule.apply(evening, emptySet()))
    }

    /** "Bass Canyon, just me." */
    @Test
    fun `one person keeps only their recordings`() {
        assertEquals(
            listOf(10L, 11L),
            PersonFilterRule.apply(evening, setOf(danny)).map { it.recordId }
        )
    }

    @Test
    fun `several people keep all of theirs`() {
        assertEquals(
            listOf(10L, 11L, 12L),
            PersonFilterRule.apply(evening, setOf(danny, colin)).map { it.recordId }
        )
    }

    /**
     * A recording with nobody attached is not shown under a filter.
     *
     * It is not any of the people chosen. Counting it for them would put a stranger's evening into
     * their stats — and it would do so invisibly, because it has no face to show in the lane.
     */
    @Test
    fun `an unattributed recording is not counted for anyone chosen`() {
        assertTrue(PersonFilterRule.apply(evening, setOf(danny, colin, tyler)).none { it.personId == null })
    }

    /**
     * Ticking everyone is stored as no filter.
     *
     * Otherwise the badge claims the page is narrowed while it shows what it always did, and a fifth
     * person's recording arriving later would be silently left out of an analysis meant to include
     * everybody.
     */
    @Test
    fun `everyone ticked means no filter`() {
        val inScope = listOf(danny, colin, tyler)
        assertEquals(emptySet<Long>(), PersonFilterRule.normalise(setOf(danny, colin, tyler), inScope))
    }

    @Test
    fun `a genuine subset is kept as chosen`() {
        val inScope = listOf(danny, colin, tyler)
        assertEquals(setOf(danny), PersonFilterRule.normalise(setOf(danny), inScope))
    }

    /** Nothing in scope yet — still loading — must not turn a real choice into "everyone". */
    @Test
    fun `a choice made before the scope loads is not discarded`() {
        assertEquals(setOf(danny), PersonFilterRule.normalise(setOf(danny), emptyList()))
    }

    // --- The timeline's clock ---

    private val nine = 1_700_000_000_000L

    private fun reading(id: Long, zone: String?, person: Long): BpmRecordWithPoints {
        val points = listOf(90.0, 110.0, 130.0).mapIndexed { i, bpm ->
            BpmDataPointEntity(
                dataPointId = id * 100 + i,
                recordOwnerId = id,
                timestamp = i * 1000L,
                bpm = bpm
            )
        }
        return BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = id,
                title = "Record $id",
                date = nine,
                startTime = nine,
                endTime = nine + 3000,
                durationMs = 3000,
                personId = person,
                timeZoneId = zone
            ),
            dataPoints = points,
            minDataPoint = points.minByOrNull { it.bpm },
            maxDataPoint = points.maxByOrNull { it.bpm }
        )
    }

    /**
     * The event page's timeline reads in the venue's clock.
     *
     * `EventAnalysis.from` handed `ConcurrentAnalysis.of` no zone at all, so its clock fell back to
     * the phone's own and a Chicago set drew its axis in Pacific time — whatever the venue said and
     * however current the recordings' stored zones were.
     */
    @Test
    fun `an event timeline is drawn in the venue zone`() {
        val analysis = EventAnalysis.from(
            listOf(reading(1, "America/Chicago", danny), reading(2, "America/Chicago", colin))
        )
        assertEquals("America/Chicago", analysis.zoneId)
        assertEquals("America/Chicago", analysis.clock.id)
    }

    /** One wearer with no venue at the front of the list does not decide it for everyone else. */
    @Test
    fun `a leading unfiled recording does not set the clock`() {
        val analysis = EventAnalysis.from(
            listOf(reading(1, null, danny), reading(2, "America/Chicago", colin))
        )
        assertEquals("America/Chicago", analysis.zoneId)
    }

    /** The chart built the other way agrees, so no two charts can disagree about one occasion. */
    @Test
    fun `a concurrent analysis uses the same rule`() {
        val analysis = ConcurrentAnalysis.from(
            listOf(reading(1, null, danny), reading(2, "America/Chicago", colin))
        )
        assertEquals("America/Chicago", analysis.zoneId)
    }
}
