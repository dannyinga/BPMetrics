package inga.bpmetrics.wear

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.*
import androidx.health.services.client.unregisterMeasureCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import java.sql.Date

import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord

/**
 * The model/factory layer of the watch's MVC architecture. When the factory starts recording, it
 * starts the HealthServices client and actively listens to the user's BPM data and stores it.
 * When the factory stops recording, it constructs a BpmWatchRecord from the user's data and sends
 * it back to the controller.
 */
class BpmWatchFactory(context: Context) {

    //TODO: Start HealthServices on initialization and keep BPM listening always active
    //TODO: Only store data points when a recording is started
    private val tag = "BPMetrics Watch Factory"

    private val measureClient by lazy {
        HealthServices.getClient(context).measureClient
    }

    private var isRecording = false
    private val dataPoints = mutableListOf<BpmDataPoint>()
    private var startTime: Long = 0L

    /**
     * Start recording BPM data and emit live updates.
     */
    fun startRecording(): Flow<MeasureMessage> = callbackFlow {
        if (isRecording) close(IllegalStateException("Already recording"))

        isRecording = true
        startTime = System.currentTimeMillis()
        dataPoints.clear()
        val startTimeFromBoot = SystemClock.elapsedRealtime()

        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(
                dataType: DeltaDataType<*, *>,
                availability: Availability
            ) {
                Log.d("BPMetrics", "AvailabilityChanged")
                if (availability is DataTypeAvailability) {
                    trySendBlocking(MeasureMessage.MeasureAvailability(availability))
                }
            }

            override fun onDataReceived(data: DataPointContainer) {
                val bpmData = data.getData(DataType.HEART_RATE_BPM)
                bpmData.forEach { point ->
                    val dataPoint = BpmDataPoint(
                        timestamp = point.timeDurationFromBoot.toMillis() - startTimeFromBoot, // uptime-based timestamp
                        bpm = point.value
                    )
                    dataPoints.add(dataPoint)
                    trySend(MeasureMessage.MeasureData(dataPoint)) // live emission
                }
            }
        }

        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        Log.d(tag, "Recording started")

        awaitClose {
            runBlocking {
                measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, callback)
                isRecording = false
                Log.d(tag, "Recording stopped")
            }
        }
    }

    /**
     * Stop recording and construct a complete watch record.
     */
    fun stopRecordingAndGetRecord(): BpmWatchRecord? {
        if (!isRecording || dataPoints.isEmpty()) return null

        val endTime = System.currentTimeMillis()

        return BpmWatchRecord(
            date = Date(System.currentTimeMillis()),
            dataPoints = dataPoints.toList().sorted(),
            startTime = startTime,
            endTime = endTime
        )
    }
}

/**
 * MeasureMessage class taken and modified from Google's HealthServices MeasureCompose sample.
 * The message that is to be sent in the HealthServices callbackFlow. Can be a BpmDataPoint or a
 * change in BPM data availability
 */
sealed class MeasureMessage {
    class MeasureAvailability(val availability: DataTypeAvailability) : MeasureMessage()
    class MeasureData(val data: BpmDataPoint) : MeasureMessage()
}
