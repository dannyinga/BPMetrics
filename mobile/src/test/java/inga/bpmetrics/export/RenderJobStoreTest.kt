package inga.bpmetrics.export

import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.BpmRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a persisted render job has to survive.
 *
 * The queue is written by one process and read by the next, so anything lost in the encoding shows
 * up as a render that comes back wrong — or not at all — after a restart, which is both the hardest
 * moment to notice it and the worst one to debug it in.
 */
class RenderJobStoreTest {

    private fun record(id: Long) = BpmRecordWithPoints(
        metadata = BpmRecordEntity(
            recordId = id,
            title = "Recording $id",
            date = 0L,
            startTime = 1_700_000_000_000L,
            endTime = 1_700_000_060_000L,
            durationMs = 60_000L
        ),
        dataPoints = emptyList(),
        minDataPoint = null,
        maxDataPoint = null
    )

    @Test
    fun `record ids survive the round trip`() {
        val ids = listOf(7L, 8L, 900L)

        assertEquals(ids, RenderJobStore.decodeIds(RenderJobStore.encodeIds(ids)))
    }

    @Test
    fun `an empty id list decodes to an empty list rather than a phantom entry`() {
        assertEquals(emptyList<Long>(), RenderJobStore.decodeIds(RenderJobStore.encodeIds(emptyList())))
    }

    @Test
    fun `colours survive the round trip, including the negative ones`() {
        // ARGB values are negative signed Ints whenever the alpha byte is set, which is always.
        // An encoding that only handled positives would drop every opaque colour there is.
        val colors = mapOf(7L to 0xFFF44336.toInt(), 8L to 0xFF42A5F5.toInt())

        assertEquals(colors, RenderJobStore.decodeColors(RenderJobStore.encodeColors(colors)))
    }

    @Test
    fun `a malformed colour is skipped rather than taking the job with it`() {
        val decoded = RenderJobStore.decodeColors("7:-65536,nonsense,8:-1")

        assertEquals(mapOf(7L to -65536, 8L to -1), decoded)
    }

    @Test
    fun `a restored job gets its recordings back from the library`() {
        val job = RenderJob(
            recordId = 7,
            recordTitle = "Subtronics",
            config = VideoExporter.VideoExportConfig(
                imageConfig = ImageExporter.ImageExportConfig(),
                records = emptyList()
            ),
            targetUri = null,
            recordIds = listOf(7L, 8L)
        )

        val filled = RenderJobStore.rehydrate(job, listOf(record(7), record(8), record(99)))

        assertEquals(listOf(7L, 8L), filled?.config?.records?.map { it.metadata.recordId })
    }

    @Test
    fun `a job whose recordings were deleted fails instead of rendering something else`() {
        // The alternative is worse than an error: rendering whatever is left would produce a video
        // of the wrong people, and nothing about it would look wrong.
        val job = RenderJob(
            recordId = 7,
            recordTitle = "Subtronics",
            config = VideoExporter.VideoExportConfig(
                imageConfig = ImageExporter.ImageExportConfig(),
                records = emptyList()
            ),
            targetUri = null,
            recordIds = listOf(7L, 8L)
        )

        assertNull(RenderJobStore.rehydrate(job, listOf(record(99))))
    }

    @Test
    fun `a job that already has its recordings is left alone`() {
        val loaded = listOf(record(7))
        val job = RenderJob(
            recordId = 7,
            recordTitle = "Subtronics",
            config = VideoExporter.VideoExportConfig(
                imageConfig = ImageExporter.ImageExportConfig(),
                records = loaded
            ),
            targetUri = null,
            recordIds = listOf(7L)
        )

        assertTrue(RenderJobStore.rehydrate(job, emptyList()) === job)
    }

    @Test
    fun `only a finished-badly job can be retried`() {
        fun job(status: RenderStatus) = RenderJob(
            recordId = 7,
            recordTitle = "Subtronics",
            config = VideoExporter.VideoExportConfig(imageConfig = ImageExporter.ImageExportConfig()),
            targetUri = null,
            status = status
        )

        assertTrue(job(RenderStatus.FAILED).isRetryable)
        assertTrue(job(RenderStatus.CANCELLED).isRetryable)
        // Retrying these would either duplicate a finished video or fight the render in flight.
        assertTrue(!job(RenderStatus.COMPLETED).isRetryable)
        assertTrue(!job(RenderStatus.RENDERING).isRetryable)
        assertTrue(!job(RenderStatus.QUEUED).isRetryable)
    }

    @Test
    fun `a job describes itself well enough to tell it from its siblings`() {
        val job = RenderJob(
            recordId = 7,
            recordTitle = "Subtronics · 21:04",
            config = VideoExporter.VideoExportConfig(imageConfig = ImageExporter.ImageExportConfig()),
            targetUri = null,
            recordIds = listOf(7L, 8L),
            presetName = "Story 9:16",
            sourceLabel = "Lost Lands"
        )

        assertEquals("Lost Lands · 2 recordings · Story 9:16", job.summary)
    }

    @Test
    fun `one recording is described in the singular`() {
        val job = RenderJob(
            recordId = 7,
            recordTitle = "Subtronics",
            config = VideoExporter.VideoExportConfig(imageConfig = ImageExporter.ImageExportConfig()),
            targetUri = null,
            recordIds = listOf(7L),
            presetName = "Landscape 1080p"
        )

        assertEquals("1 recording · Landscape 1080p", job.summary)
    }
}
