package inga.bpmetrics

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.collectAsState
import com.google.android.gms.wearable.*
import inga.bpmetrics.core.*
import kotlinx.coroutines.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * The controller layer in the watch MVC architecture. The view tells the controller to start/stop
 * recording. The controller communicates with the factory to start/stop, updates the UI with the
 * live BPM, and sends the completed record to the paired device.
 */
class BpmWatchController private constructor(private val context: Context) {
    private val tag = "BPMetricsWatchController"
    private var service: BpmListeningService? = null
    private var bound = false
    private val gson = Gson()

    private val _bpmFlow = MutableStateFlow<Double?>(null)
    val bpmFlow: StateFlow<Double?> = _bpmFlow.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(tag, "Service connected")
            val localBinder = binder as BpmListeningService.LocalBinder
            service = localBinder.getService()
            bound = true

            scope.launch {
                service?.bpmFlow?.collect { value ->
                    _bpmFlow.value = value
                }
                service?.isRecording?.collect { value ->
                    _isRecording.value = value
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(tag, "Service disconnected")
            bound = false
            service = null
        }
    }

    fun bindService() {
        val intent = Intent(context, BpmListeningService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
    }

    fun startRecording() {
        service?.startRecording()
    }

    fun stopRecording() {
        val record = service?.stopRecordingAndGetRecord()
        Log.d(tag, "Sending this record to the phone: $record")
        record?.let { sendRecordToPhone(it) }
    }

    private fun sendRecordToPhone(record: BpmWatchRecord) {
        val recordJson = gson.toJson(record)
        val request = PutDataMapRequest.create("/bpm_record").apply {
            dataMap.putString("record_json", recordJson)
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener { Log.d(tag, "Record sent") }
            .addOnFailureListener { Log.e(tag, it.message ?: "") }
    }

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: BpmWatchController? = null
        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: BpmWatchController(context.applicationContext).also { instance = it }
            }
    }
}