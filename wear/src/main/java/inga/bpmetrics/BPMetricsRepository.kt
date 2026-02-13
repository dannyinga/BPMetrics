package inga.bpmetrics

import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseUpdate
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import kotlinx.coroutines.flow.*
import java.sql.Date

/**
 * The core logic class. Takes input from the service and compiles it into records
 */
class BPMetricsRepository private constructor() {

    private val tag = "Bpm Repository"

    private val _hasAllPrerequisites = MutableStateFlow(false)
    val hasAllPrerequisites = _hasAllPrerequisites.asStateFlow()
    private val _liveBpm = MutableStateFlow<Double?>(null)
    val liveBpm: StateFlow<Double?> = _liveBpm.asStateFlow()
    private val _serviceState = MutableStateFlow(
        BpmServiceState.ASLEEP
    )
    val serviceState = _serviceState.asStateFlow()

    private val _currentRecord = MutableStateFlow<BpmWatchRecord?>(null)
    val currentRecord = _currentRecord.asStateFlow()

    private val _recordingStartTime = MutableStateFlow(0L)
    val recordingStartTime = _recordingStartTime.asStateFlow()


    /**
     * Record creation data
     */

    private val dataPoints = mutableListOf<BpmDataPoint>()
    private var startTimeForDate: Long = 0L

    fun onExerciseAvailabilityChanged(availability: Availability) {
        if (_serviceState.value != BpmServiceState.RECORDING) {
            _serviceState.value = when (availability) {
                    DataTypeAvailability.AVAILABLE -> BpmServiceState.READY
                    DataTypeAvailability.ACQUIRING -> BpmServiceState.PREPARING
                    else -> BpmServiceState.ASLEEP
                }
            Log.d(tag, "Availability changed: $availability")
        }
    }

    fun onExerciseUpdate(update: ExerciseUpdate) {
        Log.d(tag, "$update")

        if (update.exerciseStateInfo.state.isEnded) {
            Log.d(tag, "Service state changing to ASLEEP")
            _serviceState.value = BpmServiceState.ASLEEP
        }

        val samples = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
        samples.forEach { point ->
            val bpm = point.value
            val timestamp = point.timeDurationFromBoot.toMillis() - _recordingStartTime.value

            _liveBpm.value = bpm
            if (_serviceState.value == BpmServiceState.RECORDING)
                if (point.value > 0 && timestamp > 0) {
                    val dataPoint = BpmDataPoint(
                        timestamp = timestamp,
                        bpm = bpm
                    )
                    dataPoints.add(dataPoint)
                    Log.d(tag, "Exercise added point $dataPoint")
            }
        }
    }

    fun startRecording() {
        Log.d(tag, "Service state changing to RECORDING")
        resetDataForNewRecord()
        _serviceState.value = BpmServiceState.RECORDING
    }

    fun stopRecording()  {
        Log.d(tag, "Service state changing to ENDING")
        _serviceState.value = BpmServiceState.ENDING
        generateRecord()
    }

    fun grantAllPrerequisites() {
        _hasAllPrerequisites.value = true
    }

    private fun resetDataForNewRecord() {
        Log.d(tag, "Data being reset and recordingStartTime being set")
        _recordingStartTime.value = SystemClock.elapsedRealtime()
        dataPoints.clear()
        startTimeForDate = System.currentTimeMillis()
        _currentRecord.value = null
    }

    private fun generateRecord(){
        _currentRecord.value =
            if (dataPoints.isEmpty())
                null
            else
                BpmWatchRecord(
            date = Date(startTimeForDate),
            dataPoints = dataPoints.toList().sorted(),
            startTime = startTimeForDate,
            endTime = System.currentTimeMillis()
        )
    }

    companion object {
        val instance by lazy { BPMetricsRepository() }
    }
}

enum class BpmServiceState {
    ASLEEP, PREPARING, READY, RECORDING, ENDING
}