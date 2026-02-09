package inga.bpmetrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.unregisterMeasureCallback
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import java.sql.Date

class BpmListeningService: Service() {
    private val tag = "BPMetrics Service"
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val measureClient by lazy { HealthServices.getClient(this).measureClient }

    private val _bpmFlow = MutableStateFlow<Double?>(null)
    val bpmFlow: StateFlow<Double?> = _bpmFlow.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val dataPoints = mutableListOf<BpmDataPoint>()
    private var startTime: Long = 0L
    private var startTimeFromBoot: Long = 0L
    private var measureCallback: MeasureCallback? = null

    override fun onBind(intent: Intent?): IBinder = binder

    inner class LocalBinder : Binder() {
        fun getService(): BpmListeningService = this@BpmListeningService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service created")
        startListening()
    }

    override fun onDestroy() {
        Log.d(tag, "Service destroyed")
        serviceScope.launch {
            stopListening()
        }
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val channelId = "bpm_service_channel"
        val channel = NotificationChannel(
            channelId,
            "BPM Listening",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("BPMetrics")
            .setContentText("Listening to heart rate…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
    }

    fun startListening() {
        if (measureCallback != null) return // already active

        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(
                dataType: DeltaDataType<*, *>,
                availability: Availability
            ) {
                Log.d(tag, "AvailabilityChanged: $availability")
            }

            override fun onDataReceived(data: DataPointContainer) {
                val bpmData = data.getData(DataType.HEART_RATE_BPM)
                bpmData.forEach { point ->
                    val dataPoint = BpmDataPoint(
                        timestamp = point.timeDurationFromBoot.toMillis() - startTimeFromBoot,
                        bpm = point.value
                    )
                    _bpmFlow.value = dataPoint.bpm
                    if (_isRecording.value && point.value > 0) {
                        dataPoints.add(dataPoint)
                    }
                }
            }
        }

        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        measureCallback = callback
        Log.d(tag, "Listening started")
    }

    suspend fun stopListening() {
        measureCallback?.let { callback ->
            try {
                measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, callback)
                Log.d(tag, "Listening stopped")
            } catch (e: Exception) {
                Log.e(tag, "Failed to unregister callback", e)
            } finally {
                measureCallback = null
            }
        }

        serviceScope.cancel()
    }

    fun startRecording() {
        _isRecording.value = true
        dataPoints.clear()
        startTime = System.currentTimeMillis()
        startTimeFromBoot = SystemClock.elapsedRealtime()
        startForegroundWithNotification()
        Log.d(tag, "Recording started")
    }

    fun stopRecordingAndGetRecord(): BpmWatchRecord? {
        _isRecording.value = false

        val record = BpmWatchRecord(
            date = Date(startTime),
            dataPoints = dataPoints.toList().sorted(),
            startTime = startTime,
            endTime = System.currentTimeMillis()
        )

        if (dataPoints.isEmpty()) return null

        return record
    }
}