package inga.bpmetrics.datasync

import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.gson.Gson
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.library.LibraryRepository
import kotlinx.coroutines.tasks.await

/**
 * Processor responsible for handling data items received from the Wearable Data Client.
 * 
 * This class identifies BPM records received from the watch via the Wearable Data layer,
 * deserializes the JSON assets into [BpmWatchRecord] objects, and saves them to the
 * local [LibraryRepository].
 *
 * @property dataClient The GMS Wearable Data Client used to delete processed items.
 * @property repository The [LibraryRepository] used for permanent storage of records.
 */
class DataClientProcessor(
    private val dataClient: DataClient,
    private val repository: LibraryRepository
) {
    /** List of record IDs processed during the current session to avoid duplicates. */
    private val processedIds = mutableListOf<String>()
    
    /** Tag used for identifying log entries from this processor. */
    private val tag = "DataClientProcessor"

    /**
     * Processes a single [DataItem] received from the watch.
     * 
     * Validates the item's path, checks for duplicates, and triggers the conversion
     * and storage flow. If successful, the item is deleted from the Wearable network.
     *
     * @param item The [DataItem] to process.
     */
    suspend fun processDataItem(item: DataItem) {
        // Only process items within the /bpm_record path
        if (item.uri.path?.startsWith("/bpm_record") == true) {
            val recordId = item.uri.lastPathSegment ?: return
            
            // Deduplication check
            if (processedIds.contains(recordId)) return
            processedIds.add(recordId)

            try {
                val record = convertDataItemToRecord(item)
                if (record != null) {
                    // Save to Room database
                    repository.saveWatchRecordToLibrary(record)
                    
                    // Clear the item from the Wearable "cloud" buffer
                    dataClient.deleteDataItems(item.uri).await()
                    Log.d(tag, "Record $recordId processed and deleted.")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error processing record $recordId", e)
                // Remove from processedIds so the sync can retry on next event
                processedIds.remove(recordId)
            }
        }
    }

    /**
     * Extracts the record JSON from a [DataItem] and deserializes it.
     *
     * @param item The [DataItem] containing the heart rate record asset.
     * @return The deserialized [BpmWatchRecord], or null if parsing fails.
     */
    private suspend fun convertDataItemToRecord(item: DataItem): BpmWatchRecord? {
        return try {
            val dataMap = DataMapItem.fromDataItem(item).dataMap
            val recordAsset = dataMap.getAsset("record_asset") ?: return null
            
            // Retrieve the file descriptor and read the input stream into a string
            val fd = dataClient.getFdForAsset(recordAsset).await()
            val inputStream = fd.inputStream
            val recordJson = inputStream.readBytes().toString(Charsets.UTF_8)
            
            // Parse JSON into core library object
            Gson().fromJson(recordJson, BpmWatchRecord::class.java)
        } catch (e: Exception) {
            Log.e(tag, "Failed to convert DataItem to record", e)
            null
        }
    }
}
