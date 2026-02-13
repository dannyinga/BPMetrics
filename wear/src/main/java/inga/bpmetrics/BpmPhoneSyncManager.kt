package inga.bpmetrics

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import inga.bpmetrics.core.BpmWatchRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BpmPhoneSyncManager(val context: Context) {
    private val tag = "BPMetrics Sync Manager"
    private val repository = BPMetricsRepository.instance
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            Log.d(tag, "Record collection listening started...")
            repository.currentRecord.collect { record ->
                sendRecordToPhone(record)
            }
        }
    }

    fun sendRecordToPhone(record: BpmWatchRecord?) {
        if (record != null) {
            val recordJson = gson.toJson(record)
            val request = PutDataMapRequest.create("/bpm_record").apply {
                dataMap.putString("record_json", recordJson)
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(context).putDataItem(request)
                .addOnSuccessListener {
                    Log.d(tag, "Record sent to phone")
                }
                .addOnFailureListener { Log.e(tag, it.message ?: "") }
        }
    }
}