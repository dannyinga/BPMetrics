package inga.bpmetrics.ui.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The image crop fields: either clock read back, and a reason when a time can't be used. */
class ClockEntryTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    // An evening set: 8:02:30 PM to 11:40 PM.
    private val evening = at(2026, 11, 5, 20, 2, 30) to at(2026, 11, 5, 23, 40)

    private fun read(text: String, use24Hour: Boolean = false, span: Pair<Long, Long> = evening, withDate: Boolean = false) =
        ClockEntry.parse(text, span, zone, withDate, use24Hour)

    private fun value(ms: Long?) = ClockEntry.Result.Value(ms)

    @Test
    fun `shown in the user's clock`() {
        assertEquals("9:15 PM", ClockEntry.format(at(2026, 11, 5, 21, 15), zone, withDate = false, use24Hour = false))
        assertEquals("21:15", ClockEntry.format(at(2026, 11, 5, 21, 15), zone, withDate = false, use24Hour = true))
    }

    @Test
    fun `either clock is read back whatever the setting`() {
        val nineFifteen = value(at(2026, 11, 5, 21, 15))
        assertEquals(nineFifteen, read("9:15 PM"))
        assertEquals(nineFifteen, read("9:15pm"))
        assertEquals(nineFifteen, read("21:15"))
        assertEquals(nineFifteen, read("21:15", use24Hour = true))
        assertEquals(nineFifteen, read("9:15 p.m.", use24Hour = true))
    }

    @Test
    fun `a 12-hour time without AM or PM takes the half the recording covers`() {
        assertEquals(value(at(2026, 11, 5, 21, 15)), read("9:15"))
    }

    @Test
    fun `and asks when both halves are covered`() {
        val allDay = at(2026, 11, 5, 8, 0) to at(2026, 11, 5, 22, 0)
        assertEquals(ClockEntry.Result.Error("Add AM or PM."), read("9:15", span = allDay))
    }

    @Test
    fun `the minute a set starts in counts as inside it`() {
        // The set starts at 8:02:30; "8:02 PM" is that minute.
        assertEquals(value(at(2026, 11, 5, 20, 2)), read("8:02 PM"))
    }

    @Test
    fun `a time outside the recording says so, with its span`() {
        val result = read("7:00 PM")
        assertTrue(result is ClockEntry.Result.Error)
        assertEquals("Outside the recording (8:02 PM – 11:40 PM).", (result as ClockEntry.Result.Error).message)
    }

    @Test
    fun `what can't be read says what to type`() {
        assertEquals(ClockEntry.Result.Error("Type a time like 8:02 PM."), read("quarter past"))
        assertEquals(ClockEntry.Result.Error("Type a time like 20:02."), read("25:00", use24Hour = true))
        // An hour alone is too often half typed to guess at.
        assertTrue(read("9") is ClockEntry.Result.Error)
    }

    @Test
    fun `empty means the whole range`() {
        assertEquals(value(null), read("  "))
    }

    @Test
    fun `a span over midnight needs the date`() {
        val late = at(2026, 11, 5, 22, 0) to at(2026, 11, 6, 2, 0)
        assertTrue(read("1:00 AM", span = late, withDate = true) is ClockEntry.Result.Error)
        assertEquals(value(at(2026, 11, 6, 1, 0)), read("2026-11-06 1:00 AM", span = late, withDate = true))
        assertEquals(value(at(2026, 11, 6, 1, 0)), read("2026-11-06 01:00", span = late, withDate = true, use24Hour = true))
    }

    @Test
    fun `a window that ends before it starts is a problem`() {
        assertEquals("From has to be before To.", ImageCrop(startWallClockMs = 2_000, endWallClockMs = 1_000).problem)
        assertEquals(null, ImageCrop(startWallClockMs = 1_000, endWallClockMs = 2_000).problem)
        assertEquals(null, ImageCrop(startWallClockMs = 1_000).problem)
    }
}
