package inga.bpmetrics.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import inga.bpmetrics.core.BpmGson
import inga.bpmetrics.recording.RecordingRepository
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.db.PendingRecordEntity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import kotlinx.coroutines.tasks.await

/**
 * What one pass over the outbox achieved, for reporting back to the wearer.
 *
 * @property confirmed Records the phone acknowledged during this pass.
 * @property sent Records handed to Play Services during this pass.
 * @property stillWaiting Records the phone does not have yet, after the pass.
 * @property failed Whether the pass could not complete.
 */
data class SyncOutcome(
    val confirmed: Int = 0,
    val sent: Int = 0,
    val stillWaiting: Int = 0,
    val failed: Boolean = false
)

/**
 * Moves finished recordings from the watch to the paired phone.
 *
 * A record leaves the watch only once the phone says it has it. Handing a record to Play Services
 * is not that moment — `putDataItem` resolves against the local data store and succeeds with no
 * phone in range — so the outbox row is kept and marked with where the record went. The phone
 * deletes each data item once the recording is safely in its library, and that deletion is what
 * clears the row here.
 *
 * @param context The application context to initialize the Wearable Data Client.
 */
class PhoneSyncManager(val context: Context) {
    private val tag = "BPMetrics Sync Manager"
    private val repository = RecordingRepository.Companion.getInstance(context)
    private val gson = BpmGson.instance

    /**
     * Keeps sync failures from crashing the watch app.
     *
     * The outbox flow opens the database on first collection, so a database-level failure here
     * would otherwise reach the default uncaught handler and take down the process on launch.
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Unhandled failure in sync scope", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    /**
     * One pass over the outbox at a time.
     *
     * Four things start a pass — a finished recording, the data layer changing, the retry timer,
     * and the wearer pressing send — and two of them running together could hand the same record
     * over twice, leaving the phone with a duplicate.
     */
    private val passLock = Mutex()

    /** Recounts whenever the data layer changes, which includes the phone consuming an item. */
    private val dataListener = DataClient.OnDataChangedListener { events ->
        events.release()
        scope.launch { runPass() }
    }

    init {
        // New recordings: the flow fires when one is finalized, and again when it is marked as
        // handed over. The second pass finds nothing left to send, so this does not loop.
        scope.launch {
            Log.d(tag, "Record sync observer started...")
            repository.getPendingRecordsFlow().collect { pending ->
                if (pending.any { it.dataItemPath == null }) runPass()
            }
        }

        // Retry anything the outbox is still holding.
        //
        // The observer above only wakes when the table changes, so a record whose handover failed
        // — Play Services still starting up, no room in the local store — would sit there until
        // some *other* recording finished. On a watch handed to a friend for the evening, that can
        // be never. This asks again on its own.
        scope.launch {
            while (true) {
                delay(RETRY_INTERVAL_MS)
                runPass()
            }
        }

        Wearable.getDataClient(context).addListener(dataListener)
        scope.launch { runPass() }
    }

    /**
     * Clears out what the phone has taken, then offers it whatever it has not.
     *
     * Reconciling first means a record confirmed since the last pass is dropped before anything
     * else is attempted, so the count the wearer sees settles as soon as the phone catches up.
     */
    suspend fun syncNow(): SyncOutcome = runPass()

    private suspend fun runPass(): SyncOutcome = passLock.withLock {
        try {
            val confirmed = reconcileDelivered()
            val sent = handOverUnsent()
            val stillWaiting = repository.getPendingRecords().size

            if (confirmed > 0 || sent > 0) {
                Log.d(tag, "Sync pass: $confirmed confirmed, $sent sent, $stillWaiting still waiting")
            }
            SyncOutcome(confirmed = confirmed, sent = sent, stillWaiting = stillWaiting)
        } catch (e: Exception) {
            Log.e(tag, "Sync pass failed", e)
            SyncOutcome(stillWaiting = runCatching { repository.getPendingRecords().size }.getOrDefault(0), failed = true)
        }
    }

