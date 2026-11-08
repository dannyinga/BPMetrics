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

    private fun request(records: List<BpmRecordWithPoints> = emptyList()) = ExportRequest.Video(
        content = ExportContent(records = records, startMs = 0L, endMs = 60_000L),
        preset = ExportPreset()
    )

    /** A render_jobs table in memory, enough to put a job through [RenderJobStore] and back. */
    private class FakeDao : inga.bpmetrics.library.RenderJobDao {
        val rows = linkedMapOf<String, inga.bpmetrics.library.RenderJobEntity>()
        override suspend fun getAll() = rows.values.sortedBy { it.queuedAt }
        override suspend fun upsert(job: inga.bpmetrics.library.RenderJobEntity) { rows[job.jobId] = job }
        override suspend fun upsertAll(jobs: List<inga.bpmetrics.library.RenderJobEntity>) = jobs.forEach { rows[it.jobId] = it }
        override suspend fun delete(jobId: String) { rows.remove(jobId) }
        override suspend fun deleteMissing(keep: List<String>) { rows.keys.retainAll(keep.toSet()) }
        override suspend fun deleteAll() = rows.clear()
        override suspend fun markInterrupted(rendering: String, failed: String, reason: String) {
            rows.replaceAll { _, row -> if (row.status == rendering) row.copy(status = failed, error = reason) else row }
        }
    }

    @Test
    fun `a queued job comes back from storage with exactly the look it was queued with`() = kotlinx.coroutines.test.runTest {
        // Audit #25: the job was stored through `ExportPreset.from(config)`, which didn't know about
        // the readout pills, the clock, the wordmark or per-cast framing. Killed before it ran, it
        // rendered with defaults. Now the preset itself is stored.
        val look = ExportPreset(
            name = "Story · minimal",
            width = 1080, height = 1920,
            pillShowPhoto = false, pillShowName = false, pillScale = 1.4f, pillCorner = PillCorner.TOP_LEFT,
            clockMode = ClockMode.ELAPSED,
            showWordmark = true, wordmarkOpacity = 80,
            graphLeft = 0.1f, graphTop = 0.6f, graphRight = 0.9f, graphBottom = 0.95f,
            syncOffsetMs = -1_250L, frameRate = 60
        )
        val job = RenderJob(
            recordId = 7,
            recordTitle = "Subtronics · 21:04",
            request = ExportRequest.Video(
                content = ExportContent(
                    records = listOf(record(7)), startMs = 12_000L, endMs = 48_000L,
                    title = "Subtronics | Lost Lands", colours = mapOf(7L to -65536)
                ),
                preset = look,
                clip = null,
                clipStartedAtMs = 1_700_000_012_000L
            ),
            targetUri = null,
            recordIds = listOf(7L),
            presetName = "Story · minimal"
        )
        val store = RenderJobStore(FakeDao())

        store.save(job)
        val restored = store.restore().single()

        assertEquals(look, restored.request.preset)
        assertEquals(12_000L, restored.request.content.startMs)
        assertEquals(48_000L, restored.request.content.endMs)
        assertEquals("Subtronics | Lost Lands", restored.request.content.title)
        assertEquals(mapOf(7L to -65536), restored.request.content.colours)
        assertEquals(1_700_000_012_000L, restored.request.clipStartedAtMs)
        assertEquals(listOf(7L), restored.recordIds)
    }

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
            request = request(),
            targetUri = null,
            recordIds = listOf(7L, 8L)
        )

        val filled = RenderJobStore.rehydrate(job, listOf(record(7), record(8), record(99)))

        assertEquals(listOf(7L, 8L), filled?.request?.content?.records?.map { it.metadata.recordId })
    }

    @Test
    fun `a job whose recordings were deleted fails instead of rendering something else`() {
        // The alternative is worse than an error: rendering whatever is left would produce a video
        // of the wrong people, and nothing about it would look wrong.
        val job = RenderJob(
            recordId = 7,
            recordTitle = "Subtronics",
            request = request(),
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
            request = request(loaded),
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
            request = request(),
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
            request = request(),
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
            request = request(),
            targetUri = null,
            recordIds = listOf(7L),
            presetName = "Landscape 1080p"
        )

        assertEquals("1 recording · Landscape 1080p", job.summary)
    }
}
