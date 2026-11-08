package inga.bpmetrics.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the graph's header says about when it is: the clock's main line and the small one above. */
class HeaderClockTest {

    private val zone = ZoneId.of("America/Chicago")
    /** 12:23:35 AM on October 17, 2026, in Chicago. */
    private val at = ZonedDateTime.of(2026, 10, 17, 0, 23, 35, 0, zone).toInstant().toEpochMilli()
    private val elapsed = 42_000L

    private fun lines(look: ExportPreset) = VideoFrameRenderer.clockLinesFor(look, at, elapsed, zone)
    private val time = inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString(at, zone, withSeconds = true)
    private val into = inga.bpmetrics.ui.util.StringFormatHelpers.getDurationString(elapsed)

    @Test
    fun `nothing to say with the clock and date off`() {
        assertNull(lines(ExportPreset(clockMode = ClockMode.NONE, showDate = false)))
    }

    @Test
    fun `the time of day alone is one line`() {
        assertEquals(VideoFrameRenderer.ClockLines(time, null), lines(ExportPreset(clockMode = ClockMode.CLOCK)))
    }

    @Test
    fun `the date goes above the time, with its year`() {
        val look = ExportPreset(clockMode = ClockMode.CLOCK, showDate = true, dateStyle = DateStyle.MONTH_DAY_YEAR)
        assertEquals(VideoFrameRenderer.ClockLines(time, "Oct 17, 2026"), lines(look))
    }

    @Test
    fun `both puts the time into the session above the time of day, after the date`() {
        assertEquals(VideoFrameRenderer.ClockLines(time, into), lines(ExportPreset(clockMode = ClockMode.BOTH)))
        val dated = ExportPreset(clockMode = ClockMode.BOTH, showDate = true, dateStyle = DateStyle.YEAR_MONTH_DAY)
        assertEquals(VideoFrameRenderer.ClockLines(time, "2026-10-17  ·  $into"), lines(dated))
    }

    @Test
    fun `with no clock, the date takes its place`() {
        val look = ExportPreset(clockMode = ClockMode.NONE, showDate = true, dateStyle = DateStyle.NUMERIC)
        assertEquals(VideoFrameRenderer.ClockLines("10/17/2026", null), lines(look))
    }

    @Test
    fun `elapsed with the date has the date above`() {
        val look = ExportPreset(clockMode = ClockMode.ELAPSED, showDate = true)
        assertEquals(VideoFrameRenderer.ClockLines(into, "Oct 17, 2026"), lines(look))
    }
}
