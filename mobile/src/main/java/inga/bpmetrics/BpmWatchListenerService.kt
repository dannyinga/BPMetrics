package inga.bpmetrics

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

class BpmWatchListenerService() : WearableListenerService() {

    private val context = BPMetricsApp.getAppContext()
    private var lastRecordJson = ""

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d("BpmListener", "Received new data")
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val item = event.dataItem
                if (item.uri.path == "/bpm_record") {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val recordJson = dataMap.getString("record_json") ?: continue
                    forwardToReceiver(recordJson)
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        // Real-time message path
        Log.d("BpmListener", "Received new message")
        if (messageEvent.path == "/bpm_record") {
            val recordJson = String(messageEvent.data, Charsets.UTF_8)
            forwardToReceiver(recordJson)
        }
    }

    private fun forwardToReceiver(recordJson: String) {
        if (recordJson != lastRecordJson) {
            val intent = Intent(context, BpmWatchRecordReceiver::class.java).apply {
                putExtra("extra_record_json", recordJson)
            }

            Log.d("BpmListener", "Forwarding new record Intent to Receiver")
            context.sendBroadcast(intent)
        }

        lastRecordJson = recordJson
    }

//    private val messageListener = MessageClient.OnMessageReceivedListener { messageEvent ->
//
//    }
//
//    private val dataListener = DataClient.OnDataChangedListener { dataEvents ->
//
//    }
//    fun register() {
//        Log.d("BpmListener", "Registering listeners")
//        Wearable.getMessageClient(context).addListener(messageListener)
//        Wearable.getDataClient(context).addListener(dataListener)
//        val nodeList = Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
//            for (node in nodes) {
//                Log.d("BpmListener", "Connected node = ${node.id}: ${node.displayName}")
//            }
//
//        }
//
//    }
//
//    fun unregister() {
//        Log.d("BpmListener", "Unregistering listeners")
//        Wearable.getMessageClient(context).removeListener(messageListener)
//        Wearable.getDataClient(context).removeListener(dataListener)
//    }

}