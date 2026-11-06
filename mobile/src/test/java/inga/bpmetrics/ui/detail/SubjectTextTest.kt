package inga.bpmetrics.ui.detail

import inga.bpmetrics.ui.util.StringFormatHelpers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The lines under a detail page's title (S15.2, S15.4).
 */
class SubjectTextTest {

    private val gorge = ZoneId.of("America/Los_Angeles")
    private val chicago = ZoneId.of("America/Chicago")

    private fun at(zone: ZoneId, month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Before
    fun patterns() {
        StringFormatHelpers.datePattern = "MMMM d, yyyy"
        StringFormatHelpers.use24Hour = false
    }

    @Test
    fun `a three-day festival reads as three days`() {
        val text = SubjectText.span(at(gorge, 5, 22, 14), at(gorge, 5, 24, 23, 30), gorge, gorge)
        assertEquals("May 22, 2026, 2:00 PM – May 24, 2026, 11:30 PM", text)
    }

    @Test
    fun `one night reads as one date with its times`() {
        val text = SubjectText.span(at(gorge, 5, 24, 19), at(gorge, 5, 24, 23, 30), gorge, gorge)
        assertEquals("May 24, 2026 · 7:00 PM–11:30 PM", text)
    }

    @Test
    fun `a venue in another zone is named`() {
        val text = SubjectText.span(at(gorge, 5, 24, 19), at(gorge, 5, 24, 23, 30), gorge, chicago)
        assertEquals("May 24, 2026 · 7:00 PM–11:30 PM Los Angeles time", text)
    }

    @Test
    fun `a bare offset is named as one`() {
        assertEquals("UTC−7", SubjectText.zoneLabel(ZoneId.of("-07:00")))
        assertEquals("UTC+5:30", SubjectText.zoneLabel(ZoneId.of("+05:30")))
    }

    @Test
    fun `an event with nothing in it says so`() {
        assertEquals("Festival · Nothing in it yet · The Gorge", SubjectText.event("Festival", null, null, gorge, gorge, "The Gorge"))
    }

    @Test
    fun `counts leave off what says nothing`() {
        assertEquals("1 recording", SubjectText.counts(recordings = 1, people = 1))
        assertEquals("11 recordings · 5 people · 3d 3h long", SubjectText.counts(11, 5, length = "3d 3h long"))
        assertEquals("2 events · 4 recordings · 2 people", SubjectText.counts(4, 2, events = 2))
    }

    @Test
    fun `a festival's length reads in days`() {
        assertEquals("3d 3h", inga.bpmetrics.ui.analysis.shortDuration(75 * 3_600_000L + 16 * 60_000L))
        assertEquals("1h 24m", inga.bpmetrics.ui.analysis.shortDuration(84 * 60_000L))
    }
}
