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

    private fun recordWithPoints(id: Long, points: Int) = BpmRecordWithPoints(
        metadata = BpmRecordEntity(
            recordId = id,
            title = "Set $id",
            date = 1_000_000L,
            startTime = 1_000_000L,
            endTime = 1_000_000L + points * 1000L,
            durationMs = points * 1000L
        ),
        dataPoints = (0 until points).map {
            BpmDataPointEntity(dataPointId = it.toLong(), recordOwnerId = id, timestamp = it * 1000L, bpm = 120.0)
        },
        minDataPoint = null,
        maxDataPoint = null
    )

    @Test
    fun `a backup written to a stream reads back the same as one built as a string`() {
        val records = listOf(recordWithPoints(1, 300), recordWithPoints(2, 5))
        val out = java.io.ByteArrayOutputStream()

        JsonExporter.writeBackup(out, JsonExporter.buildBackup(records))
        val streamed = JsonExporter.parseBackup(out.toString(Charsets.UTF_8.name()))

        assertNotNull(streamed)
        assertEquals(listOf(300, 5), streamed!!.records.map { it.dataPoints.size })
        assertEquals(
            JsonExporter.parseBackup(JsonExporter.toBackupJson(records))!!.records,
            streamed.records
        )
    }

    @Test
    fun `readings are written compactly, one line rather than four each`() {
        val json = JsonExporter.toBackupJson(listOf(recordWithPoints(1, 100)))

        // Pretty-printing put every reading on four indented lines, more than doubling a file
        // that is nearly all readings.
        assertEquals(false, json.contains('\n'))
        assert(json.contains("""{"timestamp":1000,"bpm":120.0}""")) { json.take(400) }
    }

    @Test
    fun `a single record on its own still reads`() {
        val single = """{"title":"Lone","startTime":1000,"endTime":2000,"dataPoints":[{"timestamp":0,"bpm":70.0}]}"""

        val parsed = JsonExporter.parseBackup(single)

        assertNotNull(parsed)
        assertEquals(listOf("Lone"), parsed!!.records.map { it.title })
    }

    @Test
    fun `json that is not a backup is refused rather than read as an empty one`() {
        val reason = JsonExporter.parseBackup(java.io.StringReader("""{"hello":"world"}""")) {
            java.io.StringReader("""{"hello":"world"}""")
        }

        assertEquals(BackupFile.Unreadable("That file is not a BPMetrics backup"), reason)
    }

    @Test
    fun `a backup is recognised by its content whatever it is called`() {
        // A texted or downloaded file arrives as octet-stream with no extension, so the importer
        // decides from the first bytes. A byte order mark and leading whitespace must not hide it.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(true, JsonExporter.startsLikeJson("""{"formatVersion":6""".toByteArray()))
        assertEquals(true, JsonExporter.startsLikeJson("  \n [{".toByteArray()))
        assertEquals(true, JsonExporter.startsLikeJson(bom + "{".toByteArray()))
        assertEquals(false, JsonExporter.startsLikeJson("Timestamp,BPM\n0,70".toByteArray()))
        assertEquals(false, JsonExporter.startsLikeJson(ByteArray(0)))
    }
}
