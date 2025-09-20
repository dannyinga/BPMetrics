package inga.bpmetrics.core

import org.junit.Assert.*
import org.junit.Test
import java.sql.Date

class BpmWatchRecordTest {

    @Test
    fun `BpmWatchRecord initialization`() {
        val date = Date(System.currentTimeMillis())
        val dataPoints = listOf(
            BpmDataPoint(1000L, 80),
            BpmDataPoint(2000L, 90),
            BpmDataPoint(3000L, 65)
        )
        val max = BpmDataPoint(2000L, 90)
        val min = BpmDataPoint(3000L, 65)
        val avg = (80 + 90 + 65) / 3
        val record = BpmWatchRecord(
            date = date,
            dataPoints = dataPoints,
            startTime = 1000L,
            endTime = 3000L,
            max = max,
            min = min,
            avg = avg
        )

        assertEquals(date, record.date)
        assertEquals(dataPoints, record.dataPoints)
        assertEquals(1000L, record.startTime)
        assertEquals(3000L, record.endTime)
        assertEquals(max, record.max)
        assertEquals(min, record.min)
        assertEquals(avg, record.avg)
    }

    @Test
    fun `BpmWatchRecord max, min, avg consistency`() {
        val dataPoints = listOf(
            BpmDataPoint(0L, 70),
            BpmDataPoint(1L, 90),
            BpmDataPoint(2L, 80)
        )
        val max = dataPoints.maxByOrNull { it.bpm }!!
        val min = dataPoints.minByOrNull { it.bpm }!!
        val avg = dataPoints.map { it.bpm }.average().toInt()

        val record = BpmWatchRecord(
            date = Date(System.currentTimeMillis()),
            dataPoints = dataPoints,
            startTime = 0L,
            endTime = 2L,
            max = max,
            min = min,
            avg = avg
        )

        assertEquals(max, record.max)
        assertEquals(min, record.min)
        assertEquals(avg, record.avg)
    }

    @Test
    fun `BpmWatchRecord comparator by max bpm works`() {
        val record1 = BpmWatchRecord(
            date = Date(0L),
            dataPoints = listOf(BpmDataPoint(0L, 80), BpmDataPoint(1L, 100)),
            startTime = 0L,
            endTime = 1L,
            max = BpmDataPoint(1L, 100),
            min = BpmDataPoint(0L, 80),
            avg = 90
        )

        val record2 = BpmWatchRecord(
            date = Date(0L),
            dataPoints = listOf(BpmDataPoint(0L, 70), BpmDataPoint(1L, 95)),
            startTime = 0L,
            endTime = 1L,
            max = BpmDataPoint(1L, 95),
            min = BpmDataPoint(0L, 70),
            avg = 82
        )

        val comparator = compareBy<BpmWatchRecord> { it.max.bpm }
        assertTrue(comparator.compare(record1, record2) > 0)
        assertTrue(comparator.compare(record2, record1) < 0)
        assertEquals(0, comparator.compare(record1, record1))
    }
}
