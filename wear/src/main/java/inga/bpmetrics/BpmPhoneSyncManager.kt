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
import kotlinx.coroutines.launch

class BpmPhoneSyncManager(
    val context: Context
) : DefaultLifecycleObserver {
    private val tag = "BPMetrics Sync Manager"
    private val repository = BPMetricsRepository.instance
    private val gson = Gson()

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        owner.lifecycleScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.currentRecord.collect { record ->
                    sendRecordToPhone(repository.currentRecord.value)
                }
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