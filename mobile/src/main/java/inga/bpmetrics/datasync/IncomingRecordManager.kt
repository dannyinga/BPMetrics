package inga.bpmetrics.datasync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a record has got to on its way in from a watch.
 *
 * Deliberately stages rather than a percentage. The transfer reads an asset of unknown length over
 * Bluetooth, so any percentage would be invented; what the user actually wants to know is whether
 * something is still coming and whether it landed.
 */
enum class IncomingStatus {
    /** Seen on the data layer, not yet read. */
    WAITING,

    /** Reading the payload off the watch. The slow part on Bluetooth. */
    RECEIVING,

    /** Payload read; writing the recording and its data points to the library. */
    SAVING,

    /** In the library. */
    COMPLETED,

    /** Did not arrive. The watch keeps its copy, so this will be retried. */
    FAILED
}

/**
 * One record arriving from a watch.
 *
 * @property id The data layer item id, so repeated events about the same record update one entry.
 * @property label What to call it before it has been read — the watch, once known.
 * @property receivedBytes Payload size once read, so a slow transfer is explicable.
 */
data class IncomingRecord(
    val id: String,
    val label: String,
    val status: IncomingStatus = IncomingStatus.WAITING,
    val receivedBytes: Int = 0,
    val error: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null
)

/**
 * Tracks records arriving from watches so the phone can show what is coming in.
 *
 * Without this a sync is invisible: recordings simply appear in the library some time later, with
 * no way to tell whether the rest are still on their way. After an event with several watches
 * that is exactly what you want to know.
 *
 * A process-wide singleton, matching [inga.bpmetrics.export.RenderQueueManager], because records
 * arrive through a manifest-registered service that outlives any screen.
 */
object IncomingRecordManager {

    private val _incoming = MutableStateFlow<List<IncomingRecord>>(emptyList())
    val incoming: StateFlow<List<IncomingRecord>> = _incoming.asStateFlow()

    /**
     * Notes that a record has been seen, or resets one being retried.
     */
    fun started(id: String, label: String) {
        synchronized(this) {
            val existing = _incoming.value.firstOrNull { it.id == id }
            _incoming.value = if (existing == null) {
                _incoming.value + IncomingRecord(
                    id = id,
                    label = label,
                    startedAt = System.currentTimeMillis()
                )
            } else {
                // A retry of something that failed before — put it back in flight rather than
                // adding a second entry for the same record.
                _incoming.value.map {
                    if (it.id == id) {
                        it.copy(status = IncomingStatus.WAITING, error = null, finishedAt = null)
                    } else it
                }
            }
        }
    }

    fun receiving(id: String) = update(id) { it.copy(status = IncomingStatus.RECEIVING) }

    fun saving(id: String, label: String, receivedBytes: Int) = update(id) {
        it.copy(status = IncomingStatus.SAVING, label = label, receivedBytes = receivedBytes)
    }

    fun completed(id: String, label: String) = update(id) {
        it.copy(
            status = IncomingStatus.COMPLETED,
            label = label,
            finishedAt = System.currentTimeMillis()
        )
    }

    fun failed(id: String, error: String?) = update(id) {
        it.copy(
            status = IncomingStatus.FAILED,
            error = error,
            finishedAt = System.currentTimeMillis()
        )
    }

    /** Clears everything that is no longer in flight. */
    fun clearFinished() {
        synchronized(this) {
            _incoming.value = _incoming.value.filter { it.status.isActive }
        }
    }

    private fun update(id: String, transform: (IncomingRecord) -> IncomingRecord) {
        synchronized(this) {
            _incoming.value = _incoming.value.map { if (it.id == id) transform(it) else it }
        }
    }
}

/** Whether this stage means the record is still on its way. */
val IncomingStatus.isActive: Boolean
    get() = this == IncomingStatus.WAITING ||
            this == IncomingStatus.RECEIVING ||
            this == IncomingStatus.SAVING
