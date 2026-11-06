package inga.bpmetrics.export

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** What the queue says about a render, and what "Clear finished" leaves. */
class RenderQueueReportingTest {

    private val added = mutableListOf<String>()

    private fun job(title: String, status: RenderStatus): String {
        val job = RenderQueueManager.addJob(
            recordId = 1,
            recordTitle = title,
            request = ExportRequest.Video(ExportContent(emptyList(), 0L, 60_000L), ExportPreset()),
            targetUri = null,
            recordIds = listOf(1L)
        )
        if (status != RenderStatus.QUEUED) RenderQueueManager.updateJobStatus(job.id, status)
        added += job.id
        return job.id
    }

    @After
    fun tidy() = added.forEach { RenderQueueManager.removeJob(it) }

    @Test
    fun `clear finished drops saved and cancelled renders, and keeps failed ones`() {
        val waiting = job("Waiting", RenderStatus.QUEUED)
        val running = job("Running", RenderStatus.RENDERING)
        val failed = job("Failed", RenderStatus.FAILED)
        job("Saved", RenderStatus.COMPLETED)
        job("Cancelled", RenderStatus.CANCELLED)

        RenderQueueManager.clearFinished()

        val left = RenderQueueManager.queue.value.map { it.id }.filter { it in added }
        assertEquals(listOf(waiting, running, failed), left)
    }

    @Test
    fun `the app's own failures keep their words`() {
        val reason = "Not enough space: it needs about 900 MB free while it renders, and the phone has 200 MB."
        assertEquals(reason, RenderFailure.describe(IOException(reason)))
    }

    @Test
    fun `the system's get a sentence`() {
        assertTrue(RenderFailure.describe(SecurityException("Permission Denial: uid 10123")).startsWith("BPMetrics can no longer open"))
        assertEquals("Something went wrong while rendering.", RenderFailure.describe(IllegalStateException()))
        assertEquals("Something went wrong: codec stalled", RenderFailure.describe(IllegalStateException("codec stalled")))
    }
}
