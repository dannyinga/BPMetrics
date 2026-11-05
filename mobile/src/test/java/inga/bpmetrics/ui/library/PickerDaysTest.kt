package inga.bpmetrics.ui.library

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The date filter's days are the phone's days.
 *
 * Material's picker answers in UTC midnights. West of Greenwich, "Mar 14" as an instant is the
 * evening of the 13th, so the range read "Mar 13 – Mar 14" and dropped the evening shows that were
 * the reason for picking the 14th (Audit #5).
 */
class PickerDaysTest {

    private val phoenix = ZoneId.of("America/Phoenix") // UTC-7, no daylight saving
    private val tokyo = ZoneId.of("Asia/Tokyo") // UTC+9

    /** What the picker hands back for a day: midnight UTC. */
    private fun picked(month: Int, day: Int) =
        LocalDateTime.of(2026, month, day, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun at(zone: ZoneId, month: Int, day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `an evening show on the picked day is inside the range, west of UTC`() {
        val (start, end) = PickerDays.toRange(picked(3, 14), null, phoenix)

        // 9pm on the 14th in Phoenix is 4am on the 15th in UTC; the old range ended at 5pm.
        assertTrue(at(phoenix, 3, 14, 21) in start..end)
        // The evening before is not, which the old range included.
        assertTrue(at(phoenix, 3, 13, 21) !in start..end)
    }

    @Test
    fun `the range runs from the first day's midnight to the last day's end, locally`() {
        val (start, end) = PickerDays.toRange(picked(3, 14), picked(3, 16), phoenix)
        assertEquals(at(phoenix, 3, 14, 0), start)
        assertEquals(at(phoenix, 3, 17, 0) - 1, end)
    }

    @Test
    fun `east of UTC the picked day is the local day too`() {
        val (start, end) = PickerDays.toRange(picked(3, 14), null, tokyo)
        assertTrue(at(tokyo, 3, 14, 0, 30) in start..end)
        assertTrue(at(tokyo, 3, 14, 23, 30) in start..end)
        assertTrue(at(tokyo, 3, 15, 0, 30) !in start..end)
    }

    @Test
    fun `reopening the picker shows the days that were picked`() {
        val range = PickerDays.toRange(picked(3, 14), picked(3, 16), phoenix)
        assertEquals(picked(3, 14) to picked(3, 16), PickerDays.toPicker(range, phoenix))
    }

    @Test
    fun `a day that changes the clock is still one whole day`() {
        // Clocks go forward on Mar 8 2026 in New York: the day is 23 hours long.
        val newYork = ZoneId.of("America/New_York")
        val (start, end) = PickerDays.toRange(picked(3, 8), null, newYork)
        assertEquals(23 * 3_600_000L - 1, end - start)
    }
}
