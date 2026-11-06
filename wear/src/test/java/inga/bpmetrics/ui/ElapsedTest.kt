package inga.bpmetrics.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedTest {

    @Test
    fun `every unit from the largest down`() {
        assertEquals("0s", Elapsed.format(0))
        assertEquals("5s", Elapsed.format(5_400))
        assertEquals("12m 5s", Elapsed.format(12 * 60_000L + 5_000))
        assertEquals("1h 0m 5s", Elapsed.format(3_600_000L + 5_000))
        assertEquals("2h 30m 0s", Elapsed.format(9_000_000L))
    }
}
