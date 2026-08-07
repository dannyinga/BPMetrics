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
                                "Duration: 0m 1s 0ms\n" +
                                "Device ID: Watch\n\n" +
                                "Raw Data\n" +
                                "Timestamp: 0m 0s 0ms, BPM: 80.0\n" +
                                "Timestamp: 0m 1s 0ms, BPM: 100.0\n"

        assertEquals(expectedString, record1.toString())
    }

    @Test
    fun `BpmWatchRecord toString with wearerName works`() {
        val record = BpmWatchRecord(
            date = Date(3000L),
            dataPoints = listOf(BpmDataPoint(0L, 80.0)),
            startTime = 0L,
            endTime = 1000L,
            deviceId = "GalaxyWatch5_01",
            wearerName = "John"
        )

        val str = record.toString()
        assertTrue(str.contains("Device ID: GalaxyWatch5_01"))
        assertTrue(str.contains("Wearer Name: John"))
    }

    @Test
    fun `BpmGson round-trip serialization and deserialization`() {
        val originalRecord = BpmWatchRecord(
            date = Date(1700000000000L),
            dataPoints = listOf(BpmDataPoint(0L, 80.0), BpmDataPoint(1000L, 85.5)),
            startTime = 1700000000000L,
            endTime = 1700000005000L,
            deviceId = "Pixel Watch 2",
            wearerName = "Alice"
        )

        val json = BpmGson.instance.toJson(originalRecord)
        val deserializedRecord = BpmGson.instance.fromJson(json, BpmWatchRecord::class.java)

        assertNotNull(deserializedRecord)
        assertEquals(originalRecord.date.time, deserializedRecord.date.time)
        assertEquals(originalRecord.deviceId, deserializedRecord.deviceId)
        assertEquals(originalRecord.wearerName, deserializedRecord.wearerName)
        assertEquals(originalRecord.dataPoints.size, deserializedRecord.dataPoints.size)
        assertEquals(originalRecord.dataPoints[0].bpm, deserializedRecord.dataPoints[0].bpm, 0.001)
    }

    @Test
    fun `record survives the full watch outbox to phone hop with its date intact`() {
        // Production path: the watch writes the record to its outbox table, reads it back to
        // send it, and the phone deserializes what arrives. Every hop must use BpmGson —
        // a plain Gson at any one of them writes java.sql.Date as a locale-formatted string
        // that cannot be parsed back, silently replacing the date with "now".
        val recordedAt = 1700000000000L
        val original = BpmWatchRecord(
            date = Date(recordedAt),
            dataPoints = listOf(BpmDataPoint(0L, 61.0), BpmDataPoint(5000L, 143.0)),
            startTime = recordedAt,
            endTime = recordedAt + 5000L,
            deviceId = "Pixel Watch 2",
            wearerName = "Alice"
        )

        // Hop 1: watch serializes into the pending_records outbox
        val outboxJson = BpmGson.instance.toJson(original)
        val fromOutbox = BpmGson.instance.fromJson(outboxJson, BpmWatchRecord::class.java)

        // Hop 2: watch re-serializes for the Data Layer, phone reads it
        val wireJson = BpmGson.instance.toJson(fromOutbox)
        val onPhone = BpmGson.instance.fromJson(wireJson, BpmWatchRecord::class.java)

        assertEquals(recordedAt, onPhone.date.time)
        assertEquals(original.startTime, onPhone.startTime)
        assertEquals(original.endTime, onPhone.endTime)
        assertEquals(original.deviceId, onPhone.deviceId)
        assertEquals(original.wearerName, onPhone.wearerName)
        assertEquals(original.dataPoints.size, onPhone.dataPoints.size)
        assertEquals(143.0, onPhone.dataPoints[1].bpm, 0.001)
    }
}
