package inga.bpmetrics.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a clock correction is described before it is applied. */
class ShiftTimeTest {

    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `a watch that was weeks out reads in days, hours and minutes`() {
        assertEquals(
            "Moves 74 days, 3 h, 12 min later",
            describeShift(74 * day + 3 * hour + 12 * minute + 40 * second)
        )
    }

    @Test
    fun `seconds show only when the move is under a day`() {
        assertEquals("Moves 2 min, 5 s earlier", describeShift(-(2 * minute + 5 * second)))
    }

    @Test
    fun `one day is singular`() {
        assertEquals("Moves 1 day later", describeShift(day))
    }

    @Test
    fun `a sub-second move still says something`() {
        assertEquals("Moves under a second later", describeShift(400))
    }
}
