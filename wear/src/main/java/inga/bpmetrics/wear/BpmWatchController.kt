package inga.bpmetrics.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.*
import inga.bpmetrics.core.*
import kotlinx.coroutines.*
import com.google.gson.Gson

/**
 * The controller layer in the watch MVC architecture. The view tells the controller to start/stop
 * recording. The controller communicates with the factory to start/stop, updates the UI with the
 * live BPM, and sends the completed record to the paired device.
 */
class BpmWatchController(
    private val context: Context
) {
    private val tag = "BPMetrics Watch Controller"
    private val factory = BpmWatchFactory(context)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** JSON encoder/decoder */
    private val gson = Gson()

    /** The recording job that is launched when a recording is started and cancelled when stopped */
    private var recordingJob: Job? = null

    /**
     * Launches the recording job. Called from the UI.
     * Tells the factory to start recording and sends live BPM back to the UI.
     */
    fun startRecording(onBpmUpdate: (Double) -> Unit) {
        recordingJob = scope.launch {

            Log.d(tag, "Recording job started")
            factory.startRecording().collect { message : MeasureMessage ->

                if (message is MeasureMessage.MeasureAvailability) {
                    Log.d(tag, "BPM availability: ${message.availability}")
                }
                else if (message is MeasureMessage.MeasureData){
                    onBpmUpdate(message.data.bpm)
                }

            }
        }
    }

    /**
     * Called from the UI. Cancels the current recording job and gets the record from the factory.
     * The record is then sent to the paired device.
     */
    fun stopRecording() {
        scope.launch {
            recordingJob?.cancel()
            recordingJob = null
            Log.d(tag, "Recording stopped")

            val record = factory.stopRecordingAndGetRecord()
            Log.d(tag, "Record created: \n${record}")
            record?.let {
                Log.d(tag, "Attempting to send record to phone")
                // sendRecordToPhone(it)
            }
        }
    }

    /**
     * Serializes the record as a JSON then uses the DataLayer API to send the record to the
     * paired device.
     */
    private fun sendRecordToPhone(record: BpmWatchRecord) {
        val client = Wearable.getDataClient(context)
        val serialized = gson.toJson(record)

        val request = PutDataMapRequest.create("/bpm_record").apply {
            dataMap.putString("record", serialized)
        }.asPutDataRequest().setUrgent()

        client.putDataItem(request)
    }
}