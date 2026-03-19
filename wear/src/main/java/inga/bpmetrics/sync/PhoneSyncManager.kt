package inga.bpmetrics.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import inga.bpmetrics.recording.RecordingRepository
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.db.PendingRecordEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

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
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    }

    /**
     * Deserializes and sends a pending record to the phone.
     */
    private fun processPendingRecord(entity: PendingRecordEntity) {
        try {
            val record = gson.fromJson(entity.recordJson, BpmWatchRecord::class.java)
            sendRecordToPhone(record) { success ->
                if (success) {
                    scope.launch {
                        repository.removePendingRecord(entity)
                        Log.d(tag, "Successfully synced and removed record ID: ${entity.id}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to process pending record ${entity.id}: ${e.message}")
        }
    }

    /**
     * Encodes a [BpmWatchRecord] into JSON and sends it to the phone via the Wearable network.
     *
     * @param record The record to send.
     * @param onResult Callback indicating if the DataClient accepted the request.
     */
    private fun sendRecordToPhone(record: BpmWatchRecord, onResult: (Boolean) -> Unit) {
        val recordJson = gson.toJson(record)
        val bytes = recordJson.toByteArray()
        val asset = Asset.createFromBytes(bytes)

        Log.d(tag, "Attempting to sync record - Points: ${record.dataPoints.size}, Size: ${bytes.size/1024} KB")

        val recordId = UUID.randomUUID().toString()
        val putDataMapRequest = PutDataMapRequest.create("/bpm_record/$recordId")
            .apply { dataMap.putAsset("record_asset", asset) }

        val request = putDataMapRequest.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener {
                Log.d(tag, "Record $recordId successfully queued in DataClient.")
                onResult(true)
            }
            .addOnFailureListener {
                Log.e(tag, "DataClient sync failed: ${it.message}")
                onResult(false)
            }
    }
}