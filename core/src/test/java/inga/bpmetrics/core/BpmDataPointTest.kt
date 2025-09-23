package inga.bpmetrics.core

import org.junit.Assert.*
import org.junit.Test

class BpmDataPointTest {

    @Test
    fun `BpmDataPoint initialization`() {
        val timestamp = System.currentTimeMillis()
        val bpm = 75.0
        val dataPoint = BpmDataPoint(timestamp, bpm)

        assertEquals(timestamp, dataPoint.timestamp)
        assertEquals(bpm, dataPoint.bpm, 0.0)
    }

    @Test
    fun `BpmDataPoint invalid parameters throw exceptions`() {
        assertThrows(IllegalArgumentException::class.java) {
            BpmDataPoint(-1000L, 60.0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            BpmDataPoint(1000L, -60.0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            BpmDataPoint(1000L, 260.0)
        }
    }

    @Test
    fun `BpmDataPoint equality`() {
        val timestamp = System.currentTimeMillis()
        val dp1 = BpmDataPoint(timestamp, 80.0)
        val dp2 = BpmDataPoint(timestamp, 80.0)

        assertEquals(dp1, dp2)
    }

    @Test
    fun `BpmDataPoint comparator works`() {
        val dp1 = BpmDataPoint(5L, 70.0)
        val dp2 = BpmDataPoint(1L, 85.0)
        val dp3 = BpmDataPoint(3L, 85.0)

        val sortedDpList = listOf<BpmDataPoint>(dp1, dp2, dp3).sorted()
        assertEquals(listOf(dp2, dp3, dp1), sortedDpList)
    }

    @Test
    fun `BpmDataPoint toString works`() {
        val dp = BpmDataPoint(5L, 70.0)

        assertEquals("Timestamp: 0m 0s 5ms, BPM: 70.0", dp.toString())
    }
}
