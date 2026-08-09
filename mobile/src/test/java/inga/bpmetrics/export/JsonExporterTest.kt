package inga.bpmetrics.export

import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.WatchEntity
import inga.bpmetrics.library.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JsonExporterTest {

    @Test
    fun `toJsonString serializes record metadata, deviceId, wearerName, and category tags`() {
        val record = BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = 10L,
                title = "Concert Session",
                description = "Live show",
                date = 1000000L,
                startTime = 1000000L,
                endTime = 1060000L,
                durationMs = 60000L,
                deviceId = "GalaxyWatch5_John",
                wearerName = "John Doe"
            ),
            dataPoints = listOf(
                BpmDataPointEntity(dataPointId = 1L, recordOwnerId = 10L, timestamp = 0L, bpm = 75.0),
                BpmDataPointEntity(dataPointId = 2L, recordOwnerId = 10L, timestamp = 1000L, bpm = 82.0)
            ),
            minDataPoint = null,
            maxDataPoint = null,
            tags = listOf(
                TagEntity(tagId = 1L, name = "Rock", parentCategoryId = 5L)
            )
        )

        val jsonString = JsonExporter.toBackupJson(
            records = listOf(record),
            categories = listOf(CategoryEntity(categoryId = 5L, name = "Genre"))
        )

        assertNotNull(jsonString)
        assert(jsonString.contains("Concert Session"))
        assert(jsonString.contains("GalaxyWatch5_John"))
        assert(jsonString.contains("John Doe"))

        // The category *name*, not its id. This test previously asserted "5:Rock" — the id — while
        // the importer has always expected "Category:Tag", so tags never survived a round trip and
        // the test was pinning the bug in place.
        assert(jsonString.contains("Genre:Rock")) { "expected a named category, got: $jsonString" }
    }

    @Test
    fun `a backup carries the people and watches its records point at`() {
        val person = PersonEntity(personId = 3L, name = "Kyle", colorArgb = 0xFF00E5FF.toInt())
        val watch = WatchEntity(watchId = "watch-uuid", deviceName = "Watch A")
        val record = BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = 11L,
                title = "Subtronics",
                date = 1000L,
                startTime = 1000L,
                endTime = 2000L,
                durationMs = 1000L,
                personId = 3L,
                watchId = "watch-uuid"
            ),
            dataPoints = listOf(
                BpmDataPointEntity(dataPointId = 1L, recordOwnerId = 11L, timestamp = 0L, bpm = 120.0)
            ),
            minDataPoint = null,
            maxDataPoint = null
        )

        val json = JsonExporter.toBackupJson(listOf(record), listOf(person), listOf(watch))
        val parsed = JsonExporter.parseBackup(json)

        assertNotNull(parsed)
        // Without these a restore returns the recordings and loses who made them.
        assertEquals(listOf("Kyle"), parsed!!.people.map { it.name })
        assertEquals(person.colorArgb, parsed.people.first().colorArgb)
        assertEquals(listOf("watch-uuid"), parsed.watches.map { it.watchId })
        assertEquals("Kyle", parsed.records.first().wearerName)
        assertEquals("watch-uuid", parsed.records.first().watchId)
    }

    @Test
    fun `a bare array from an older export still reads`() {
        val legacy = """
            [{"title":"Old One","startTime":1000,"endTime":2000,
              "dataPoints":[{"timestamp":0,"bpm":70.0}]}]
        """.trimIndent()

        val parsed = JsonExporter.parseBackup(legacy)

        // A backup a later build cannot read is not a backup.
        assertNotNull(parsed)
        assertEquals(1, parsed!!.records.size)
        assertEquals("Old One", parsed.records.first().title)
    }
}
