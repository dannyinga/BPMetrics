package inga.bpmetrics

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import inga.bpmetrics.core.BpmWatchRecord

class BpmSyncManager(
    val context: Context
) {
    private val tag = "BPMetrics Sync Manager"
    private val gson = Gson()

    fun sendRecordToPhone(record: BpmWatchRecord) {
        val recordJson = gson.toJson(record)
        val request = PutDataMapRequest.create("/bpm_record").apply {
            dataMap.putString("record_json", recordJson)
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener {
                Log.d(tag, "Record sent with ${record.dataPoints.size} entries")
                BpmRepository.instance.onRecordSent()
            }
            .addOnFailureListener { Log.e(tag, it.message ?: "") }
    }
}