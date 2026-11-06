package inga.bpmetrics.export

import android.net.Uri
import android.util.Log
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.RenderJobDao
import inga.bpmetrics.library.RenderJobEntity

/**
 * Turns a queued render into a row and back again.
 *
 * A job is an [ExportRequest.Video]: the look is its [ExportPreset], which goes out as its own
 * JSON, and the content (which recordings, which clip, what window) goes into columns.
 *
 * Records are stored as *ids*. Writing whole `BpmRecordWithPoints`s here would copy the library into a second
 * table that starts drifting from it immediately, and a queue row is not the right place to be the
 * authority on what someone's heart rate did.
 */
class RenderJobStore(private val dao: RenderJobDao) {

    companion object {
        private const val TAG = "RenderJobStore"

        /** What a job left mid-render is reported as, since the process that knew is gone. */
        const val INTERRUPTED = "Interrupted when the app closed"

        fun encodeIds(ids: Collection<Long>): String = ids.joinToString(",")

        fun decodeIds(csv: String): List<Long> =
            csv.split(',').mapNotNull { it.trim().toLongOrNull() }

        fun encodeColors(colors: Map<Long, Int>): String =
            colors.entries.joinToString(",") { "${it.key}:${it.value}" }

        fun decodeColors(csv: String): Map<Long, Int> = csv.split(',')
            .mapNotNull { pair ->
                val parts = pair.split(':')
                val id = parts.getOrNull(0)?.trim()?.toLongOrNull()
                val argb = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (id != null && argb != null) id to argb else null
            }
            .toMap()

        /**
         * Puts the recordings back into a restored job.
         *
         * A restored job carries record ids rather than record data, so this is what makes it
         * runnable again. A job whose recordings have since been deleted returns null: failing
         * honestly is the only correct answer, since there is nothing left to draw.
         */
        fun rehydrate(job: RenderJob, available: List<BpmRecordWithPoints>): RenderJob? {
            if (job.request.content.records.isNotEmpty()) return job
            val wanted = job.recordIds.toSet()
            val found = available.filter { it.metadata.recordId in wanted }
            if (found.isEmpty()) return null
            return job.copy(request = job.request.copy(content = job.request.content.copy(records = found)))
        }
    }

    /** Everything on disk, oldest first, with anything left mid-render marked as interrupted. */
    suspend fun restore(): List<RenderJob> {
        dao.markInterrupted(
            rendering = RenderStatus.RENDERING.name,
            failed = RenderStatus.FAILED.name,
            reason = INTERRUPTED
        )
        return dao.getAll().mapNotNull { it.toJob() }
    }

    suspend fun save(job: RenderJob) {
        runCatching { dao.upsert(job.toEntity()) }
            .onFailure { Log.e(TAG, "Could not persist render job ${job.id}", it) }
    }

    suspend fun saveAll(jobs: List<RenderJob>) {
        runCatching { dao.upsertAll(jobs.map { it.toEntity() }) }
            .onFailure { Log.e(TAG, "Could not persist the render queue", it) }
    }

    suspend fun delete(jobId: String) {
        runCatching { dao.delete(jobId) }
            .onFailure { Log.e(TAG, "Could not drop render job $jobId", it) }
    }

    /** Drops rows for jobs the in-memory queue no longer has, so the two cannot diverge. */
    suspend fun retainOnly(jobs: List<RenderJob>) {
        runCatching {
            if (jobs.isEmpty()) dao.deleteAll() else dao.deleteMissing(jobs.map { it.id })
        }.onFailure { Log.e(TAG, "Could not prune the render queue", it) }
    }

    private fun RenderJob.toEntity(): RenderJobEntity {
        val content = request.content
        return RenderJobEntity(
            jobId = id,
            recordId = recordId,
            title = recordTitle,
            recordIdsCsv = encodeIds(recordIds.ifEmpty { content.records.map { it.metadata.recordId } }),
            // The whole look, as the preset it is. It went through `ExportPreset.from(config)`,
            // which didn't know about the readout pills, the clock, the wordmark or per-cast
            // framing, so a job restored after the app was killed rendered with defaults
            // (Audit #25). Nothing is copied now, so nothing can be left behind.
            presetJson = request.preset.toJson(),
            colorsCsv = encodeColors(content.colours),
            graphTitle = content.title,
            startTimeMs = content.startMs,
            endTimeMs = content.endMs,
            overlayUri = request.clip?.toString(),
            overlayStartedAtMs = request.clipStartedAtMs,
            targetUri = targetUri?.toString(),
            status = status.name,
            error = error,
            presetName = presetName,
            sourceLabel = sourceLabel,
            recordCount = recordIds.size.takeIf { it > 0 } ?: content.records.size,
            queuedAt = queuedAt
        )
    }

    private fun RenderJobEntity.toJob(): RenderJob? {
        val preset = ExportPreset.fromJson(presetJson) ?: run {
            // A row whose look cannot be read is not recoverable into something anyone asked for,
            // and rendering it with defaults would produce a video that is not the one queued.
            Log.w(TAG, "Dropping render job $jobId: its settings could not be read")
            return null
        }

        return RenderJob(
            id = jobId,
            recordId = recordId,
            recordTitle = title,
            request = ExportRequest.Video(
                content = ExportContent(
                    // Filled in by rehydrate, once the library is to hand.
                    records = emptyList(),
                    startMs = startTimeMs,
                    endMs = endTimeMs,
                    title = graphTitle,
                    colours = decodeColors(colorsCsv)
                ),
                preset = preset,
                clip = overlayUri?.let { runCatching { Uri.parse(it) }.getOrNull() },
                clipStartedAtMs = overlayStartedAtMs
            ),
            targetUri = targetUri?.let { runCatching { Uri.parse(it) }.getOrNull() },
            status = runCatching { RenderStatus.valueOf(status) }.getOrDefault(RenderStatus.QUEUED),
            error = error,
            recordIds = decodeIds(recordIdsCsv),
            presetName = presetName,
            sourceLabel = sourceLabel,
            queuedAt = queuedAt
        )
    }
}
