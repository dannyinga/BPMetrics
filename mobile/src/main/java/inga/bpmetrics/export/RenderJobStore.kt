package inga.bpmetrics.export

import android.net.Uri
import android.util.Log
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.RenderJobDao
import inga.bpmetrics.library.RenderJobEntity

/**
 * Turns a queued render into a row and back again.
 *
 * The awkward part of persisting the queue is that a [VideoExporter.VideoExportConfig] is half
 * appearance and half content, and only the appearance half already knows how to serialize itself.
 * So this splits it the same way [ExportPreset] does: the look goes out as preset JSON, and the
 * content — which recordings, which clip, what window — goes into columns.
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
            if (job.config.records.isNotEmpty()) return job
            val wanted = job.recordIds.toSet()
            val found = available.filter { it.metadata.recordId in wanted }
            if (found.isEmpty()) return null
            return job.copy(config = job.config.copy(records = found))
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
        val image = config.imageConfig
        return RenderJobEntity(
            jobId = id,
            recordId = recordId,
            title = recordTitle,
            recordIdsCsv = encodeIds(recordIds.ifEmpty { config.records.map { it.metadata.recordId } }),
            // The look, in the app's one format for a look.
            presetJson = ExportPreset.from(config).toJson(),
            colorsCsv = encodeColors(image.customRecordColors),
            graphTitle = image.graphTitle,
            startTimeMs = image.startTimeMs,
            endTimeMs = image.endTimeMs,
            overlayUri = config.overlayVideoUri?.toString(),
            overlayStartedAtMs = config.overlayStartedAtMs,
            targetUri = targetUri?.toString(),
            status = status.name,
            error = error,
            presetName = presetName,
            sourceLabel = sourceLabel,
            recordCount = recordIds.size.takeIf { it > 0 } ?: config.records.size,
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

        val base = VideoExporter.VideoExportConfig(
            imageConfig = ImageExporter.ImageExportConfig(
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                customRecordColors = decodeColors(colorsCsv),
                graphTitle = graphTitle,
                alignByElapsedTime = false
            ),
            overlayVideoUri = overlayUri?.let { runCatching { Uri.parse(it) }.getOrNull() },
            overlayStartedAtMs = overlayStartedAtMs,
            // Filled in by rehydrate, once the library is to hand.
            records = emptyList()
        )

        return RenderJob(
            id = jobId,
            recordId = recordId,
            recordTitle = title,
            config = preset.applyTo(base),
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
