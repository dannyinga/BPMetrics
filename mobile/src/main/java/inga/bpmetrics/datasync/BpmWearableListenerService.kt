package inga.bpmetrics.datasync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import inga.bpmetrics.BPMetricsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background listener service for receiving Wearable data items when the mobile app is not active.
 *
 * Registered in AndroidManifest.xml to automatically handle incoming BPM records from any connected watch.
 */
class BpmWearableListenerService : WearableListenerService() {

    private val tag = "BpmWearableListenerService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val app = applicationContext as? BPMetricsApp ?: return
        val processor = app.dataClientProcessor

        val itemsToProcess = mutableListOf<com.google.android.gms.wearable.DataItem>()
        try {
            for (event in dataEvents) {
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path?.startsWith("/bpm_record") == true) {
                    itemsToProcess.add(event.dataItem.freeze())
                }
            }
        } finally {
            dataEvents.release()
        }

        if (itemsToProcess.isNotEmpty()) {
            Log.d(tag, "Received ${itemsToProcess.size} record data items in background service")
            scope.launch {
                itemsToProcess.forEach { item ->
                    processor.processDataItem(item)
                }
            }
        }
    }
}
