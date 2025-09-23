package inga.bpmetrics.core

import org.junit.Assert.*
import org.junit.Test
import java.sql.Date
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class BpmWatchRecordTest {

    @Test
    fun `BpmWatchRecord initialization`() {
        val date = Date(System.currentTimeMillis())
        val dataPoints = listOf(
            BpmDataPoint(1000L, 80.0),
            BpmDataPoint(2000L, 90.0),
            BpmDataPoint(3000L, 65.0)
        )
        val record = BpmWatchRecord(
            date = date,
            dataPoints = dataPoints,
            startTime = 50L,
            endTime = 4000L,
        )

        assertEquals(date, record.date)
        assertEquals(dataPoints, record.dataPoints)
        assertEquals(50L, record.startTime)
        assertEquals(4000L, record.endTime)
    }

    @Test
    fun `BpmWatchRecord invalid parameters throws exception`() {
        val date = Date(System.currentTimeMillis())
        val dataPoints = listOf(
            BpmDataPoint(1000L, 80.0),
            BpmDataPoint(2000L, 90.0),
            BpmDataPoint(3000L, 65.0)
        )
        val record = BpmWatchRecord(
            date = date,
            dataPoints = dataPoints,
            startTime = 50L,
            endTime = 4000L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            BpmWatchRecord(
                date = date,
                dataPoints = dataPoints,
                startTime = -20L,
                endTime = 4000L,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            BpmWatchRecord(
                date = date,
                dataPoints = dataPoints,
                startTime = 1000L,
                endTime = 50L,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            BpmWatchRecord(
                date = date,
                dataPoints = dataPoints,
                startTime = 10L,
                endTime = -10L,
            )
        }

        assertEquals(date, record.date)
        assertEquals(dataPoints, record.dataPoints)
        assertEquals(50L, record.startTime)
        assertEquals(4000L, record.endTime)
    }

    @Test
    fun `BpmWatchRecord sorting works`() {
        val record1 = BpmWatchRecord(
            date = Date(3000L),
            dataPoints = listOf(BpmDataPoint(0L, 80.0), BpmDataPoint(1L, 100.0)),
            startTime = 0L,
            endTime = 1L,
        )

        val record2 = BpmWatchRecord(
            date = Date(1000L),
            dataPoints = listOf(BpmDataPoint(0L, 70.0), BpmDataPoint(1L, 95.0)),
            startTime = 0L,
            endTime = 1L,
        )

        val recordList = listOf(record1, record2).sorted()

        assertEquals(listOf(record2, record1), recordList)
    }

    @Test
    fun `BpmWatchRecord toString works`() {
        val record1 = BpmWatchRecord(
            date = Date(3000L),
            dataPoints = listOf(BpmDataPoint(0L, 80.0), BpmDataPoint(1000L, 100.0)),
            startTime = 0L,
            endTime = 1000L,
        )

        val formatter = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.getDefault())
        val startTime = "Start Time: ${Instant.ofEpochMilli(0L)
                                        .atZone(ZoneId.systemDefault())
                                        .format(formatter)}\n"
        val endTime = "End Time: ${Instant.ofEpochMilli(1000L)
                                    .atZone(ZoneId.systemDefault())
                                    .format(formatter)}\n"

        val expectedString =    "Date: ${Date(3000L)}\n" +
                                startTime +
                                endTime +
                                "Duration: 0m 1s 0ms\n\n" +
                                "Raw Data\n" +
                                "Timestamp: 0m 0s 0ms, BPM: 80.0\n" +
                                "Timestamp: 0m 1s 0ms, BPM: 100.0\n"


        assertEquals(expectedString, record1.toString())
    }
}