    /**
     * Drops the records the phone has acknowledged.
     *
     * An item vanishing from the data layer means the phone saved the recording and deleted it —
     * that deletion replicates back here, and it is the only receipt the data layer offers.
     */
    private suspend fun reconcileDelivered(): Int {
        // A null reading means the data layer could not be read, which is not the same as
        // "nothing is outstanding". Treating the two alike would delete the watch's only copy of
        // every record the moment Play Services had a bad day.
        val outstanding = outstandingPaths() ?: return 0

        var confirmed = 0
        repository.getPendingRecords().forEach { entity ->
            val path = entity.dataItemPath ?: return@forEach
            if (path !in outstanding) {
                repository.removePendingRecord(entity.id)
                confirmed++
                Log.d(tag, "Phone confirmed record ${entity.id}; removed from the watch")
            }
        }
        return confirmed
    }

    /** Offers Play Services every record that has not been handed over yet. */
    private suspend fun handOverUnsent(): Int {
        var sent = 0
        repository.getPendingRecords().forEach { entity ->
            if (entity.dataItemPath != null) return@forEach
            val path = handOver(entity)
            if (path != null) {
                repository.markPendingRecordHandedOver(entity.id, path)
                sent++
            }
        }
        return sent
    }

    /**
     * The record paths this watch still has sitting on the data layer.
     *
     * Only this watch's own records count. The data layer replicates items to every node running
     * the app, so a second watch paired to the same phone can see the first one's outstanding
     * recordings.
     *
     * @return the paths, or null if the data layer could not be read.
     */
    private suspend fun outstandingPaths(): Set<String>? {
        return try {
            val localNodeId = Wearable.getNodeClient(context).localNode.await().id
            val buffer = Wearable.getDataClient(context).dataItems.await()
            try {
                buffer.filter {
                    it.uri.host == localNodeId && it.uri.path?.startsWith(RECORD_PATH_PREFIX) == true
                }.mapNotNull { it.uri.path }.toSet()
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
            Log.e(tag, "Could not read the data layer: ${e.message}")
            null
        }
    }

    /**
     * Encodes a record and hands it to Play Services.
     *
     * @return the path it was written to, or null if it could not be handed over.
     */
    private suspend fun handOver(entity: PendingRecordEntity): String? {
        return try {
            val record = gson.fromJson(entity.recordJson, BpmWatchRecord::class.java)
            val recordJson = gson.toJson(record)
            val bytes = recordJson.toByteArray()
            val asset = Asset.createFromBytes(bytes)

            Log.d(
                tag,
                "Handing over record ${entity.id} (Device: ${record.deviceId}) - " +
                        "Points: ${record.dataPoints.size}, Size: ${bytes.size / 1024} KB"
            )

            // Derived from the outbox row rather than random, so that a handover which succeeded
            // without its row being marked — the process died in between — is re-offered to the
            // same place and overwrites itself. A fresh id each time would leave the phone with
            // two copies of the recording and no way to tell they were the same one.
            val path = "$RECORD_PATH_PREFIX/${repository.getWatchId()}-${entity.id}"
            val request = PutDataMapRequest.create(path)
                .apply { dataMap.putAsset("record_asset", asset) }
                .asPutDataRequest()
                .setUrgent()

            // Resolves once the local data store accepts the item. Delivery to the phone happens
            // afterwards, whenever Play Services can reach it — so this is a handover, not a
            // receipt, and the outbox row stays until the phone deletes the item.
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(tag, "Record ${entity.id} queued at $path.")
            path
        } catch (e: Exception) {
            Log.e(tag, "Handover of record ${entity.id} failed: ${e.message}")
            null
        }
    }

    private companion object {
        const val RECORD_PATH_PREFIX = "/bpm_record"

        /**
         * How often to re-offer a stranded record.
         *
         * Long, because the common case is a record already handed over and simply waiting on a
         * phone that is out of range — retrying that costs battery and achieves nothing.
         */
        const val RETRY_INTERVAL_MS = 5 * 60 * 1000L
    }
}
