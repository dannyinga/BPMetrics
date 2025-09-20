package inga.bpmetrics.core

import org.junit.Assert.*
import org.junit.Test

class BpmDataPointTest {

    @Test
    fun `BpmDataPoint initialization`() {
        val timestamp = System.currentTimeMillis()
        val bpm = 75
        val dataPoint = BpmDataPoint(timestamp, bpm)

        assertEquals(timestamp, dataPoint.timestamp)
        assertEquals(bpm, dataPoint.bpm)
    }

    @Test
    fun `BpmDataPoint equality`() {
        val timestamp = System.currentTimeMillis()
        val dp1 = BpmDataPoint(timestamp, 80)
        val dp2 = BpmDataPoint(timestamp, 80)

        assertEquals(dp1, dp2)
    }

    @Test
    fun `BpmDataPoint comparator works`() {
        val dp1 = BpmDataPoint(5L, 70)
        val dp2 = BpmDataPoint(1L, 85)
        val dp3 = BpmDataPoint(3L, 85)

        val sortedDpList = listOf<BpmDataPoint>(dp1, dp2, dp3).sorted()
        assertEquals(listOf(dp2, dp3, dp1), sortedDpList)
    }
}
