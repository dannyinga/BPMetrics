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

    @Test
    fun `isValidBpm accepts the inclusive bounds and rejects outside them`() {
        assertTrue(BpmDataPoint.isValidBpm(BpmDataPoint.MIN_BPM))
        assertTrue(BpmDataPoint.isValidBpm(BpmDataPoint.MAX_BPM))
        assertTrue(BpmDataPoint.isValidBpm(75.0))

        assertFalse(BpmDataPoint.isValidBpm(-0.1))
        assertFalse(BpmDataPoint.isValidBpm(250.1))
        assertFalse(BpmDataPoint.isValidBpm(1000.0))
    }

    @Test
    fun `isValidBpm agrees with what the constructor accepts`() {
        // Producers filter with isValidBpm to avoid constructing a point that throws.
        // If these ever disagree, a sensor artifact would slip through and fail later.
        listOf(-60.0, 0.0, 75.0, 250.0, 260.0).forEach { bpm ->
            val constructorAccepts = try {
                BpmDataPoint(1000L, bpm)
                true
            } catch (e: IllegalArgumentException) {
                false
            }
            assertEquals("Mismatch for bpm=$bpm", constructorAccepts, BpmDataPoint.isValidBpm(bpm))
        }
    }
}
