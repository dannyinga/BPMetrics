package inga.bpmetrics.ui.settings

import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the preset editor draws on.
 *
 * The failure worth guarding against is a quiet one: falling back to a synthetic curve when real
 * recordings were available would show someone a preset judged against data that is not theirs,
 * and nothing on screen would say so.
 */
class PreviewSubjectsTest {

    private val noon = 1_700_000_000_000L

    private fun record(
        id: Long,
        personId: Long?,
        startTime: Long,
        eventId: Long? = null,
        points: Int = 10
    ) = BpmRecord(
        metadata = BpmRecordEntity(
            recordId = id,
            title = "Recording $id",
            date = startTime,
            startTime = startTime,
            endTime = startTime + 60_000L,
            durationMs = 60_000L,
            personId = personId,
            eventId = eventId
        ),
        dataPoints = (0 until points).map {
            BpmDataPointEntity(recordOwnerId = id, timestamp = it * 1_000L, bpm = 90.0)
        },
        minDataPoint = null,
        maxDataPoint = null
    )

    @Test
    fun `an empty library still has something to preview`() {
        // The case this matters most in: a fresh install, which is exactly when someone is likely
        // to be arranging a preset.
        val single = PreviewSubjects.onePerson(emptyList())
        val several = PreviewSubjects.severalPeople(emptyList())

        assertEquals(1, single.size)
        assertTrue(single.single().dataPoints.size > 2)
        assertTrue("several must mean several", several.size > 1)
    }

    @Test
    fun `the sample curves are not flat`() {
        // A flat line would make every colour setting look identical and the gradient look broken.
        val bpm = PreviewSubjects.onePerson(emptyList()).single().dataPoints.map { it.bpm }

        assertTrue("the sample must actually move", bpm.max() - bpm.min() > 20.0)
    }

    @Test
    fun `the sample is the same every time it is asked for`() {
        // Redrawing differently on each recomposition would make it impossible to tell whether a
        // change to the preset had done anything.
        val first = PreviewSubjects.onePerson(emptyList()).single().dataPoints.map { it.bpm }
        val second = PreviewSubjects.onePerson(emptyList()).single().dataPoints.map { it.bpm }

        assertEquals(first, second)
    }

    @Test
    fun `a real recording is preferred over the sample`() {
        val real = record(1, personId = 5, startTime = noon)

        assertEquals(listOf(1L), PreviewSubjects.onePerson(listOf(real)).map { it.metadata.recordId })
    }

    @Test
    fun `the most recent real recording is the one shown`() {
        val older = record(1, personId = 5, startTime = noon)
        val newer = record(2, personId = 5, startTime = noon + 600_000)

        assertEquals(
            listOf(2L),
            PreviewSubjects.onePerson(listOf(older, newer)).map { it.metadata.recordId }
        )
    }

    @Test
    fun `a recording with almost no readings is not used as a sample`() {
        // Two points draw a straight line, which shows nothing about a preset.
        val stub = record(1, personId = 5, startTime = noon, points = 1)

        val chosen = PreviewSubjects.onePerson(listOf(stub))

        assertTrue("must fall back rather than preview a stub", chosen.single().metadata.recordId < 0)
    }

    @Test
    fun `several people comes from an event with more than one of them`() {
        val library = listOf(
            record(1, personId = 5, startTime = noon, eventId = 7),
            record(2, personId = 6, startTime = noon, eventId = 7),
            record(3, personId = 5, startTime = noon + 600_000, eventId = 8)
        )

        val chosen = PreviewSubjects.severalPeople(library)

        assertEquals(setOf(1L, 2L), chosen.map { it.metadata.recordId }.toSet())
    }

    @Test
    fun `an event with only one person does not qualify`() {
        // Two recordings of one person is still one lane, which is the other preview.
        val library = listOf(
            record(1, personId = 5, startTime = noon, eventId = 7),
            record(2, personId = 5, startTime = noon + 60_000, eventId = 7)
        )

        val chosen = PreviewSubjects.severalPeople(library)

        assertTrue("must fall back to the sample trio", chosen.all { it.metadata.recordId < 0 })
    }

    @Test
    fun `a crowded event is capped so the preview stays readable`() {
        val library = (1..8).map { record(it.toLong(), personId = it.toLong(), noon, eventId = 7) }

        assertEquals(3, PreviewSubjects.severalPeople(library).size)
    }
}
