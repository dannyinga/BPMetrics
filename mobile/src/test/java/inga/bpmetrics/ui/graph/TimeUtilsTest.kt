package inga.bpmetrics.ui.graph

import inga.bpmetrics.ui.util.StringFormatHelpers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reading back the times the app itself prints.
 *
 * The whole reason this exists: the split dialogs formatted a clock time one way and parsed it
 * another, so the app could not read what it had just written and told people their times were
 * invalid. A round-trip is the only test that catches that class of bug, so every case here is one.
 */
class TimeUtilsTest {

    private val pacific: ZoneId = ZoneId.of("America/Los_Angeles")

    private fun at(hour: Int, minute: Int, second: Int = 0, day: Int = 14): Long =
        ZonedDateTime.of(2025, 6, day, hour, minute, second, 0, pacific).toInstant().toEpochMilli()

    @After
    fun restoreClockPreference() {
        StringFormatHelpers.use24Hour = false
    }

    @Test
    fun `a single-digit hour parses`() {
        // The bug. "h:mm:ss a" is what the app prints; "hh:mm:ss a" is a fixed-width field that
        // refuses it, so nine hours in twelve were unreadable.
        assertEquals(21, TimeUtils.parseClockTime("9:04:00 PM")!!.hour)
        assertEquals(21, TimeUtils.parseClockTime("09:04:00 PM")!!.hour)
    }

    @Test
    fun `the shapes people actually type`() {
        assertEquals(21, TimeUtils.parseClockTime("9:04 pm")!!.hour)
        assertEquals(21, TimeUtils.parseClockTime("21:04")!!.hour)
        assertEquals(21, TimeUtils.parseClockTime("21:04:30")!!.hour)
        // The narrow no-break space CLDR puts in front of the marker on newer platforms.
        assertEquals(21, TimeUtils.parseClockTime("9:04 PM")!!.hour)
        assertEquals(21, TimeUtils.parseClockTime("  9:04   PM ")!!.hour)
        assertNull(TimeUtils.parseClockTime("not a time"))
        assertNull(TimeUtils.parseClockTime(""))
    }

    @Test
    fun `what formatClockTime writes is what parseClockTime reads`() {
        listOf(true, false).forEach { twentyFour ->
            StringFormatHelpers.use24Hour = twentyFour
            (0..23).forEach { hour ->
                val written = TimeUtils.formatClockTime(at(hour, 37, 5), pacific)
                assertNotNull("could not read back \"$written\"", TimeUtils.parseClockTime(written))
                assertEquals(
                    "round trip failed for \"$written\"",
                    0L,
                    TimeUtils.parseClockTimeToRelativeMs(written, at(hour, 37, 5), pacific)
                )
            }
        }
    }

    @Test
    fun `a time after midnight belongs to the night, not the morning`() {
        // The set began at 23:40 and ended at 00:20. Anchoring on the start's date made that end
        // twenty-three hours and twenty minutes *before* it, which every caller refused.
        val start = at(23, 40)
        assertEquals(40 * 60_000L, TimeUtils.parseClockTimeToRelativeMs("12:20 AM", start, pacific))
    }

    @Test
    fun `a time just before the anchor stays just before it`() {
        // Nearest occurrence, not next: widening a selection backwards is an ordinary thing to ask
        // for, and rolling forward would answer it with tomorrow.
        val anchor = at(21, 0)
        assertEquals(-5 * 60_000L, TimeUtils.parseClockTimeToRelativeMs("8:55 PM", anchor, pacific))
    }

    @Test
    fun `the recording's own clock decides, not the reader's`() {
        // Same instant, two zones. A recording made at the Gorge is entered in the times it
        // happened at, which is the clock the rest of its page prints.
        val start = at(21, 0)
        val eastern = ZoneId.of("America/New_York")
        assertEquals(0L, TimeUtils.parseClockTimeToRelativeMs("9:00 PM", start, pacific))
        assertEquals(0L, TimeUtils.parseClockTimeToRelativeMs("12:00 AM", start, eastern))
    }

    @Test
    fun `elapsed times survive the round trip`() {
        listOf(0L, 999L, 90_000L, 3_600_000L, 5_425_678L).forEach { ms ->
            assertEquals(ms, TimeUtils.parseToMs(TimeUtils.formatMs(ms)))
        }
    }
}
