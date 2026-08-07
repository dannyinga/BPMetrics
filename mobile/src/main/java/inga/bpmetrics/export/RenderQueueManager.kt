package inga.bpmetrics.export

import android.net.Uri
import android.util.Log
import inga.bpmetrics.library.BpmRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class RenderStatus {
    QUEUED,
    RENDERING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class RenderJob(
    val id: String = UUID.randomUUID().toString(),
    val recordId: Long,
    val recordTitle: String,
    val config: VideoExporter.VideoExportConfig,
    val targetUri: Uri?,
    val status: RenderStatus = RenderStatus.QUEUED,
    val progress: Float = 0f,
    val error: String? = null,

    /**
     * Every recording this job draws.
     *
     * Held beside the config because a restored job has no recordings in its config yet — they are
     * fetched from the library when it runs. The ids are what survives; the data does not need to.
     */
    val recordIds: List<Long> = emptyList(),

    /**
     * What this job is of, in words: the preset it was made with and where it came from.
     *
     * Stored rather than derived, so it still reads correctly after the recordings are deleted. A
     * queue of six that says nothing but "Export" six times cannot be acted on.
     */
    val presetName: String? = null,
    val sourceLabel: String? = null,
    val queuedAt: Long = System.currentTimeMillis()
) {
    /** How many recordings are drawn, whether or not they are currently loaded. */
    val recordCount: Int get() = recordIds.size.takeIf { it > 0 } ?: config.records.size

    /** Whether this can be run again as it stands, without being reconfigured. */
    val isRetryable: Boolean
        get() = status == RenderStatus.FAILED || status == RenderStatus.CANCELLED

    /** A one-line description for the queue: what it is of, and how it will look. */
    val summary: String
        get() = buildList {
            sourceLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(if (recordCount == 1) "1 recording" else "$recordCount recordings")
            presetName?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (config.overlayVideoUri != null) add("over video")
        }.joinToString(" · ")
}

/**
 * The render queue: what is waiting, what is running, and what happened to the rest.
 *
 * Backed by the database rather than only by memory. A render takes minutes, and a phone may kill
 * the app during any of them — while the queue lived only in memory, that lost every pending job
 * without leaving so much as a row saying a render had been abandoned. The only symptom was a video
 * that never appeared.
 *
 * Memory stays the source of truth for *reads*: the UI collects [queue] and the service polls it,
 * both of which happen far too often to go through disk. Disk is written behind them on the changes
 * that matter — a job added, a status moved, a job dropped — and never on progress, which is
 * meaningless after a restart because an interrupted render begins again from the start.
 */
object RenderQueueManager {
    private const val TAG = "RenderQueueManager"

    private val _queue = MutableStateFlow<List<RenderJob>>(emptyList())
    val queue: StateFlow<List<RenderJob>> = _queue.asStateFlow()

    var onJobCancelled: ((String) -> Unit)? = null

    /** Set once, by the application object. Absent in tests, where the queue stays in memory. */
    private var store: RenderJobStore? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Restores the queue from disk and starts persisting changes to it.
     *
     * Anything the database still records as rendering was interrupted — the status is only ever
     * written by a process that is now gone — so it comes back as failed with a reason, and can be
     * retried. Anything queued is still queued, and will run when the service is next started.
     */
    fun attach(store: RenderJobStore, onRestored: (Int) -> Unit = {}) {
        this.store = store
        scope.launch {
            val restored = runCatching { store.restore() }.getOrElse {
                Log.e(TAG, "Could not restore the render queue", it)
                return@launch
            }
            if (restored.isEmpty()) return@launch

            synchronized(this@RenderQueueManager) {
                // Anything already added in this process wins: it is newer than what was on disk.
                val live = _queue.value.map { it.id }.toSet()
                _queue.value = restored.filterNot { it.id in live } + _queue.value
            }
            Log.i(TAG, "Restored ${restored.size} render job(s)")
            onRestored(restored.count { it.status == RenderStatus.QUEUED })
        }
    }

    private fun persist(job: RenderJob) {
        val target = store ?: return
        scope.launch { target.save(job) }
    }

    private fun persistAll() {
        val target = store ?: return
        val snapshot = _queue.value
        scope.launch {
            target.saveAll(snapshot)
            target.retainOnly(snapshot)
        }
    }

    fun addJob(
        recordId: Long,
        recordTitle: String,
        config: VideoExporter.VideoExportConfig,
        targetUri: Uri?,
        recordIds: List<Long> = config.records.map { it.metadata.recordId },
        presetName: String? = null,
        sourceLabel: String? = null
    ): RenderJob {
        val job = RenderJob(
            recordId = recordId,
            recordTitle = recordTitle,
            config = config,
            targetUri = targetUri,
            recordIds = recordIds,
            presetName = presetName,
            sourceLabel = sourceLabel
        )
        synchronized(this) {
            _queue.value = _queue.value + job
        }
        persist(job)
        return job
    }

    fun removeJob(jobId: String) {
        synchronized(this) {
            _queue.value = _queue.value.filter { it.id != jobId }
        }
        store?.let { target -> scope.launch { target.delete(jobId) } }
    }

    fun cancelJob(jobId: String) {
        updateJobStatus(jobId, RenderStatus.CANCELLED)
        onJobCancelled?.invoke(jobId)
    }

    /**
     * Puts a failed job back in the queue, exactly as it was configured.
     *
     * The point of persisting the whole job rather than a note that one failed: a render that ran
     * out of space at 90% should not have to be rebuilt from the source, the clip and the framing
     * by hand. Progress and the error are cleared, because they describe the attempt rather than
     * the job.
     */
    fun retryJob(jobId: String) {
        var retried: RenderJob? = null
        synchronized(this) {
            _queue.value = _queue.value.map {
                if (it.id == jobId && it.isRetryable) {
                    it.copy(status = RenderStatus.QUEUED, progress = 0f, error = null)
                        .also { updated -> retried = updated }
                } else {
                    it
                }
            }
        }
        retried?.let(::persist)
    }

    /** Every job that could be run again, for a "retry all" that does not need each one tapped. */
    fun retryableJobs(): List<RenderJob> = _queue.value.filter { it.isRetryable }

    /**
     * Progress, in memory only.
     *
     * Deliberately not written to disk: it arrives many times a second, and it is worthless after a
     * restart because an interrupted render starts again from the beginning rather than resuming.
     */
    fun updateJobProgress(jobId: String, progress: Float) {
        synchronized(this) {
            _queue.value = _queue.value.map {
                if (it.id == jobId) it.copy(progress = progress) else it
            }
        }
    }

    fun updateJobStatus(jobId: String, status: RenderStatus, error: String? = null, targetUri: Uri? = null) {
        var changed: RenderJob? = null
        synchronized(this) {
            _queue.value = _queue.value.map {
                if (it.id == jobId) {
                    it.copy(
                        status = status,
                        error = error,
                        targetUri = targetUri ?: it.targetUri
                    ).also { updated -> changed = updated }
                } else {
                    it
                }
            }
        }
        changed?.let(::persist)
    }

    fun getNextJob(): RenderJob? = _queue.value.firstOrNull { it.status == RenderStatus.QUEUED }

    fun clearCompleted() {
        synchronized(this) {
            _queue.value = _queue.value.filter {
                it.status == RenderStatus.QUEUED || it.status == RenderStatus.RENDERING
            }
        }
        persistAll()
    }
}
