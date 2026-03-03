package inga.bpmetrics.datasync

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Listener that monitors the Wearable Data Client for changes.
 *
 * This class observes the lifecycle of its host (usually an Activity or Application) and
 * manages registration with the GMS [DataClient]. It handles incoming data events from the watch
 * and ensures they are processed correctly via the [DataClientProcessor].
 *
 * @property dataClient The GMS Wearable Data Client used to manage data synchronization.
 * @property processor The [DataClientProcessor] used to parse and save incoming record data.
 */
class DataClientListener(
    private val dataClient: DataClient,
    private val processor: DataClientProcessor
) : DataClient.OnDataChangedListener, DefaultLifecycleObserver {

    /** Coroutine scope for handling background synchronization tasks. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /** Tag used for identifying log entries from this listener. */
    private val tag = "DataClientListener"

    /**
     * Called when the associated lifecycle starts.
     * Registers this listener with the [DataClient] and performs an initial sweep for missed records.
     *
     * @param owner The lifecycle owner being observed.
     */
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        dataClient.addListener(this)
        scope.launch { sweepForExistingRecords() }
    }

    /**
     * Called when the associated lifecycle stops.
     * Unregisters the listener to prevent memory leaks and unnecessary background work.
     *
     * @param owner The lifecycle owner being observed.
     */
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        dataClient.removeListener(this)
    }

    /**
     * Callback triggered when data items in the Wearable network change.
     *
     * Iterates through the [DataEventBuffer], identifies new or changed records,
     * freezes the data items for asynchronous processing, and passes them to the processor.
     *
     * @param dataEvents A buffer containing events related to data item changes.
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val itemsToProcess = mutableListOf<DataItem>()
        try {
            for (event in dataEvents) {
                if (event.type == DataEvent.TYPE_CHANGED) {
                    // Freezing the item is necessary because the buffer is closed after this function returns.
                    itemsToProcess.add(event.dataItem.freeze())
                }
            }
        } finally {
            // Releasing the buffer is required to prevent memory leaks in Play Services.
            dataEvents.release()
        }

        if (itemsToProcess.isNotEmpty()) {
            scope.launch {
                itemsToProcess.forEach { item ->
                    processor.processDataItem(item)
                }
            }
        }
    }

    /**
     * Queries the Wearable network for all current data items.
     *
     * This is used during initialization to ensure that records sent by the watch
     * while the mobile app was closed are still processed.
     */
    private suspend fun sweepForExistingRecords() {
        try {
            val dataItems = dataClient.dataItems.await()
            try {
                for (item in dataItems) {
                    processor.processDataItem(item)
                }
            } finally {
                dataItems.release()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to sweep for existing records", e)
        }
    }
}
