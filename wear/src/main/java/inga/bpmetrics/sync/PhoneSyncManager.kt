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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

import kotlinx.coroutines.tasks.await

/**
 * Manager responsible for synchronizing recorded BPM data from the watch to the paired phone.
 *
 * It observes the [RecordingRepository] for new pending records in the database and
 * uses the Wearable Data Client to transmit them as JSON-encoded assets.
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

    private val _awaitingPhoneCount = MutableStateFlow(0)

    /**
     * Records handed to Play Services that the phone has not taken yet.
     *
     * The outbox alone cannot answer "how many is the phone still missing". putDataItem resolves
     * as soon as the local data store accepts the item — it does not wait for delivery — so a
     * record leaves the outbox whether or not the phone is anywhere nearby.
     *
     * What does track delivery is the data layer itself: the phone deletes each item once it has
     * saved the recording, and that deletion replicates back here. So anything still sitting
     * under `/bpm_record/` is something the phone has yet to receive.
     */
    val awaitingPhoneCount: StateFlow<Int> = _awaitingPhoneCount.asStateFlow()

    /** Recounts whenever the data layer changes, which includes the phone deleting an item. */
    private val dataListener = DataClient.OnDataChangedListener { events ->
        events.release()
        refreshAwaitingCount()
    }

    init {
        // Start observing the persistent pending records flow from the repository.
        scope.launch {
            Log.d(tag, "Record sync observer started...")
            repository.getPendingRecordsFlow().collect { pendingRecords ->
                if (pendingRecords.isNotEmpty()) {
                    Log.d(tag, "Found ${pendingRecords.size} records pending synchronization.")
                    pendingRecords.forEach { entity ->
                        processPendingRecord(entity)
                    }
                }
            }
        }

        Wearable.getDataClient(context).addListener(dataListener)
        refreshAwaitingCount()
    }

    /**
     * Counts the records still sitting on the data layer, unclaimed by the phone.
     *
     * Only this watch's own records count. The data layer replicates items to every node running
     * the app, so a second watch paired to the same phone can see the first one's outstanding
     * recordings — and without filtering on the originating node, each watch would report the
     * other's backlog as part of its own.
     */
    private fun refreshAwaitingCount() {
        scope.launch {
            try {
                val localNodeId = Wearable.getNodeClient(context).localNode.await().id
                val buffer = Wearable.getDataClient(context).dataItems.await()
                val outstanding = try {
                    buffer.count { item ->
                        item.uri.path?.startsWith("/bpm_record") == true &&
                                item.uri.host == localNodeId
                    }
                } finally {
                    buffer.release()
                }
                _awaitingPhoneCount.value = outstanding
                Log.d(tag, "$outstanding of this watch's record(s) still awaiting the phone")
            } catch (e: Exception) {
                Log.e(tag, "Could not count outstanding records: ${e.message}")
            }
        }
    }

    /**
     * Deserializes and sends a pending record to the phone.
     */
    private suspend fun processPendingRecord(entity: PendingRecordEntity) {
        try {
            val record = gson.fromJson(entity.recordJson, BpmWatchRecord::class.java)
            val success = sendRecordToPhone(record)
            if (success) {
                repository.removePendingRecord(entity)
                Log.d(tag, "Successfully synced and removed record ID: ${entity.id}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to process pending record ${entity.id}: ${e.message}")
        }
    }

    /**
     * Encodes a [BpmWatchRecord] into JSON and sends it to the phone via the Wearable network.
     *
     * @param record The record to send.
     * @return True if the DataClient accepted the request, false otherwise.
     */
    private suspend fun sendRecordToPhone(record: BpmWatchRecord): Boolean {
        return try {
            val recordJson = gson.toJson(record)
            val bytes = recordJson.toByteArray()
            val asset = Asset.createFromBytes(bytes)

            Log.d(tag, "Attempting to sync record (Device: ${record.deviceId}, Wearer: ${record.wearerName}) - Points: ${record.dataPoints.size}, Size: ${bytes.size/1024} KB")

            val recordId = UUID.randomUUID().toString()
            val putDataMapRequest = PutDataMapRequest.create("/bpm_record/$recordId")
                .apply { dataMap.putAsset("record_asset", asset) }

            val request = putDataMapRequest.asPutDataRequest().setUrgent()

            // Resolves once the local data store accepts the item. Delivery to the phone happens
            // afterwards, whenever Play Services can reach it — so this is a handover, not a
            // receipt. awaitingPhoneCount is what tracks the difference.
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(tag, "Record $recordId successfully queued in DataClient.")
            refreshAwaitingCount()
            true
        } catch (e: Exception) {
            Log.e(tag, "DataClient sync failed: ${e.message}")
            false
        }
    }
}