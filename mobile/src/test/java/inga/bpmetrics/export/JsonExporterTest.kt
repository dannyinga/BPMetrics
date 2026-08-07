package inga.bpmetrics.export

import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JsonExporterTest {

    @Test
    fun `toJsonString serializes record metadata, deviceId, wearerName, and category tags`() {
        val record = BpmRecord(
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

        val jsonString = JsonExporter.toJsonString(listOf(record))

        assertNotNull(jsonString)
        assert(jsonString.contains("Concert Session"))
        assert(jsonString.contains("GalaxyWatch5_John"))
        assert(jsonString.contains("John Doe"))
        assert(jsonString.contains("5:Rock"))
    }
}
