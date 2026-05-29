package inga.bpmetrics.export

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val error: String? = null
)

object RenderQueueManager {
    private val _queue = MutableStateFlow<List<RenderJob>>(emptyList())
    val queue: StateFlow<List<RenderJob>> = _queue.asStateFlow()
    
    var onJobCancelled: ((String) -> Unit)? = null

    fun addJob(recordId: Long, recordTitle: String, config: VideoExporter.VideoExportConfig, targetUri: Uri?): RenderJob {
        val job = RenderJob(
            recordId = recordId,
            recordTitle = recordTitle,
            config = config,
            targetUri = targetUri
        )
        synchronized(this) {
            _queue.value = _queue.value + job
        }
        return job
    }

    fun removeJob(jobId: String) {
        synchronized(this) {
            _queue.value = _queue.value.filter { it.id != jobId }
        }
    }

    fun cancelJob(jobId: String) {
        synchronized(this) {
            _queue.value = _queue.value.map {
                if (it.id == jobId) {
                    it.copy(status = RenderStatus.CANCELLED)
                } else {
                    it
                }
            }
        }
        onJobCancelled?.invoke(jobId)
    }

    fun updateJobProgress(jobId: String, progress: Float) {
        synchronized(this) {
            _queue.value = _queue.value.map {
                if (it.id == jobId) {
                    it.copy(progress = progress)
                } else {
                    it
                }
            }
        }
    }

    fun updateJobStatus(jobId: String, status: RenderStatus, error: String? = null) {
        synchronized(this) {
            _queue.value = _queue.value.map {
                if (it.id == jobId) {
                    it.copy(status = status, error = error)
                } else {
                    it
                }
            }
        }
    }

    fun getNextJob(): RenderJob? {
        return _queue.value.firstOrNull { it.status == RenderStatus.QUEUED }
    }

    fun clearCompleted() {
        synchronized(this) {
            _queue.value = _queue.value.filter {
                it.status == RenderStatus.QUEUED || it.status == RenderStatus.RENDERING
            }
        }
    }
}
