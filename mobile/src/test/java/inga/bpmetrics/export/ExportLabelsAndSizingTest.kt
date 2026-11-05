package inga.bpmetrics.export

import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things an export says out loud: whose curve each readout is, and how much room it needs.
 */
class ExportLabelsAndSizingTest {

    private fun recording(id: Long, wearer: String, device: String = "Watch", title: String = "Untitled $id") =
        BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = id, title = title, date = 0L, startTime = 0L, endTime = 1_000L,
                durationMs = 1_000L, wearerName = wearer, deviceId = device
            ),
            dataPoints = emptyList(),
            minDataPoint = null,
            maxDataPoint = null
        )

    @Test
    fun `different people keep their plain names`() {
        assertEquals(
            listOf("Danny", "Bethany"),
            ImageExporter.distinctLabelsOf(listOf(recording(1, "Danny"), recording(2, "Bethany")))
        )
    }

    @Test
    fun `one person on two watches is told apart by the watch`() {
        assertEquals(
            listOf("Danny · Blue", "Danny · The OG"),
            ImageExporter.distinctLabelsOf(
                listOf(recording(1, "Danny", device = "Blue"), recording(2, "Danny", device = "The OG"))
            )
        )
    }

    @Test
    fun `one person on one watch is told apart by the recording`() {
        assertEquals(
            listOf("Danny · Boogie T", "Danny · SubDocta"),
            ImageExporter.distinctLabelsOf(
                listOf(recording(1, "Danny", title = "Boogie T"), recording(2, "Danny", title = "SubDocta"))
            )
        )
    }

    @Test
    fun `with nothing else to go on, a number`() {
        assertEquals(
            listOf("Danny · 1", "Danny · 2"),
            ImageExporter.distinctLabelsOf(listOf(recording(1, "Danny"), recording(2, "Danny")))
        )
    }

    @Test
    fun `step 4 and the render size a video the same way`() {
        // 1080p30 over footage, ten minutes: the case step 4 promised 1.9 GB for and the render
        // then asked for 4,670 MB (Audit #38). Now both read the same numbers.
        val sized = VideoExporter.sizing(1920, 1080, 30, hasOverlay = true, durationMs = 600_000)
        assertEquals(8_087_040, sized.bitrate)
        assertEquals(sized.bytes * 5 / 2, sized.requiredFreeBytes)
    }

    @Test
    fun `a plain background is smaller, and both are clamped`() {
        val plain = VideoExporter.sizing(1920, 1080, 30, hasOverlay = false, durationMs = 60_000)
        val footage = VideoExporter.sizing(1920, 1080, 30, hasOverlay = true, durationMs = 60_000)
        assertTrue(plain.bytes < footage.bytes)
        assertEquals(50_000_000, VideoExporter.sizing(7680, 4320, 120, true, 1_000).bitrate)
        assertEquals(1_000_000, VideoExporter.sizing(16, 16, 1, false, 1_000).bitrate)
    }
}
