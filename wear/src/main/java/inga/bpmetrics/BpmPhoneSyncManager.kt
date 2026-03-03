package inga.bpmetrics

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import inga.bpmetrics.core.BpmWatchRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manager responsible for synchronizing recorded BPM data from the watch to the paired phone.
 *
 * It observes the [BPMetricsRepository] for new [BpmWatchRecord] completions and uses 
 * the Wearable Data Client to transmit the record as a JSON-encoded asset.
 *
 * @param context The application context to initialize the Wearable Data Client.
 */
class BpmPhoneSyncManager(val context: Context) {
    private val tag = "BPMetrics Sync Manager"
    private val repository = BPMetricsRepository.getInstance(context)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Start observing the current record flow from the repository.
        // Whenever a new record is generated, it will be automatically sent to the phone.
        scope.launch {
            Log.d(tag, "Record collection listening started...")
            repository.currentRecord.collect { record ->
                sendRecordToPhone(record)
            }
        }
    }

    /**
     * Encodes a [BpmWatchRecord] into JSON and sends it to the phone via the Wearable network.
     *
     * The record is sent as an [Asset] within a [PutDataMapRequest] to ensure reliable delivery
     * even if the connection is temporarily lost.
     *
     * @param record The record to send. If null, the request is ignored.
     */
    fun sendRecordToPhone(record: BpmWatchRecord?) {
        if (record != null) {
            val recordJson = gson.toJson(record)
            val bytes = recordJson.toByteArray()
            val asset = Asset.createFromBytes(bytes)

            Log.d(tag, "Attempting to send record - " +
                    "\n\tData points size: ${record.dataPoints.size}" +
                    "\n\tTime: ${record.durationMs/1000} seconds" +
                    "\n\tSize: ${bytes.size/1024} KB")

            // Generate a unique ID for each record to avoid collisions in the Data Client storage.
            val recordId = UUID.randomUUID().toString()
            val putDataMapRequest = PutDataMapRequest.create("/bpm_record/$recordId")
                .apply { dataMap.putAsset("record_asset", asset) }

            // Mark the request as urgent to trigger immediate synchronization.
            val request = putDataMapRequest.asPutDataRequest().setUrgent()

            Wearable.getDataClient(context).putDataItem(request)
                .addOnSuccessListener {
                    Log.d(tag, "Record sent to the DataClient")
                }
                .addOnFailureListener { Log.e(tag, it.message ?: "") }
        }
    }
}
