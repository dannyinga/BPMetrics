package inga.bpmetrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import inga.bpmetrics.core.BpmWatchRecord

class BpmWatchRecordReceiver() : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recordJson = intent.getStringExtra("extra_record_json")
        if (recordJson != null) {
            val watchRecord = parseRecord(recordJson)

            // Forward to controller
            Log.d("BpmReceiver", "Sending new watch record to the controller")
            BpmLibrarian.getInstance().sendWatchRecordToLibrary(watchRecord)
        }
    }

    private fun parseRecord(recordJson: String): BpmWatchRecord {
        // Decode with Gson
        return Gson().fromJson(recordJson, BpmWatchRecord::class.java)
    }
}